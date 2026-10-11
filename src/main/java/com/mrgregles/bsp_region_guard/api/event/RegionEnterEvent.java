package com.mrgregles.bsp_region_guard.api.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.Cancelable;

/**
 * Fired on the Forge bus when a player is about to enter a region (one event per region entered). Cancel it to
 * refuse the entry: the player is put back where they came from, as if the region's {@code entry} flag denied them.
 */
@Cancelable
public class RegionEnterEvent extends PlayerEvent {
    private final String regionId;
    private final ResourceKey<Level> dimension;
    private final boolean teleport;

    public RegionEnterEvent(ServerPlayer player, String regionId, ResourceKey<Level> dimension, boolean teleport) {
        super(player);
        this.regionId = regionId;
        this.dimension = dimension;
        this.teleport = teleport;
    }

    public String regionId() {
        return regionId;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** True when the player arrived by teleport or dimension change rather than by walking. */
    public boolean byTeleport() {
        return teleport;
    }

    @Override
    public ServerPlayer getEntity() {
        return (ServerPlayer) super.getEntity();
    }
}
