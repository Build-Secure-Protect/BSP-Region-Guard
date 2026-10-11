package com.mrgregles.bsp_region_guard.api;

import net.minecraft.server.level.ServerLevel;

/** Mines, for other mods (a shop that sells mine access, a scoreboard of resets). */
public interface MineService {
    boolean isMine(ServerLevel level, String regionId);

    /** Resets the mine now; returns the message a player would see. */
    String reset(ServerLevel level, String mineId);

    /** Seconds until the next automatic reset, or -1 when none is scheduled or the id is not a mine. */
    long secondsToReset(ServerLevel level, String mineId);
}
