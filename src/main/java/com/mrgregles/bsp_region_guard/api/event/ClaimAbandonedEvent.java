package com.mrgregles.bsp_region_guard.api.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Fired on the Forge bus after a player gave a claim up and the refund was paid. Not cancellable. */
public class ClaimAbandonedEvent extends PlayerEvent {
    private final String claimId;
    private final ResourceKey<Level> dimension;
    private final int refund;

    public ClaimAbandonedEvent(ServerPlayer player, String claimId, ResourceKey<Level> dimension, int refund) {
        super(player);
        this.claimId = claimId;
        this.dimension = dimension;
        this.refund = refund;
    }

    public String claimId() {
        return claimId;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public int refund() {
        return refund;
    }
}
