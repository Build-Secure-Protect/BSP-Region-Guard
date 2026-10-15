package com.mrgregles.bsp_region_guard.api;

import com.mrgregles.bsp_region_guard.flag.StateFlag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * Lifts a region's rule for a player in a spot, the way the Shatter Totem rule lets raiders near another player's
 * totem. Register one with {@link RegionService#addExemption(Exemption)}.
 *
 * <p>Asked only when a region would refuse a player (never for the world's own events such as fire or fluids), on
 * the server thread, possibly every tick for a moving player: keep it quick.
 */
@FunctionalInterface
public interface Exemption {
    /**
     * @param level  the world
     * @param pos    the block concerned (where the player acts or stands)
     * @param flag   the rule that would refuse, for example {@code Flags.ENTRY} or {@code Flags.BLOCK_BREAK}
     * @param player the player
     * @return true to let the player through this time
     */
    boolean exempts(ServerLevel level, BlockPos pos, StateFlag flag, Player player);
}
