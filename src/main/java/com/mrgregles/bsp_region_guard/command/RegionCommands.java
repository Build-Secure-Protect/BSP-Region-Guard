package com.mrgregles.bsp_region_guard.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagContext;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.Cuboid;
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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
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
    private static final SimpleCommandExceptionType NOT_A_PLAYER = new SimpleCommandExceptionType(Component.literal("Only a player can do that."));
    private static final SimpleCommandExceptionType NO_SELECTION = new SimpleCommandExceptionType(Component.literal("Select two corners first (wand, or /rg pos1 and /rg pos2)."));

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
        rg.then(Commands.literal("define").then(id()
                .executes(ctx -> define(ctx.getSource(), id(ctx), 0))
                .then(Commands.argument("priority", IntegerArgumentType.integer())
                        .executes(ctx -> define(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "priority"))))));
        rg.then(Commands.literal("redefine").then(id().executes(ctx -> redefine(ctx.getSource(), id(ctx)))));
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
        rg.then(Commands.literal("sale").then(id().then(Commands.argument("price", IntegerArgumentType.integer(0))
                .executes(ctx -> sale(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "price"))))));
        rg.then(Commands.literal("open").then(id().then(Commands.argument("costPerBlock", IntegerArgumentType.integer(0))
                .executes(ctx -> open(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "costPerBlock"), 0, 0))
                .then(Commands.argument("minSize", IntegerArgumentType.integer(1)).then(Commands.argument("maxSize", IntegerArgumentType.integer(1))
                        .executes(ctx -> open(ctx.getSource(), id(ctx), IntegerArgumentType.getInteger(ctx, "costPerBlock"),
                                IntegerArgumentType.getInteger(ctx, "minSize"), IntegerArgumentType.getInteger(ctx, "maxSize"))))))));
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
        rg.then(Commands.literal("claims")
                .then(Commands.literal("list").executes(ctx -> claimsList(ctx.getSource())))
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

    private static int ok(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), true);
        return 1;
    }

    private static int fail(CommandSourceStack src, String text) {
        src.sendFailure(Component.literal(text));
        return 0;
    }

    private static String name(CommandSourceStack src, UUID uuid) {
        Optional<GameProfile> profile = src.getServer().getProfileCache() == null ? Optional.empty() : src.getServer().getProfileCache().get(uuid);
        return profile.map(GameProfile::getName).orElse(uuid.toString().substring(0, 8));
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
        ServerPlayer p = player(src);
        Region r = region(src, id);
        if (r.bounds() == null) {
            return fail(src, "The global region has no corners to select.");
        }
        Selections.of(p).set(p.serverLevel().dimension(), r.bounds());
        Selections.sync(p);
        return ok(src, "Selection set to " + r.id() + ": " + r.bounds() + ".");
    }

    // ------------------------------------------------------------------ create, change, remove

    private static int define(CommandSourceStack src, String id, int priority) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        Cuboid box = Selections.of(p).cuboid();
        if (box == null || !p.serverLevel().dimension().equals(Selections.of(p).dimension())) {
            throw NO_SELECTION.create();
        }
        RegionStore store = RegionStore.get(src.getLevel());
        try {
            Region r = new Region(Region.validateId(id), src.getLevel().dimension(), box, RegionType.PROTECTED);
            r.setPriority(priority);
            store.add(r);
            return ok(src, "Region " + r.id() + " created: " + box + ", " + box.volume() + " blocks, priority " + priority + ".");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return fail(src, e.getMessage());
        }
    }

    private static int redefine(CommandSourceStack src, String id) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        Region r = region(src, id);
        if (r.isGlobal()) {
            return fail(src, "The global region has no bounds.");
        }
        Cuboid box = Selections.of(p).cuboid();
        if (box == null || !p.serverLevel().dimension().equals(Selections.of(p).dimension())) {
            throw NO_SELECTION.create();
        }
        r.setBounds(box);
        RegionStore.get(src.getLevel()).touch(r);
        return ok(src, "Region " + r.id() + " now covers " + box + ".");
    }

    private static int remove(CommandSourceStack src, String id) throws CommandSyntaxException {
        Region r = region(src, id);
        RegionStore.get(src.getLevel()).remove(r.id());
        return ok(src, "Region " + r.id() + " removed.");
    }

    private static int priority(CommandSourceStack src, String id, int priority) throws CommandSyntaxException {
        Region r = region(src, id);
        if (r.isGlobal()) {
            return fail(src, "The global region always has the lowest priority.");
        }
        r.setPriority(priority);
        RegionStore.get(src.getLevel()).touch(r);
        return ok(src, "Region " + r.id() + " priority is now " + priority + ".");
    }

    private static int parent(CommandSourceStack src, String id, String parentId) throws CommandSyntaxException {
        Region r = region(src, id);
        RegionStore store = RegionStore.get(src.getLevel());
        if (parentId.equalsIgnoreCase("none") || parentId.equals("-")) {
            r.setParentId(null);
            store.touch(r);
            return ok(src, "Region " + r.id() + " has no parent now.");
        }
        Region parent = region(src, parentId);
        if (parent == r || store.isAncestor(r, parent)) {
            return fail(src, "That would make a loop of parents.");
        }
        r.setParentId(parent.id());
        store.touch(r);
        return ok(src, "Region " + r.id() + " now inherits from " + parent.id() + ".");
    }

    private static int type(CommandSourceStack src, String id, String typeKey) throws CommandSyntaxException {
        Region r = region(src, id);
        RegionType type = RegionType.byKey(typeKey.toLowerCase());
        if (type == null || type == RegionType.GLOBAL || r.isGlobal()) {
            return fail(src, "Types: protected, plot_for_sale, open_for_buying, claim, mine.");
        }
        if ((type == RegionType.CLAIM || type == RegionType.PLOT_FOR_SALE || type == RegionType.OPEN_FOR_BUYING)
                && !com.mrgregles.bsp_region_guard.protection.TotemRule.claimsAllowedIn(src.getLevel())) {
            return fail(src, "Claims are not allowed in a PvP world (where Shatter Totems can be placed). See the claims section of the config.");
        }
        r.setType(type);
        RegionStore.get(src.getLevel()).touch(r);
        return ok(src, "Region " + r.id() + " is now a " + type.key().replace('_', ' ') + " region.");
    }

    private static int rename(CommandSourceStack src, String id, String name) throws CommandSyntaxException {
        Region r = region(src, id);
        r.setName(name.trim());
        RegionStore.get(src.getLevel()).touch(r);
        return ok(src, "Region " + r.id() + " is now called \"" + r.name() + "\".");
    }

    private static int people(CommandSourceStack src, String id, Collection<GameProfile> profiles, boolean owners, boolean add) throws CommandSyntaxException {
        Region r = region(src, id);
        int changed = 0;
        for (GameProfile gp : profiles) {
            boolean did = owners ? (add ? r.addOwner(gp.getId()) : r.removeOwner(gp.getId()))
                    : (add ? r.addMember(gp.getId()) : r.removeMember(gp.getId()));
            if (did) {
                changed++;
            }
        }
        RegionStore.get(src.getLevel()).touch(r);
        String who = owners ? "owner" : "member";
        return ok(src, changed + " " + who + (changed == 1 ? "" : "s") + (add ? " added to " : " removed from ") + r.id() + ".");
    }

    private static int rank(CommandSourceStack src, String id, String rank, boolean add) throws CommandSyntaxException {
        Region r = region(src, id);
        boolean did = add ? r.addRank(rank) : r.removeRank(rank);
        RegionStore.get(src.getLevel()).touch(r);
        return ok(src, did ? "Rank " + rank.toLowerCase() + (add ? " may now enter " : " removed from ") + r.id() + "."
                : "Nothing changed.");
    }

    // ------------------------------------------------------------------ claims

    private static int sale(CommandSourceStack src, String id, int price) throws CommandSyntaxException {
        Region r = region(src, id);
        if (r.isGlobal()) {
            return fail(src, "The world default cannot be sold.");
        }
        if (!com.mrgregles.bsp_region_guard.protection.TotemRule.claimsAllowedIn(src.getLevel())) {
            return fail(src, "Claims are not allowed in a PvP world.");
        }
        if (r.type() == RegionType.CLAIM) {
            return fail(src, r.id() + " is already owned; the owner can abandon it, or an admin can with /claim abandon.");
        }
        r.setType(RegionType.PLOT_FOR_SALE);
        com.mrgregles.bsp_region_guard.claim.ClaimData.set(r, com.mrgregles.bsp_region_guard.claim.ClaimData.PRICE, price);
        RegionStore.get(src.getLevel()).touch(r);
        return ok(src, r.id() + " is for sale at " + price + " coins.");
    }

    private static int open(CommandSourceStack src, String id, int cost, int min, int max) throws CommandSyntaxException {
        Region r = region(src, id);
        if (r.isGlobal()) {
            return fail(src, "The world default cannot be an open area.");
        }
        if (!com.mrgregles.bsp_region_guard.protection.TotemRule.claimsAllowedIn(src.getLevel())) {
            return fail(src, "Claims are not allowed in a PvP world.");
        }
        r.setType(RegionType.OPEN_FOR_BUYING);
        com.mrgregles.bsp_region_guard.claim.ClaimData.set(r, com.mrgregles.bsp_region_guard.claim.ClaimData.COST_PER_BLOCK, cost);
        com.mrgregles.bsp_region_guard.claim.ClaimData.set(r, com.mrgregles.bsp_region_guard.claim.ClaimData.MIN_SIZE, min);
        com.mrgregles.bsp_region_guard.claim.ClaimData.set(r, com.mrgregles.bsp_region_guard.claim.ClaimData.MAX_SIZE, max);
        RegionStore.get(src.getLevel()).touch(r);
        return ok(src, r.id() + " is open for buying at " + cost + " coins per block" + (min > 0 || max > 0 ? ", plots " + min + " to " + max + " blocks a side." : "."));
    }

    private static int plotFlags(CommandSourceStack src, String id, String flags) throws CommandSyntaxException {
        Region r = region(src, id);
        var unknown = com.mrgregles.bsp_region_guard.claim.ClaimFlags.setAllowed(r, flags);
        RegionStore.get(src.getLevel()).touch(r);
        String now = String.join(", ", com.mrgregles.bsp_region_guard.claim.ClaimFlags.allowedFor(RegionStore.get(src.getLevel()), r));
        return ok(src, "Buyers of " + r.id() + " may set: " + now + (unknown.isEmpty() ? "" : " (not flags, ignored: " + String.join(", ", unknown) + ")"));
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
        Region r = region(src, id);
        if (r.isGlobal()) {
            return fail(src, "The world default cannot be a mine.");
        }
        com.mrgregles.bsp_region_guard.mine.Mines.makeMine(RegionStore.get(src.getLevel()), r);
        return ok(src, r.id() + " is now a mine: breaking is allowed inside, placing is not. Add blocks with /rg mine add " + r.id() + " <block> <weight>.");
    }

    private static int mineAdd(CommandSourceStack src, String id, net.minecraft.resources.ResourceLocation block, int weight) throws CommandSyntaxException {
        Region r = mine(src, id);
        if (weight > 0 && !net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(block)) {
            return fail(src, "No block called " + block + ".");
        }
        com.mrgregles.bsp_region_guard.mine.MineData.put(r, block, weight);
        RegionStore.get(src.getLevel()).touch(r);
        return ok(src, weight > 0 ? block + " added to " + r.id() + " with weight " + weight + "." : block + " removed from " + r.id() + ".");
    }

    private static int mineInterval(CommandSourceStack src, String id, int seconds) throws CommandSyntaxException {
        Region r = mine(src, id);
        com.mrgregles.bsp_region_guard.mine.Mines.schedule(RegionStore.get(src.getLevel()), r, seconds);
        return ok(src, seconds > 0 ? r.id() + " resets every " + seconds + " seconds; the first reset is in " + seconds + " seconds." : r.id() + " no longer resets on its own.");
    }

    private static int mineSpawn(CommandSourceStack src, String id) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        Region r = mine(src, id);
        com.mrgregles.bsp_region_guard.mine.MineData.setSpawn(r, FlagLocation.of(p.serverLevel(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
        RegionStore.get(src.getLevel()).touch(r);
        return ok(src, "Players in " + r.id() + " are moved here when it resets.");
    }

    private static int mineShell(CommandSourceStack src, String id, int radius) throws CommandSyntaxException {
        Region r = mine(src, id);
        Region shell = com.mrgregles.bsp_region_guard.mine.Mines.makeShell(src.getLevel(), RegionStore.get(src.getLevel()), r, radius);
        return ok(src, shell.id() + " surrounds " + r.id() + " by " + radius + " blocks: only the mine can be dug there, nothing can be placed.");
    }

    private static int mineReset(CommandSourceStack src, String id) throws CommandSyntaxException {
        Region r = mine(src, id);
        return ok(src, com.mrgregles.bsp_region_guard.mine.Mines.reset(src.getLevel(), RegionStore.get(src.getLevel()), r));
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
            out.append("\n  " + r.id() + "  " + names(src, r.owners()) + "  " + com.mrgregles.bsp_region_guard.claim.ClaimData.boughtFor(r) + " coins");
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
        RegionStore store = RegionStore.get(src.getLevel());
        if (value.trim().equalsIgnoreCase("clear") || value.trim().equals("-")) {
            r.setFlag(flag, null);
            store.touch(r);
            return ok(src, r.id() + " " + flag.name() + " cleared.");
        }
        try {
            FlagContext context = new FlagContext(src.getLevel(), src.getEntity() instanceof ServerPlayer p ? p : null);
            setParsed(r, flag, value.trim(), context);
            store.touch(r);
            return ok(src, r.id() + " " + flag.name() + " set to " + formatAny(flag, r.flag(flag)) + ".");
        } catch (IllegalArgumentException e) {
            return fail(src, flag.name() + ": " + e.getMessage());
        }
    }

    private static <T> void setParsed(Region r, Flag<T> flag, String value, FlagContext context) {
        r.setFlag(flag, flag.parse(value, context));
    }

    @SuppressWarnings("unchecked")
    private static <T> String formatAny(Flag<T> flag, Object value) {
        return flag.format((T) value);
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
                    .append(Component.literal(" " + (r.isGlobal() ? "world default" : r.bounds() + ", priority " + r.priority())
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
        out.append("\n  Bounds: " + (r.bounds() == null ? "the whole world" : r.bounds() + " (" + r.bounds().sizeX() + " x "
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
        Region r = region(src, id);
        FlagLocation target = r.flag(Flags.TELEPORT);
        ServerLevel level = src.getLevel();
        if (target != null) {
            ServerLevel dest = src.getServer().getLevel(target.dimension());
            if (dest == null) {
                return fail(src, "The teleport point is in a world that does not exist: " + target.dimension().location());
            }
            p.teleportTo(dest, target.x(), target.y(), target.z(), target.yaw(), target.pitch());
        } else if (r.bounds() != null) {
            Cuboid b = r.bounds();
            double x = (b.minX() + b.maxX() + 1) / 2.0, z = (b.minZ() + b.maxZ() + 1) / 2.0;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            y = Math.max(b.minY(), Math.min(b.maxY() + 1, y));
            p.teleportTo(level, x, y, z, p.getYRot(), p.getXRot());
        } else {
            return fail(src, "The global region has nowhere to teleport to; set its teleport flag.");
        }
        String message = RegionQuery.effective(RegionStore.get(level), r, Flags.TELEPORT_MESSAGE);
        return ok(src, message != null ? message : "Teleported to " + r.id() + ".");
    }
}
