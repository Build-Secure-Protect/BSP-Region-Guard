package com.mrgregles.bsp_region_guard.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagContext;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import com.mrgregles.bsp_region_guard.registry.ModItems;
import com.mrgregles.bsp_region_guard.selection.Selection;
import com.mrgregles.bsp_region_guard.selection.Selections;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /rg ...}: the text interface to regions, for admins and for testing before the screens exist.
 *
 * <p>Every command works on the world the player stands in. Region ids are per world.
 */
public final class RegionCommands {
    private static int plotResult(CommandSourceStack src, com.mrgregles.bsp_region_guard.claim.PlotMap.Result r) {
        if (r.ok()) {
            src.sendSuccess(() -> Component.literal(r.message()), false);
            return 1;
        }
        src.sendFailure(Component.literal(r.message()));
        return 0;
    }

    private static final SimpleCommandExceptionType NOT_A_PLAYER = new SimpleCommandExceptionType(Component.literal("Only a player can do that."));

    private static final SuggestionProvider<CommandSourceStack> REGION_IDS = (ctx, b) ->
            SharedSuggestionProvider.suggest(RegionStore.get(ctx.getSource().getLevel()).ids(), b);
    private static final SuggestionProvider<CommandSourceStack> FLAG_NAMES = (ctx, b) ->
            SharedSuggestionProvider.suggest(FlagRegistry.names(), b);
    private static final SuggestionProvider<CommandSourceStack> FLAG_VALUES = (ctx, b) -> {
        Flag<?> flag = FlagRegistry.get(StringArgumentType.getString(ctx, "flag"));
        List<String> out = new ArrayList<>(flag == null ? List.of() : flag.suggestions());
        out.add("clear");
        return SharedSuggestionProvider.suggest(out, b);
    };
    private static final SuggestionProvider<CommandSourceStack> TYPES = (ctx, b) ->
            SharedSuggestionProvider.suggest(List.of("protected", "plot_for_sale", "open_for_buying", "claim", "mine"), b);

    private RegionCommands() {
    }

    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> rg = Commands.literal("rg")
                .requires(src -> src.hasPermission(2) || src.getEntity() instanceof ServerPlayer p && BspCore.isAdmin(p));

