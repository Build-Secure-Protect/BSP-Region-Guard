package com.mrgregles.bsp_region_guard.api.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.Event;

/** Fired on the Forge bus when a mine starts refilling (after the players inside were moved out). Not cancellable. */
public class MineResetEvent extends Event {
    private final ServerLevel level;
    private final String mineId;
    private final long blocks;

    public MineResetEvent(ServerLevel level, String mineId, long blocks) {
        this.level = level;
        this.mineId = mineId;
        this.blocks = blocks;
    }

    public ServerLevel level() {
        return level;
    }

    public ResourceKey<Level> dimension() {
        return level.dimension();
    }

    public String mineId() {
        return mineId;
    }

    /** How many blocks the refill covers. */
    public long blocks() {
        return blocks;
    }
}
