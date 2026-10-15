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

    /** Fired by Region Guard; other mods only listen. */
    public ClaimPurchasedEvent(ServerPlayer player, String claimId, ResourceKey<Level> dimension, long price, boolean enlarged, @org.jetbrains.annotations.Nullable com.mrgregles.bsp_region_guard.api.Cuboid bounds) {
        super(player);
        this.claimId = claimId;
        this.dimension = dimension;
        this.price = price;
        this.enlarged = enlarged;
        this.bounds = bounds;
    }

    /** The claim's region id. */
    public String claimId() {
        return claimId;
    }

    /** The world it happened in. */
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

    @org.jetbrains.annotations.Nullable
    private final com.mrgregles.bsp_region_guard.api.Cuboid bounds;

    /** The claim's box (for an enlargement, the new one; for an abandon, the one it had), for a shop map to redraw (CR-4). */
    @org.jetbrains.annotations.Nullable
    public com.mrgregles.bsp_region_guard.api.Cuboid bounds() {
        return bounds;
    }
}