        rg.then(Commands.literal("wand").executes(ctx -> wand(ctx.getSource())));
        rg.then(Commands.literal("pos1").executes(ctx -> pos(ctx.getSource(), true)));
        rg.then(Commands.literal("pos2").executes(ctx -> pos(ctx.getSource(), false)));
        rg.then(Commands.literal("select").then(id().executes(ctx -> select(ctx.getSource(), id(ctx)))));
        for (String name : new String[]{"define", "create"}) { // without a selection: a region without corners (D76)
            rg.then(Commands.literal(name).then(id()
                    .executes(ctx -> define(ctx.getSource(), id(ctx), 0))
                    .then(Commands.argument("priority", IntegerArgumentType.integer())
                            .executes(ctx -> define(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "priority"))))));
        }
        rg.then(Commands.literal("redefine").then(id().executes(ctx -> redefine(ctx.getSource(), id(ctx)))
                .then(Commands.argument("from", BlockPosArgument.blockPos()).then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(ctx -> redefine(ctx.getSource(), id(ctx), Cuboid.of(BlockPosArgument.getBlockPos(ctx, "from"), BlockPosArgument.getBlockPos(ctx, "to"))))))));
        rg.then(Commands.literal("vfill")
                .executes(ctx -> answer(ctx.getSource(), RegionEdits.verticalFillSelection(player(ctx.getSource()))))
                .then(id().executes(ctx -> answer(ctx.getSource(), RegionEdits.verticalFill(ctx.getSource().getLevel(), RegionStore.get(ctx.getSource().getLevel()),
                        region(ctx.getSource(), id(ctx)))))));
        rg.then(Commands.literal("remove").then(id().executes(ctx -> remove(ctx.getSource(), id(ctx)))));
        rg.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));
        rg.then(Commands.literal("info")
                .executes(ctx -> here(ctx.getSource()))
                .then(id().executes(ctx -> info(ctx.getSource(), id(ctx)))));
        rg.then(Commands.literal("here").executes(ctx -> here(ctx.getSource())));
        rg.then(Commands.literal("flags").executes(ctx -> flags(ctx.getSource())));
        rg.then(Commands.literal("flag").then(id()
                .then(Commands.argument("flag", StringArgumentType.word()).suggests(FLAG_NAMES)
                        .executes(ctx -> showFlag(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "flag")))
                        .then(Commands.argument("value", StringArgumentType.greedyString()).suggests(FLAG_VALUES)
                                .executes(ctx -> setFlag(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "flag"),
                                        StringArgumentType.getString(ctx, "value")))))));
        rg.then(people("addowner", true, true));
        rg.then(people("removeowner", true, false));
        rg.then(people("addmember", false, true));
        rg.then(people("removemember", false, false));
        rg.then(Commands.literal("addrank").then(id().then(Commands.argument("rank", StringArgumentType.word())
                .executes(ctx -> rank(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "rank"), true)))));
        rg.then(Commands.literal("removerank").then(id().then(Commands.argument("rank", StringArgumentType.word())
                .executes(ctx -> rank(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "rank"), false)))));
        rg.then(Commands.literal("priority").then(id().then(Commands.argument("priority", IntegerArgumentType.integer())
                .executes(ctx -> priority(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "priority"))))));
        rg.then(Commands.literal("parent").then(id().then(Commands.argument("parent", StringArgumentType.word()).suggests(REGION_IDS)
                .executes(ctx -> parent(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "parent"))))));
        rg.then(Commands.literal("type").then(id().then(Commands.argument("type", StringArgumentType.word()).suggests(TYPES)
                .executes(ctx -> type(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "type"))))));
        rg.then(Commands.literal("rename").then(id().then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> rename(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "name"))))));
        rg.then(Commands.literal("teleport").then(id().executes(ctx -> teleport(ctx.getSource(), id(ctx)))));
        rg.then(Commands.literal("bypass").executes(ctx -> bypass(ctx.getSource())));
        rg.then(Commands.literal("border")
                .executes(ctx -> border(ctx.getSource()))
                .then(Commands.literal("off").executes(ctx -> answer(ctx.getSource(), RegionEdits.borderOff(ctx.getSource().getLevel()))))
                .then(Commands.literal("links").executes(ctx -> borderLinks(ctx.getSource())))
                .then(Commands.literal("unlink").then(Commands.argument("side", StringArgumentType.word()).suggests(SIDES)
                        .executes(ctx -> answer(ctx.getSource(), RegionEdits.edgeUnlink(ctx.getSource().getLevel(), StringArgumentType.getString(ctx, "side"))))))
                .then(Commands.literal("link").then(Commands.argument("side", StringArgumentType.word()).suggests(SIDES)
                        .then(Commands.argument("server", StringArgumentType.word()).suggests(NETWORK_SERVERS)
                                .then(Commands.argument("world", StringArgumentType.string()).then(Commands.argument("theirSide", StringArgumentType.word()).suggests(SIDES)
                                        .executes(ctx -> answer(ctx.getSource(), RegionEdits.edgeLink(ctx.getSource().getLevel(), StringArgumentType.getString(ctx, "side"),
                                                StringArgumentType.getString(ctx, "server"), StringArgumentType.getString(ctx, "world"), StringArgumentType.getString(ctx, "theirSide")))))))))
                .then(Commands.literal("set").then(Commands.argument("width", IntegerArgumentType.integer(16, 59_999_968))
                        .executes(ctx -> answer(ctx.getSource(), RegionEdits.border(ctx.getSource().getLevel(), IntegerArgumentType.getInteger(ctx, "width"), 0, 0)))
                        .then(Commands.argument("centerX", DoubleArgumentType.doubleArg()).then(Commands.argument("centerZ", DoubleArgumentType.doubleArg())
                                .executes(ctx -> answer(ctx.getSource(), RegionEdits.border(ctx.getSource().getLevel(), IntegerArgumentType.getInteger(ctx, "width"),
                                        DoubleArgumentType.getDouble(ctx, "centerX"), DoubleArgumentType.getDouble(ctx, "centerZ")))))))));
        rg.then(Commands.literal("sale").then(id().then(Commands.argument("price", LongArgumentType.longArg(0))
                .executes(ctx -> sale(ctx.getSource(), id(ctx), LongArgumentType.getLong(ctx, "price"))))));
        rg.then(Commands.literal("open").then(id().then(Commands.argument("costPerBlock", LongArgumentType.longArg(0))
                .executes(ctx -> open(ctx.getSource(), id(ctx), LongArgumentType.getLong(ctx, "costPerBlock"), 0, 0))
                .then(Commands.argument("minSize", IntegerArgumentType.integer(1)).then(Commands.argument("maxSize", IntegerArgumentType.integer(1))
                        .executes(ctx -> open(ctx.getSource(), id(ctx), LongArgumentType.getLong(ctx, "costPerBlock"),
                                IntegerArgumentType.getInteger(ctx, "minSize"), IntegerArgumentType.getInteger(ctx, "maxSize"))))))));
        rg.then(Commands.literal("cointier").then(id().then(Commands.argument("tier", StringArgumentType.word())
                .suggests((c, b) -> {
                    List<String> tiers = new ArrayList<>(BspCore.coinTiers());
                    tiers.add("default");
                    return SharedSuggestionProvider.suggest(tiers, b);
                })
                .executes(ctx -> answer(ctx.getSource(), RegionEdits.coinTier(RegionStore.get(ctx.getSource().getLevel()), region(ctx.getSource(), id(ctx)),
                        StringArgumentType.getString(ctx, "tier")))))));
        rg.then(Commands.literal("plotlimit").then(id()
                .executes(ctx -> {
                    int n = com.mrgregles.bsp_region_guard.claim.ClaimData.plotLimit(region(ctx.getSource(), id(ctx)));
                    return ok(ctx.getSource(), n < 0 ? "No plot limit on " + id(ctx) + "." : "A player may own " + n + " plot" + (n == 1 ? "" : "s") + " inside " + id(ctx) + ".");
                })
                .then(Commands.argument("limit", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("none", "1", "2", "3"), b))
                        .executes(ctx -> answer(ctx.getSource(), RegionEdits.plotLimit(RegionStore.get(ctx.getSource().getLevel()), region(ctx.getSource(), id(ctx)),
                                StringArgumentType.getString(ctx, "limit")))))));
        rg.then(Commands.literal("plotflags").then(id().then(Commands.argument("flags", StringArgumentType.greedyString())
                .executes(ctx -> plotFlags(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "flags"))))));
        rg.then(Commands.literal("mine")
                .then(Commands.literal("create").then(id().executes(ctx -> mineCreate(ctx.getSource(), id(ctx)))))
                .then(Commands.literal("add").then(id().then(Commands.argument("block", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .suggests((c, b) -> SharedSuggestionProvider.suggestResource(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKeys(), b))
                        .then(Commands.argument("weight", IntegerArgumentType.integer(0, 1000000))
                                .executes(ctx -> mineAdd(ctx.getSource(), id(ctx), net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "block"), IntegerArgumentType.getInteger(ctx, "weight")))))))
                .then(Commands.literal("remove").then(id().then(Commands.argument("block", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .executes(ctx -> mineAdd(ctx.getSource(), id(ctx), net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "block"), 0)))))
                .then(Commands.literal("interval").then(id().then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                        .executes(ctx -> mineInterval(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "seconds"))))))
                .then(Commands.literal("spawn").then(id().executes(ctx -> mineSpawn(ctx.getSource(), id(ctx)))))
                .then(Commands.literal("shell").then(id().then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> mineShell(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(Commands.literal("reset").then(id().executes(ctx -> mineReset(ctx.getSource(), id(ctx)))))
                .then(Commands.literal("info").then(id().executes(ctx -> mineInfo(ctx.getSource(), id(ctx))))));
        rg.then(Commands.literal("trigger")
                .then(Commands.literal("list").then(id().executes(ctx -> triggerList(ctx.getSource(), id(ctx)))))
                .then(Commands.literal("remove").then(id().then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> triggerRemove(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "index"))))))
                .then(Commands.literal("add").then(id().then(Commands.argument("cause", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("enter", "exit", "timer", "shutdown"), b))
                        .then(Commands.argument("destination", StringArgumentType.greedyString()).suggests(RegionCommands::suggestDestination)
                                .executes(ctx -> triggerAdd(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "cause"), StringArgumentType.getString(ctx, "destination"))))))));
        rg.then(Commands.literal("storage")
                .then(Commands.literal("status").executes(ctx -> {
                    var m = com.mrgregles.bsp_region_guard.storage.RegionMirror.active();
                    return ok(ctx.getSource(), m == null ? "Shared storage is off (storage.mode = none)." : m.status());
                }))
                .then(Commands.literal("sync").executes(ctx -> {
                    var m = com.mrgregles.bsp_region_guard.storage.RegionMirror.active();
                    if (m == null) {
                        return fail(ctx.getSource(), "Shared storage is off.");
                    }
                    m.reconcile(ctx.getSource().getLevel());
                    return ok(ctx.getSource(), "Reconciling " + ctx.getSource().getLevel().dimension().location() + " with the database; see the log.");
                }))
                .then(Commands.literal("servers").executes(ctx -> {
                    var m = com.mrgregles.bsp_region_guard.storage.RegionMirror.active();
                    if (m == null) {
                        return fail(ctx.getSource(), "Shared storage is off.");
                    }
                    CommandSourceStack src = ctx.getSource();
                    m.countByServer(counts -> {
                        StringBuilder sb = new StringBuilder("Regions per server:");
                        counts.forEach((server, n) -> sb.append("\n  ").append(server).append(server.equals(m.serverId()) ? " (this server)" : "").append(": ").append(n));
                        src.sendSuccess(() -> Component.literal(counts.isEmpty() ? "No regions in the database yet." : sb.toString()), false);
                    });
                    return 1;
                })));
        rg.then(Commands.literal("claims")
                .then(Commands.literal("list").executes(ctx -> claimsList(ctx.getSource())))
                .then(Commands.literal("pvp")
                        .executes(ctx -> ok(ctx.getSource(), plotRuleText(ctx.getSource().getLevel())))
                        .then(Commands.argument("rule", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("allow", "deny", "default"), b))
                                .executes(ctx -> answer(ctx.getSource(), RegionEdits.plotsInPvp(ctx.getSource().getLevel(), StringArgumentType.getString(ctx, "rule"))))))
                .then(Commands.literal("wipe").executes(ctx -> ok(ctx.getSource(), com.mrgregles.bsp_region_guard.claim.ClaimWipe.request(player(ctx.getSource()), ctx.getSource().getLevel())))
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> ok(ctx.getSource(), com.mrgregles.bsp_region_guard.claim.ClaimWipe.confirm(player(ctx.getSource()), ctx.getSource().getLevel(), StringArgumentType.getString(ctx, "code"))))))
                .then(Commands.literal("backups").executes(ctx -> {
                    var files = com.mrgregles.bsp_region_guard.claim.ClaimWipe.backups(ctx.getSource().getServer());
                    return ok(ctx.getSource(), files.isEmpty() ? "No claim backups." : "Claim backups, newest first: " + String.join(", ", files));
                }))
                .then(Commands.literal("restore").then(Commands.argument("file", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(com.mrgregles.bsp_region_guard.claim.ClaimWipe.backups(c.getSource().getServer()), b))
                        .executes(ctx -> ok(ctx.getSource(), com.mrgregles.bsp_region_guard.claim.ClaimWipe.restore(player(ctx.getSource()), StringArgumentType.getString(ctx, "file")))))));
        rg.then(Commands.literal("ranks").executes(ctx -> {
            boolean present = com.mrgregles.bsp_region_guard.compat.LuckPermsBridge.isPresent();
            var groups = com.mrgregles.bsp_region_guard.compat.LuckPermsBridge.groupNames();
            return ok(ctx.getSource(), "LuckPerms: " + (present ? "found" : "not found") + (present ? ". Groups: " + (groups.isEmpty() ? "none loaded" : String.join(", ", groups)) : ". Rank checks are skipped."));
        }).then(Commands.argument("players", GameProfileArgument.gameProfile()).executes(ctx -> {
            StringBuilder sb = new StringBuilder();
            for (GameProfile gp : GameProfileArgument.getGameProfiles(ctx, "players")) {
                var groups = com.mrgregles.bsp_region_guard.compat.LuckPermsBridge.groupsOf(gp.getId());
                sb.append(sb.length() == 0 ? "" : "; ").append(gp.getName()).append(": ").append(groups.isEmpty() ? "no groups loaded (offline players are not loaded)" : String.join(", ", groups));
            }
            return ok(ctx.getSource(), sb.toString());
        })));
        rg.then(Commands.literal("plotmap").executes(ctx -> {
            ServerPlayer p = player(ctx.getSource());
            if (!com.mrgregles.bsp_region_guard.compat.BspCore.isAdmin(p)) {
                ctx.getSource().sendFailure(Component.literal("Only BSP-Core admins can use the plot map."));
                return 0;
            }
            com.mrgregles.bsp_region_guard.network.PlotMapSync.open(p);
            return 1;
        }));
        rg.then(Commands.literal("map").executes(ctx -> {
            ServerPlayer p = player(ctx.getSource());
            if (!com.mrgregles.bsp_region_guard.compat.BspCore.isAdmin(p)) {
                ctx.getSource().sendFailure(Component.literal("Only BSP-Core admins can use the plot map."));
                return 0;
            }
            com.mrgregles.bsp_region_guard.network.PlotMapSync.open(p);
            return 1;
        }));
        // D69: backups of a plot's blocks
        rg.then(Commands.literal("plot")
                .then(Commands.literal("backup").then(id().executes(ctx -> plotResult(ctx.getSource(),
                        com.mrgregles.bsp_region_guard.claim.PlotMap.backup(player(ctx.getSource()), id(ctx))))))
                .then(Commands.literal("backups").then(id().executes(ctx -> {
                    var files = com.mrgregles.bsp_region_guard.claim.PlotBlocks.backups(ctx.getSource().getServer(), id(ctx));
                    ctx.getSource().sendSuccess(() -> Component.literal(files.isEmpty() ? "No backups of " + id(ctx) + " yet."
                            : "Backups of " + id(ctx) + ", newest first:\n  " + String.join("\n  ", files)), false);
                    return files.size();
                })))
                .then(Commands.literal("restore").then(Commands.argument("file", StringArgumentType.string()).executes(ctx -> plotResult(ctx.getSource(),
                        com.mrgregles.bsp_region_guard.claim.PlotMap.restore(player(ctx.getSource()), StringArgumentType.getString(ctx, "file")))))));
        rg.then(Commands.literal("gui").executes(ctx -> {
            com.mrgregles.bsp_region_guard.network.RegionSync.open(player(ctx.getSource()));
            return 1;
        }));

        dispatcher.register(rg);
        dispatcher.register(Commands.literal("regionguard").requires(rg.getRequirement()).redirect(dispatcher.getRoot().getChild("rg")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> people(String literal, boolean owners, boolean add) {
        return Commands.literal(literal).then(id().then(Commands.argument("players", GameProfileArgument.gameProfile())
                .executes(ctx -> people(ctx.getSource(), id(ctx), GameProfileArgument.getGameProfiles(ctx, "players"), owners, add))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> id() {
        return Commands.argument("id", StringArgumentType.word()).suggests(REGION_IDS);
    }

    private static String id(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "id");
    }

    // ------------------------------------------------------------------ helpers

    private static ServerPlayer player(CommandSourceStack src) throws CommandSyntaxException {
        if (src.getEntity() instanceof ServerPlayer p) {
            return p;
        }
        throw NOT_A_PLAYER.create();
    }

    private static Region region(CommandSourceStack src, String id) throws CommandSyntaxException {
        RegionStore store = RegionStore.get(src.getLevel());
        if (id.equalsIgnoreCase(Region.GLOBAL_ID)) {
            return store.global();
        }
        Region r = store.get(id);
        if (r == null) {
            throw new SimpleCommandExceptionType(Component.literal("No region named '" + id + "' in this world. Use /rg list.")).create();
        }
        return r;
    }

    /** What a claim cost, per currency it was paid in. */
    private static String paid(Region r) {
        long pieces = com.mrgregles.bsp_region_guard.claim.ClaimData.boughtFor(r), coins = com.mrgregles.bsp_region_guard.claim.ClaimData.boughtCoins(r);
        if (coins <= 0) {
            return BspCore.money(pieces);
        }
        String c = com.mrgregles.bsp_region_guard.claim.Prices.worthText(coins);
        return pieces > 0 ? BspCore.money(pieces) + " + " + c : c;
    }

    /** {@code /rg claims pvp}: whether plots may be made in this world, and why. */
    private static String plotRuleText(net.minecraft.server.level.ServerLevel level) {
        String world = level.dimension().location().toString();
        return switch (com.mrgregles.bsp_region_guard.protection.TotemRule.plotRule(level)) {
            case NOT_PVP -> "Plots are allowed in " + world + ": it is not a PvP world.";
            case PVP_DENIED -> "Plots are allowed in " + world + ": pvp is denied on the world default.";
            case WORLD_ALLOWS -> "Plots are allowed in " + world + " (set on this world, /rg claims pvp default to follow the config).";
            case CONFIG_ALLOWS -> "Plots are allowed in " + world + " (claims.allowInPvpWorlds in the config).";
            case WORLD_REFUSES -> "Plots are refused in " + world + ", a PvP world (set on this world).";
            case CONFIG_REFUSES -> "Plots are refused in " + world + ", a PvP world (the config). /rg claims pvp allow to allow them here.";
        };
    }

    private static int ok(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), true);
        return 1;
    }

    private static int fail(CommandSourceStack src, String text) {
        src.sendFailure(Component.literal(text));
        return 0;
    }

    private static int answer(CommandSourceStack src, RegionEdits.Result result) {
        return result.ok() ? ok(src, result.message()) : fail(src, result.message());
    }

    private static String name(CommandSourceStack src, UUID uuid) {
        Optional<GameProfile> profile = src.getServer().getProfileCache() == null ? Optional.empty() : src.getServer().getProfileCache().get(uuid);
        return profile.map(GameProfile::getName).orElse(uuid.toString().substring(0, 8));
    }

    private static final SuggestionProvider<CommandSourceStack> SIDES = (ctx, b) -> SharedSuggestionProvider.suggest(List.of("north", "east", "south", "west"), b);
    private static final SuggestionProvider<CommandSourceStack> NETWORK_SERVERS = (ctx, b) -> SharedSuggestionProvider.suggest(
            com.mrgregles.bsp_region_guard.protection.EdgeLinks.network().stream().map(w -> w.server()).distinct().toList(), b);

    /** Completes a trigger's destination word by word (see {@link TriggerSuggestions}). */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestDestination(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CommandSourceStack src = ctx.getSource();
        BlockPos at = src.getEntity() == null ? null : src.getEntity().blockPosition();
        List<String> worlds = src.getServer().levelKeys().stream().map(k -> k.location().toString()).sorted().toList();
        List<String> remote = com.mrgregles.bsp_region_guard.protection.EdgeLinks.network().stream().map(w -> w.server() + "/" + w.dimension()).sorted().toList();
        var result = TriggerSuggestions.suggest(StringArgumentType.getString(ctx, "cause"), builder.getRemaining(),
                at == null ? null : new int[]{at.getX(), at.getY(), at.getZ()}, worlds, remote);
        var next = builder.createOffset(builder.getStart() + result.start());
        result.suggestions().forEach(next::suggest);
        return next.buildFuture();
    }

    private static int borderLinks(CommandSourceStack src) {
        var level = src.getLevel();
        MutableComponent out = Component.literal("Edges of " + level.dimension().location() + ":");
        var links = com.mrgregles.bsp_region_guard.protection.EdgeLinks.effective(level);
        for (var side : com.mrgregles.bsp_region_guard.protection.EdgeLinks.Side.values()) {
            var p = links.get(side);
            out.append("\n  " + side.key() + ": " + (p == null ? "not linked" : p.server() + " " + p.dimension() + ", its " + p.side().key() + " edge"));
        }
        var network = com.mrgregles.bsp_region_guard.protection.EdgeLinks.network();
        out.append("\nOther servers' worlds" + (network.isEmpty() ? ": none known (shared storage off, or not read yet)" : ":"));
        for (var w : network) {
            out.append("\n  " + w.server() + " " + w.dimension() + ": " + (w.border() == null ? "no border" : w.border().size() + " wide"));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static int border(CommandSourceStack src) {
        var b = com.mrgregles.bsp_region_guard.protection.WorldBorders.of(src.getLevel());
        return ok(src, b == null ? src.getLevel().dimension().location() + " has no world border. Set one: /rg border set <width> [<centerX> <centerZ>]."
                : "World border of " + src.getLevel().dimension().location() + ": " + b + ".");
    }

    private static int bypass(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        if (!BspCore.isAdmin(p)) {
            return fail(src, "Only BSP Core admins have a bypass to switch.");
        }
        boolean on = !RegionQuery.bypassSetting(p.getUUID());
        RegionQuery.setBypass(p.getUUID(), on);
        return ok(src, on ? "Region bypass on: regions do not stop you." : "Region bypass off: regions treat you like any player.");
    }

    // ------------------------------------------------------------------ selection

    private static int wand(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        ItemStack wand = new ItemStack(ModItems.REGION_WAND.get());
        if (!p.getInventory().add(wand)) {
            p.drop(wand, false);
        }
        return ok(src, "Region wand given. Left-click a block for the first corner, right-click for the second.");
    }

    private static int pos(CommandSourceStack src, boolean first) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        Selection sel = Selections.of(p);
        BlockPos pos = p.blockPosition();
        if (first) {
            sel.setPos1(p.serverLevel().dimension(), pos);
        } else {
            sel.setPos2(p.serverLevel().dimension(), pos);
        }
        Selections.sync(p);
        Cuboid box = sel.cuboid();
        return ok(src, (first ? "First" : "Second") + " corner set to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + (box == null ? "" : " (" + box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ() + ")"));
    }

    private static int select(CommandSourceStack src, String id) throws CommandSyntaxException {
        return answer(src, RegionEdits.selectBounds(player(src), region(src, id)));
    }

    // ------------------------------------------------------------------ create, change, remove

    private static int define(CommandSourceStack src, String id, int priority) throws CommandSyntaxException {
        return answer(src, RegionEdits.create(src.getLevel(), id, RegionEdits.selectionIn(player(src)), priority));
    }

    private static int redefine(CommandSourceStack src, String id) throws CommandSyntaxException {
        return redefine(src, id, RegionEdits.selectionIn(player(src)));
    }

    private static int redefine(CommandSourceStack src, String id, @Nullable Cuboid box) throws CommandSyntaxException {
        Region r = region(src, id);
        return answer(src, RegionEdits.redefine(src.getLevel(), RegionStore.get(src.getLevel()), r, box));
    }

    private static int remove(CommandSourceStack src, String id) throws CommandSyntaxException {
        return answer(src, RegionEdits.remove(RegionStore.get(src.getLevel()), region(src, id)));
    }

    private static int priority(CommandSourceStack src, String id, int priority) throws CommandSyntaxException {
        return answer(src, RegionEdits.priority(RegionStore.get(src.getLevel()), region(src, id), priority));
    }

    private static int parent(CommandSourceStack src, String id, String parentId) throws CommandSyntaxException {
        return answer(src, RegionEdits.parent(RegionStore.get(src.getLevel()), region(src, id), parentId));
    }

    private static int type(CommandSourceStack src, String id, String typeKey) throws CommandSyntaxException {
        return answer(src, RegionEdits.type(src.getLevel(), RegionStore.get(src.getLevel()), region(src, id), typeKey));
    }

    private static int rename(CommandSourceStack src, String id, String name) throws CommandSyntaxException {
        return answer(src, RegionEdits.rename(RegionStore.get(src.getLevel()), region(src, id), name));
    }

    private static int people(CommandSourceStack src, String id, Collection<GameProfile> profiles, boolean owners, boolean add) throws CommandSyntaxException {
        Region r = region(src, id);
        RegionStore store = RegionStore.get(src.getLevel());
        int changed = 0;
        for (GameProfile gp : profiles) {
            if (RegionEdits.person(store, r, gp.getId(), owners, add)) {
                changed++;
            }
        }
        String who = owners ? "owner" : "member";
        return ok(src, changed + " " + who + (changed == 1 ? "" : "s") + (add ? " added to " : " removed from ") + r.id() + ".");
    }

    private static int rank(CommandSourceStack src, String id, String rank, boolean add) throws CommandSyntaxException {
        return answer(src, RegionEdits.rank(RegionStore.get(src.getLevel()), region(src, id), rank, add));
    }

    // ------------------------------------------------------------------ claims

    private static int sale(CommandSourceStack src, String id, long price) throws CommandSyntaxException {
        return answer(src, RegionEdits.sale(src.getLevel(), RegionStore.get(src.getLevel()), region(src, id), price));
    }

    private static int open(CommandSourceStack src, String id, long cost, int min, int max) throws CommandSyntaxException {
        Region r = region(src, id);
        RegionStore store = RegionStore.get(src.getLevel());
        RegionEdits.Result opened = RegionEdits.open(src.getLevel(), store, r, cost);
        if (!opened.ok() || cost <= 0) {
            return answer(src, opened);
        }
        RegionEdits.Result sizes = RegionEdits.plotSizes(store, r, min, max);
        return ok(src, opened.message() + " " + sizes.message());
    }

    private static int plotFlags(CommandSourceStack src, String id, String flags) throws CommandSyntaxException {
        return answer(src, RegionEdits.plotFlags(RegionStore.get(src.getLevel()), region(src, id), flags));
    }

    // ------------------------------------------------------------------ triggers

    private static int triggerList(CommandSourceStack src, String id) throws CommandSyntaxException {
        Region r = region(src, id);
        var triggers = com.mrgregles.bsp_region_guard.trigger.Trigger.of(r.typeData());
        if (triggers.isEmpty()) {
            return ok(src, r.id() + " has no triggers. Add one: /rg trigger add " + r.id() + " <enter|exit|timer|shutdown> <destination>.");
        }
        MutableComponent out = Component.literal("Triggers on " + r.id() + ":");
        for (int i = 0; i < triggers.size(); i++) {
            out.append("\n  " + (i + 1) + ". " + triggers.get(i).describe());
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static int triggerRemove(CommandSourceStack src, String id, int index) throws CommandSyntaxException {
        return answer(src, RegionEdits.removeTrigger(RegionStore.get(src.getLevel()), region(src, id), index));
    }

    private static int triggerAdd(CommandSourceStack src, String id, String causeName, String destination) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        return answer(src, RegionEdits.addTrigger(p, RegionStore.get(src.getLevel()), region(src, id), causeName, destination));
    }

    // ------------------------------------------------------------------ mines

    private static Region mine(CommandSourceStack src, String id) throws CommandSyntaxException {
        Region r = region(src, id);
        if (r.type() != RegionType.MINE) {
            throw new SimpleCommandExceptionType(Component.literal(r.id() + " is not a mine. Use /rg mine create " + r.id() + " first.")).create();
        }
        return r;
    }

    private static int mineCreate(CommandSourceStack src, String id) throws CommandSyntaxException {
        return answer(src, RegionEdits.makeMine(RegionStore.get(src.getLevel()), region(src, id)));
    }

    private static int mineAdd(CommandSourceStack src, String id, net.minecraft.resources.ResourceLocation block, int weight) throws CommandSyntaxException {
        return answer(src, RegionEdits.mineBlock(RegionStore.get(src.getLevel()), region(src, id), block, weight));
    }

    private static int mineInterval(CommandSourceStack src, String id, int seconds) throws CommandSyntaxException {
        return answer(src, RegionEdits.mineInterval(RegionStore.get(src.getLevel()), region(src, id), seconds));
    }

    private static int mineSpawn(CommandSourceStack src, String id) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        return answer(src, RegionEdits.mineSpawn(p, RegionStore.get(src.getLevel()), region(src, id), false));
    }

    private static int mineShell(CommandSourceStack src, String id, int radius) throws CommandSyntaxException {
        return answer(src, RegionEdits.mineShell(src.getLevel(), RegionStore.get(src.getLevel()), region(src, id), radius));
    }

    private static int mineReset(CommandSourceStack src, String id) throws CommandSyntaxException {
        return answer(src, RegionEdits.mineReset(src.getLevel(), RegionStore.get(src.getLevel()), region(src, id)));
    }

    private static int mineInfo(CommandSourceStack src, String id) throws CommandSyntaxException {
        Region r = mine(src, id);
        var fill = com.mrgregles.bsp_region_guard.mine.MineData.fill(r);
        int total = com.mrgregles.bsp_region_guard.mine.MineData.totalWeight(fill);
        MutableComponent out = Component.literal(r.id() + ": " + r.bounds().volume() + " blocks");
        long left = com.mrgregles.bsp_region_guard.mine.Mines.secondsToReset(r);
        out.append(left < 0 ? ", no automatic reset" : ", resets every " + com.mrgregles.bsp_region_guard.mine.MineData.intervalSeconds(r) + " s, next in " + left + " s");
        out.append(fill.isEmpty() ? "\n  fill table empty" : "");
        for (var e : fill) {
            out.append("\n  " + e.block() + "  " + e.weight() + "  (" + (100 * e.weight() / Math.max(1, total)) + "%)");
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static int claimsList(CommandSourceStack src) {
        var claims = com.mrgregles.bsp_region_guard.claim.ClaimWipe.claimsIn(src.getLevel());
        if (claims.isEmpty()) {
            return ok(src, "No claims in " + src.getLevel().dimension().location() + ".");
        }
        MutableComponent out = Component.literal("Claims in " + src.getLevel().dimension().location() + " (" + claims.size() + "):");
        for (Region r : claims) {
            out.append("\n  " + r.id() + "  " + names(src, r.owners()) + "  " + paid(r));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    // ------------------------------------------------------------------ flags

    private static int flags(CommandSourceStack src) {
        StringBuilder sb = new StringBuilder("Flags: ");
        FlagRegistry.names().forEach(n -> sb.append(n).append(", "));
        sb.setLength(sb.length() - 2);
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int showFlag(CommandSourceStack src, String id, String flagName) throws CommandSyntaxException {
        Region r = region(src, id);
        Flag<?> flag = FlagRegistry.get(flagName);
        if (flag == null) {
            return fail(src, "No flag named '" + flagName + "'. See /rg flags.");
        }
        Object own = r.flag(flag);
        Object effective = RegionQuery.effective(RegionStore.get(src.getLevel()), r, flag);
        String text = r.id() + " " + flag.name() + ": " + (own == null ? "not set" : formatAny(flag, own))
                + (own == null && effective != null ? " (inherited: " + formatAny(flag, effective) + ")" : "")
                + (flag.defaultValue() != null ? " [default " + formatAny(flag, flag.defaultValue()) + "]" : "");
        src.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int setFlag(CommandSourceStack src, String id, String flagName, String value) throws CommandSyntaxException {
        Region r = region(src, id);
        Flag<?> flag = FlagRegistry.get(flagName);
        if (flag == null) {
            return fail(src, "No flag named '" + flagName + "'. See /rg flags.");
        }
        String v = value.trim();
        FlagContext context = new FlagContext(src.getLevel(), src.getEntity() instanceof ServerPlayer p ? p : null);
        return answer(src, RegionEdits.flag(RegionStore.get(src.getLevel()), r, flag, v.equalsIgnoreCase("clear") || v.equals("-") ? null : v, context));
    }



    private static <T> String formatAny(Flag<T> flag, Object value) {
        return RegionEdits.formatAny(flag, value);
    }

    // ------------------------------------------------------------------ reading

    private static int list(CommandSourceStack src) {
        RegionStore store = RegionStore.get(src.getLevel());
        if (store.size() == 0) {
            return ok(src, "No regions in this world yet. Select corners and use /rg define <id>.");
        }
        MutableComponent out = Component.literal("Regions in " + src.getLevel().dimension().location() + " (" + store.size() + "):");
        for (Region r : store.all()) {
            out.append(Component.literal("\n  " + r.id()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" " + (r.isGlobal() ? "world default" : (r.hasCorners() ? r.bounds() : "no corners yet") + ", priority " + r.priority())
                            + (r.type() == RegionType.PROTECTED || r.isGlobal() ? "" : ", " + r.type().key().replace('_', ' '))));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static int here(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        List<Region> regions = RegionStore.get(src.getLevel()).at(p.blockPosition());
        if (regions.isEmpty()) {
            return ok(src, "No region here.");
        }
        MutableComponent out = Component.literal("Regions here, highest priority first:");
        for (Region r : regions) {
            out.append("\n  ").append(Component.literal(r.id()).withStyle(ChatFormatting.AQUA))
                    .append(" (priority " + r.priority() + (r.flags().isEmpty() ? ", no flags)" : ", " + r.flags().size() + " flags)"));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static int info(CommandSourceStack src, String id) throws CommandSyntaxException {
        Region r = region(src, id);
        RegionStore store = RegionStore.get(src.getLevel());
        MutableComponent out = Component.literal("");
        out.append(Component.literal(r.id()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        if (!r.name().equals(r.id())) {
            out.append(" \"" + r.name() + "\"");
        }
        out.append("\n  Type: " + r.type().key().replace('_', ' ') + ", priority " + r.priority()
                + (r.parentId() == null ? "" : ", parent " + r.parentId()));
        out.append("\n  Bounds: " + (r.bounds() == null ? "the whole world" : !r.hasCorners() ? "no corners yet (protects nothing)" : r.bounds() + " (" + r.bounds().sizeX() + " x "
                + r.bounds().sizeY() + " x " + r.bounds().sizeZ() + ")"));
        out.append("\n  Owners: " + names(src, r.owners()));
        out.append("\n  Members: " + names(src, r.members()));
        out.append("\n  Ranks: " + (r.ranks().isEmpty() ? "any" : String.join(", ", r.ranks())));
        StringBuilder fl = new StringBuilder();
        for (Map.Entry<Flag<?>, Object> e : r.flags().entrySet()) {
            fl.append(fl.length() == 0 ? "" : ", ").append(e.getKey().name()).append("=").append(formatAny(e.getKey(), e.getValue()));
        }
        out.append("\n  Flags: " + (fl.length() == 0 ? "none" : fl));
        if (r.parentId() != null) {
            Region parent = store.parentOf(r);
            out.append("\n  Inherits: " + (parent == null ? "(parent missing)" : parent.flags().size() + " flags from " + parent.id()));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static String names(CommandSourceStack src, Collection<UUID> uuids) {
        if (uuids.isEmpty()) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        for (UUID u : uuids) {
            out.add(name(src, u));
        }
        return String.join(", ", out);
    }

    private static int teleport(CommandSourceStack src, String id) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        return answer(src, RegionEdits.teleport(p, RegionStore.get(src.getLevel()), region(src, id)));
    }
}
