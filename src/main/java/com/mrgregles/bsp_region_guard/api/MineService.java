package com.mrgregles.bsp_region_guard.api;

import net.minecraft.server.level.ServerLevel;

/**
 * Automated mines, for other mods (a shop that sells mine access, a scoreboard of resets). A mine is a region of
 * type {@code mine}; its fill table, interval and spawn are set by admins. {@code MineResetEvent} fires after each reset.
 */
public interface MineService {
    /** True when the region exists in this world and is a mine. */
    boolean isMine(ServerLevel level, String regionId);

    /** Resets the mine now (players inside are moved out first, the fill is written over a few ticks); returns the message a player would see. */
    String reset(ServerLevel level, String mineId);

    /** Seconds until the next automatic reset, or -1 when none is scheduled or the id is not a mine. */
    long secondsToReset(ServerLevel level, String mineId);
}
