package com.mrgregles.bsp_region_guard.api.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Fired on the Forge bus after a player gave a claim up; the refund (in Shatter Pieces) has been sent to the bank. Not cancellable. */
public class ClaimAbandonedEvent extends PlayerEvent {
    private final String claimId;
    private final ResourceKey<Level> dimension;
    private final long refund;

    public ClaimAbandonedEvent(ServerPlayer player, String claimId, ResourceKey<Level> dimension, long refund) {
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

    public long refund() {
        return refund;
    }
}
