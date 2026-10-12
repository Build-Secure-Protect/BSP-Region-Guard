package com.mrgregles.bsp_region_guard.api.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Fired on the Forge bus after a player bought or enlarged a claim and their bank was charged. Not cancellable. */
public class ClaimPurchasedEvent extends PlayerEvent {
    private final String claimId;
    private final ResourceKey<Level> dimension;
    private final long price;
    private final boolean enlarged;

    public ClaimPurchasedEvent(ServerPlayer player, String claimId, ResourceKey<Level> dimension, long price, boolean enlarged) {
        super(player);
        this.claimId = claimId;
        this.dimension = dimension;
        this.price = price;
        this.enlarged = enlarged;
    }

    public String claimId() {
        return claimId;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** What was charged, in Shatter Pieces. */
    public long price() {
        return price;
    }

    /** True when an existing claim grew rather than a new one being bought. */
    public boolean enlarged() {
        return enlarged;
    }
}
