package com.mrgregles.bsp_region_guard.api.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.Cancelable;

/**
 * Fired on the Forge bus when a player is about to leave a region (one event per region left). Cancel it to refuse
 * the exit: the player is put back, as if the region's {@code exit} flag denied them.
 */
@Cancelable
public class RegionLeaveEvent extends PlayerEvent {
    private final String regionId;
    private final ResourceKey<Level> dimension;
    private final boolean teleport;

    /** Fired by Region Guard; other mods only listen. */
    public RegionLeaveEvent(ServerPlayer player, String regionId, ResourceKey<Level> dimension, boolean teleport) {
        super(player);
        this.regionId = regionId;
        this.dimension = dimension;
        this.teleport = teleport;
    }

    /** The region's id. */
    public String regionId() {
        return regionId;
    }

    /** The world it happened in. */
    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** True when the player left by a teleport (a jump of more than 10 blocks in a tick), not by walking. */
    public boolean byTeleport() {
        return teleport;
    }

    @Override
    public ServerPlayer getEntity() {
        return (ServerPlayer) super.getEntity();
    }
}
