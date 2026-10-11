package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared helpers for the protection handlers: the checks, the "you can't do that here" message, admin bypass. */
public final class Protection {
    /** How often one player is told "no" at most, in milliseconds. */
    private static final long MESSAGE_GAP_MS = 1500;
    private static final Map<UUID, Long> LAST_MESSAGE = new HashMap<>();

    private Protection() {
    }

    @Nullable
    public static ServerLevel server(LevelAccessor level) {
        return level instanceof ServerLevel s ? s : null;
    }

    /** A player's own action: true when the flag allows it, or the player is bypassing. */
    public static boolean mayPlayer(ServerLevel level, BlockPos pos, StateFlag flag, Player player) {
        if (RegionQuery.bypasses(player)) {
            return true;
        }
        return RegionQuery.allows(level, pos, flag, player);
    }

    /** Something that is not a player (a mob, the world): true when the flag allows it. */
    public static boolean mayWorld(ServerLevel level, BlockPos pos, StateFlag flag) {
        return RegionQuery.allows(level, pos, flag, null);
    }

    /** Checks a player's action and, when refused, tells them. Returns true when the action may go ahead. */
    public static boolean checkPlayer(ServerLevel level, BlockPos pos, StateFlag flag, Player player) {
        if (mayPlayer(level, pos, flag, player)) {
            return true;
        }
        tell(player, level, pos);
        return false;
    }

    /** Shows the region's deny message on the action bar, at most once every 1.5 seconds per player. */
    public static void tell(Player player, ServerLevel level, BlockPos pos) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_MESSAGE.get(sp.getUUID());
        if (last != null && now - last < MESSAGE_GAP_MS) {
            return;
        }
        LAST_MESSAGE.put(sp.getUUID(), now);
        sp.displayClientMessage(Component.literal(RegionQuery.denyMessage(level, pos)), true);
    }

    public static void tell(Player player, ServerLevel level, BlockPos pos, String message) {
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.literal(message), true);
        }
    }

    public static void forget(UUID player) {
        LAST_MESSAGE.remove(player);
    }

    /** The player behind an entity: the entity itself, or the owner of a projectile or pet; else {@code null}. */
    @Nullable
    public static Player playerBehind(@Nullable Entity entity) {
        if (entity instanceof Player p) {
            return p;
        }
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile proj && proj.getOwner() instanceof Player p) {
            return p;
        }
        if (entity instanceof net.minecraft.world.entity.TamableAnimal pet && pet.getOwner() instanceof Player p) {
            return p;
        }
        return null;
    }

    public static boolean isServer(Level level) {
        return !level.isClientSide();
    }
}
