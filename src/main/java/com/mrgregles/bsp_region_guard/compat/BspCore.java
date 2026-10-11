package com.mrgregles.bsp_region_guard.compat;

import com.mrgregles.bsp_core.BSPConfig;
import com.mrgregles.bsp_core.admin.Admins;
import com.mrgregles.bsp_core.coin.CoinWallet;
import com.mrgregles.bsp_core.data.TotemLedger;
import com.mrgregles.bsp_core.totem.ShatterTotemBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The only class that touches BSP-Core.
 *
 * <p>BSP-Core has no stable API yet, so every call into it goes through here: a rename there breaks this file and
 * nothing else. Move these onto {@code com.mrgregles.bsp_core.api} once it exists (request RG-C1 in the vault).
 */
public final class BspCore {
    private BspCore() {
    }

    /** BSP-Core's admin level (config list; every operator when the list is empty). */
    public static boolean isAdmin(Player player) {
        return Admins.isAdmin(player);
    }

    public static boolean isModerator(Player player) {
        return Admins.isModerator(player);
    }

    /** Total coin value the player carries, across all coin tiers. */
    public static int coinValue(Player player) {
        return CoinWallet.totalValue(player);
    }

    /** Takes {@code cost} in coin value from the player. Returns false, taking nothing, when they cannot pay. */
    public static boolean charge(ServerPlayer player, int cost) {
        return CoinWallet.pay(player, cost);
    }

    /** Gives the player {@code value} in coins (refunds). */
    public static void refund(ServerPlayer player, int value) {
        CoinWallet.give(player, value);
    }

    // ------------------------------------------------------------------ totems

    /** A world where Shatter Totems may be placed: the raiding (PvP) worlds. */
    public static boolean isTotemWorld(ResourceKey<Level> dimension) {
        try {
            return BSPConfig.isDimensionAllowed(dimension.location());
        } catch (RuntimeException e) {
            return false; // config not loaded yet
        }
    }

    /** How close a thief must stand to steal, in blocks. */
    public static int stealRadius() {
        try {
            return BSPConfig.STEAL_RADIUS.get();
        } catch (RuntimeException e) {
            return 5;
        }
    }

    /** A placed totem: who owns it and where it is. */
    public record PlacedTotem(UUID owner, BlockPos pos) {
    }

    /** Placed totems in this world within {@code radius} blocks (box distance) of a position, from BSP Core's ledger. */
    public static List<PlacedTotem> placedTotemsNear(ServerLevel level, BlockPos pos, int radius) {
        List<PlacedTotem> out = new ArrayList<>();
        Map<UUID, Set<GlobalPos>> all = TotemLedger.get(level.getServer()).allPlaced();
        for (Map.Entry<UUID, Set<GlobalPos>> e : all.entrySet()) {
            for (GlobalPos gp : e.getValue()) {
                if (gp.dimension().equals(level.dimension())) {
                    BlockPos p = gp.pos();
                    if (Math.abs(p.getX() - pos.getX()) <= radius && Math.abs(p.getY() - pos.getY()) <= radius && Math.abs(p.getZ() - pos.getZ()) <= radius) {
                        out.add(new PlacedTotem(e.getKey(), p));
                    }
                }
            }
        }
        return out;
    }

    /** Whether a steal is running at a placed totem (the chunk must be loaded). */
    public static Optional<UUID> thiefAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ShatterTotemBlockEntity be ? be.getSteal().map(st -> st.thief()) : Optional.empty();
    }

    /** Tells BSP Core's ledger about a placed totem. For tests and, later, the cross-server mirror. */
    public static void recordPlacedTotem(ServerLevel level, UUID owner, BlockPos pos, int tier) {
        TotemLedger.get(level.getServer()).recordPlaced(owner, GlobalPos.of(level.dimension(), pos), tier);
    }

    public static void forgetPlacedTotem(ServerLevel level, BlockPos pos) {
        TotemLedger.get(level.getServer()).recordRemoved(GlobalPos.of(level.dimension(), pos));
    }
}
