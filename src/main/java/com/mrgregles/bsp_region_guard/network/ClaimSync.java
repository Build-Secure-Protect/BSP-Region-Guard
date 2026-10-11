package com.mrgregles.bsp_region_guard.network;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_region_guard.api.ClaimResult;
import com.mrgregles.bsp_region_guard.claim.ClaimFlags;
import com.mrgregles.bsp_region_guard.claim.Claims;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import com.mrgregles.bsp_region_guard.registry.ModItems;
import com.mrgregles.bsp_region_guard.selection.Selection;
import com.mrgregles.bsp_region_guard.selection.Selections;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The server side of the player's claim screen. Every action goes through {@link Claims}, which checks ownership. */
public final class ClaimSync {
    private ClaimSync() {
    }

    /** Opens the claim screen on the claim the player stands in, or their first. */
    public static void open(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!Claims.INSTANCE.claimsAllowedIn(level)) {
            player.displayClientMessage(Component.literal("Claims are not allowed in this world."), true);
            return;
        }
        RegionStore store = RegionStore.get(level);
        String select = null;
        for (Region r : store.at(player.blockPosition())) {
            if (r.type() == RegionType.CLAIM && r.isOwner(player.getUUID())) {
                select = r.id();
                break;
            }
        }
        List<String> mine = Claims.INSTANCE.claimsOf(level, player.getUUID());
        if (select == null && !mine.isEmpty()) {
            select = mine.get(0);
        }
        RegionSync.sendList(player, null, false);
        Region claim = select == null ? null : store.get(select);
        if (claim != null) {
            RegionSync.sendDetail(player, claim);
        }
        RegionNetwork.sendTo(player, new ClaimOpenPacket(select, claim == null ? List.of() : ClaimFlags.allowedFor(store, claim), Claims.INSTANCE.claimLimit(player)));
    }

    public static void handle(ServerPlayer player, ClaimActionPacket msg) {
        String outcome = apply(player, msg);
        if (outcome != null && !outcome.isEmpty()) {
            player.displayClientMessage(Component.literal(outcome), true);
        }
    }

    @Nullable
    public static String apply(ServerPlayer player, ClaimActionPacket msg) {
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region claim = msg.claimId().isEmpty() ? null : store.get(msg.claimId());
        switch (msg.action()) {
            case ClaimActionPacket.TOOL -> {
                ItemStack wand = new ItemStack(ModItems.REGION_WAND.get());
                if (!player.getInventory().add(wand)) {
                    player.drop(wand, false);
                }
                return "Plot tool given: left-click one corner, right-click the other.";
            }
            case ClaimActionPacket.SELECT -> {
                if (claim != null && (claim.isMember(player.getUUID()) || claim.type() == RegionType.PLOT_FOR_SALE || claim.type() == RegionType.OPEN_FOR_BUYING)) {
                    RegionSync.sendDetail(player, claim);
                    RegionNetwork.sendTo(player, new ClaimOpenPacket(claim.id(), ClaimFlags.allowedFor(store, claim), Claims.INSTANCE.claimLimit(player)));
                }
                return null;
            }
            default -> {
                if (claim == null || !Claims.mayManage(player, claim)) {
                    return "That is not a claim of yours.";
                }
            }
        }
        ClaimResult result;
        switch (msg.action()) {
            case ClaimActionPacket.FLAG -> result = Claims.INSTANCE.setPlayerFlag(player, claim.id(), msg.a(), msg.b());
            case ClaimActionPacket.ADD_MEMBER, ClaimActionPacket.REMOVE_MEMBER -> {
                UUID who = resolve(player, msg.a().trim());
                if (who == null) {
                    return "No player called '" + msg.a().trim() + "' is known to this server.";
                }
                if (who.equals(player.getUUID())) {
                    return "You are the owner already.";
                }
                boolean changed = msg.action().equals(ClaimActionPacket.ADD_MEMBER) ? claim.addMember(who) : claim.removeMember(who);
                store.touch(claim);
                result = ClaimResult.ok(changed ? "Done." : "Nothing changed.", claim.id(), 0);
            }
            case ClaimActionPacket.ABANDON -> result = Claims.INSTANCE.abandon(player, claim.id());
            case ClaimActionPacket.ENLARGE -> {
                Selection sel = Selections.of(player);
                Cuboid ground = sel.cuboid();
                if (ground == null || !level.dimension().equals(sel.dimension())) {
                    return "Outline the larger plot with the plot tool first.";
                }
                result = Claims.INSTANCE.enlarge(player, claim.id(), ground);
            }
            case ClaimActionPacket.TELEPORT -> {
                return RegionSync.teleport(player, level, store, claim);
            }
            default -> {
                return "Unknown action " + msg.action();
            }
        }
        Region after = store.get(claim.id());
        if (after != null) {
            RegionSync.sendDetail(player, after);
        }
        RegionSync.sendList(player, null, false);
        return result.message();
    }

    @Nullable
    private static UUID resolve(ServerPlayer viewer, String nameOrUuid) {
        try {
            return UUID.fromString(nameOrUuid);
        } catch (IllegalArgumentException ignored) {
            // a name
        }
        ServerPlayer online = viewer.server.getPlayerList().getPlayerByName(nameOrUuid);
        if (online != null) {
            return online.getUUID();
        }
        if (viewer.server.getProfileCache() != null) {
            Optional<GameProfile> cached = viewer.server.getProfileCache().get(nameOrUuid);
            if (cached.isPresent()) {
                return cached.get().getId();
            }
        }
        return null;
    }
}
