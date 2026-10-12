package com.mrgregles.bsp_region_guard.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mrgregles.bsp_region_guard.api.ClaimResult;
import com.mrgregles.bsp_region_guard.api.ClaimService;
import com.mrgregles.bsp_region_guard.claim.ClaimData;
import com.mrgregles.bsp_region_guard.claim.Claims;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /claim ...}: what any player can do with plots, until BSP Core's shop screen takes over the buying part
 * (request RG-C3). Every rule lives in {@link Claims}; this is only the text interface.
 */
public final class ClaimCommands {
    private static final SimpleCommandExceptionType NOT_A_PLAYER = new SimpleCommandExceptionType(Component.literal("Only a player can do that."));
    private static final SuggestionProvider<CommandSourceStack> FOR_SALE = (ctx, b) -> {
        List<String> ids = new ArrayList<>();
        for (Region r : RegionStore.get(ctx.getSource().getLevel()).all()) {
            if (r.type() == RegionType.PLOT_FOR_SALE || r.type() == RegionType.OPEN_FOR_BUYING) {
                ids.add(r.id());
            }
        }
        return SharedSuggestionProvider.suggest(ids, b);
    };
    private static final SuggestionProvider<CommandSourceStack> MINE = (ctx, b) -> {
        if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
            return SharedSuggestionProvider.suggest(Claims.INSTANCE.claimsOf(p.serverLevel(), p.getUUID()), b);
        }
        return SharedSuggestionProvider.suggest(List.of(), b);
    };

    private ClaimCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> claim = Commands.literal("claim");
        claim.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));
        claim.then(Commands.literal("tool").executes(ctx -> tool(ctx.getSource())));
        claim.then(Commands.literal("quote").then(Commands.argument("id", StringArgumentType.word()).suggests(FOR_SALE)
                .executes(ctx -> buy(ctx.getSource(), id(ctx), false))));
        claim.then(Commands.literal("buy").then(Commands.argument("id", StringArgumentType.word()).suggests(FOR_SALE)
                .executes(ctx -> buy(ctx.getSource(), id(ctx), true))));
        claim.then(Commands.literal("enlarge").then(Commands.argument("id", StringArgumentType.word()).suggests(MINE)
                .executes(ctx -> enlarge(ctx.getSource(), id(ctx)))));
        claim.then(Commands.literal("abandon").then(Commands.argument("id", StringArgumentType.word()).suggests(MINE)
                .executes(ctx -> abandon(ctx.getSource(), id(ctx)))));
        claim.then(Commands.literal("add").then(Commands.argument("id", StringArgumentType.word()).suggests(MINE)
                .then(Commands.argument("players", GameProfileArgument.gameProfile()).executes(ctx -> member(ctx, true)))));
        claim.then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).suggests(MINE)
                .then(Commands.argument("players", GameProfileArgument.gameProfile()).executes(ctx -> member(ctx, false)))));
        claim.then(Commands.literal("flag").then(Commands.argument("id", StringArgumentType.word()).suggests(MINE)
                .then(Commands.argument("flag", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                                FlagRegistry.names().stream().filter(Claims::playerMaySet).toList(), b))
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> flag(ctx.getSource(), id(ctx), StringArgumentType.getString(ctx, "flag"), StringArgumentType.getString(ctx, "value")))))));
        claim.then(Commands.literal("here").executes(ctx -> here(ctx.getSource())));
        claim.then(Commands.literal("gui").executes(ctx -> {
            com.mrgregles.bsp_region_guard.network.ClaimSync.open(player(ctx.getSource()));
            return 1;
        }));
        dispatcher.register(claim);
    }

    private static String id(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "id");
    }

    private static ServerPlayer player(CommandSourceStack src) throws CommandSyntaxException {
        if (src.getEntity() instanceof ServerPlayer p) {
            return p;
        }
        throw NOT_A_PLAYER.create();
    }

    private static int result(CommandSourceStack src, ClaimResult r) {
        if (r.ok()) {
            src.sendSuccess(() -> Component.literal(r.message()), false);
            return 1;
        }
        src.sendFailure(Component.literal(r.message()));
        return 0;
    }

    private static int list(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        ClaimService claims = Claims.INSTANCE;
        MutableComponent out = Component.literal("");
        List<ClaimService.Listing> plots = claims.plotsForSale(p.serverLevel());
        List<ClaimService.Listing> open = claims.openAreas(p.serverLevel());
        List<String> mine = claims.claimsOf(p.serverLevel(), p.getUUID());
        if (!claims.claimsAllowedIn(p.serverLevel())) {
            out.append("Claims are not allowed in this world.");
        } else {
            out.append(Component.literal("Plots for sale: ").withStyle(ChatFormatting.GOLD)).append(plots.isEmpty() ? "none" : "");
            for (ClaimService.Listing l : plots) {
                out.append("\n  " + l.id() + "  " + l.bounds().sizeX() + " x " + l.bounds().sizeZ() + "  " + BspCore.money(l.price()));
            }
            out.append(Component.literal("\nOpen areas (outline your own plot with /claim tool): ").withStyle(ChatFormatting.GOLD)).append(open.isEmpty() ? "none" : "");
            for (ClaimService.Listing l : open) {
                out.append("\n  " + l.id() + "  " + BspCore.money(l.costPerBlock()) + " per block");
            }
        }
        out.append(Component.literal("\nYour claims here: ").withStyle(ChatFormatting.LIGHT_PURPLE)).append(mine.isEmpty() ? "none" : String.join(", ", mine))
                .append(" (" + mine.size() + " of " + claims.claimLimit(p) + ")");
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static int tool(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        if (!Claims.INSTANCE.claimsAllowedIn(p.serverLevel())) {
            src.sendFailure(Component.literal("Claims are not allowed in this world."));
            return 0;
        }
        ItemStack wand = new ItemStack(ModItems.REGION_WAND.get());
        if (!p.getInventory().add(wand)) {
            p.drop(wand, false);
        }
        src.sendSuccess(() -> Component.literal("Plot tool given. Left-click one corner, right-click the other, then /claim buy <open area>."), false);
        return 1;
    }

    private static int buy(CommandSourceStack src, String id, boolean buy) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        Region r = RegionStore.get(p.serverLevel()).get(id);
        ClaimService claims = Claims.INSTANCE;
        if (r != null && r.type() == RegionType.OPEN_FOR_BUYING) {
            Selection sel = Selections.of(p);
            Cuboid ground = sel.cuboid();
            if (ground == null || !p.serverLevel().dimension().equals(sel.dimension())) {
                src.sendFailure(Component.literal("Outline your plot first: /claim tool, then left-click and right-click the corners."));
                return 0;
            }
            if (!buy) {
                return result(src, claims.quoteOpen(p, id, ground));
            }
            claims.purchaseOpen(p, id, ground, res -> result(src, res));
            return 1;
        }
        if (!buy) {
            return result(src, claims.quotePlot(p, id));
        }
        claims.purchasePlot(p, id, res -> result(src, res)); // the bank answers a moment later on a network
        return 1;
    }

    private static int enlarge(CommandSourceStack src, String id) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        Selection sel = Selections.of(p);
        Cuboid ground = sel.cuboid();
        if (ground == null || !p.serverLevel().dimension().equals(sel.dimension())) {
            src.sendFailure(Component.literal("Outline the new, larger plot first with the plot tool."));
            return 0;
        }
        Claims.INSTANCE.enlarge(p, id, ground, r -> result(src, r));
        return 1;
    }

    private static int abandon(CommandSourceStack src, String id) throws CommandSyntaxException {
        return result(src, Claims.INSTANCE.abandon(player(src), id));
    }

    private static int member(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        ServerPlayer p = player(ctx.getSource());
        RegionStore store = RegionStore.get(p.serverLevel());
        Region r = store.get(id(ctx));
        if (r == null || !Claims.mayManage(p, r)) {
            ctx.getSource().sendFailure(Component.literal("That is not a claim of yours."));
            return 0;
        }
        int n = 0;
        for (var gp : GameProfileArgument.getGameProfiles(ctx, "players")) {
            if (gp.getId().equals(p.getUUID())) {
                continue;
            }
            if (add ? r.addMember(gp.getId()) : r.removeMember(gp.getId())) {
                n++;
            }
        }
        store.touch(r);
        int count = n;
        ctx.getSource().sendSuccess(() -> Component.literal(count + (add ? " added to " : " removed from ") + r.id() + "."), false);
        return 1;
    }

    private static int flag(CommandSourceStack src, String id, String flagName, String value) throws CommandSyntaxException {
        return result(src, Claims.INSTANCE.setPlayerFlag(player(src), id, flagName, value.trim().equalsIgnoreCase("clear") ? "" : value));
    }

    private static int here(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = player(src);
        for (Region r : RegionStore.get(p.serverLevel()).at(p.blockPosition())) {
            if (r.type() == RegionType.CLAIM) {
                src.sendSuccess(() -> Component.literal("You are in " + r.id() + (r.isOwner(p.getUUID()) ? " (yours)" : r.isMember(p.getUUID()) ? " (you are a member)" : "")), false);
                return 1;
            }
            if (r.type() == RegionType.PLOT_FOR_SALE) {
                src.sendSuccess(() -> Component.literal(r.id() + " is for sale: " + BspCore.money(ClaimData.price(r)) + ". /claim buy " + r.id()), false);
                return 1;
            }
            if (r.type() == RegionType.OPEN_FOR_BUYING) {
                src.sendSuccess(() -> Component.literal("Open area " + r.id() + ": " + BspCore.money(ClaimData.costPerBlock(r)) + " per block. /claim tool to outline a plot."), false);
                return 1;
            }
        }
        src.sendSuccess(() -> Component.literal("No claim here."), false);
        return 1;
    }
}
