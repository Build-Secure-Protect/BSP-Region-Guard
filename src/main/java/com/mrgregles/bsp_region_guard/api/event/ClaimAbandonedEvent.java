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

    /** Fired by Region Guard; other mods only listen. */
    public ClaimAbandonedEvent(ServerPlayer player, String claimId, ResourceKey<Level> dimension, long refund, @org.jetbrains.annotations.Nullable com.mrgregles.bsp_region_guard.api.Cuboid bounds) {
        super(player);
        this.claimId = claimId;
        this.dimension = dimension;
        this.refund = refund;
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

    /** What went back into the owner's bank, in Shatter Pieces (0 when nothing). */
    public long refund() {
        return refund;
    }

    @org.jetbrains.annotations.Nullable
    private final com.mrgregles.bsp_region_guard.api.Cuboid bounds;

    /** The claim's box (for an enlargement, the new one; for an abandon, the one it had), for a shop map to redraw (CR-4). */
    @org.jetbrains.annotations.Nullable
    public com.mrgregles.bsp_region_guard.api.Cuboid bounds() {
        return bounds;
    }
}
