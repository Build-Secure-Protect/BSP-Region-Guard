package com.mrgregles.bsp_region_guard.selection;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Two corners picked by a player, in one world. Complete once both are set. */
public final class Selection {
    @Nullable
    private ResourceKey<Level> dimension;
    @Nullable
    private BlockPos pos1;
    @Nullable
    private BlockPos pos2;

    public void setPos1(ResourceKey<Level> dimension, BlockPos pos) {
        switchWorld(dimension);
        pos1 = pos.immutable();
    }

    public void setPos2(ResourceKey<Level> dimension, BlockPos pos) {
        switchWorld(dimension);
        pos2 = pos.immutable();
    }

    public void set(ResourceKey<Level> dimension, Cuboid box) {
        this.dimension = dimension;
        pos1 = box.min();
        pos2 = box.max();
    }

    public void clear() {
        dimension = null;
        pos1 = null;
        pos2 = null;
    }

    private void switchWorld(ResourceKey<Level> dimension) {
        if (!dimension.equals(this.dimension)) {
            pos1 = null;
            pos2 = null;
            this.dimension = dimension;
        }
    }

    @Nullable
    public ResourceKey<Level> dimension() {
        return dimension;
    }

    @Nullable
    public BlockPos pos1() {
        return pos1;
    }

    @Nullable
    public BlockPos pos2() {
        return pos2;
    }

    public boolean isComplete() {
        return dimension != null && pos1 != null && pos2 != null;
    }

    /** The selected box, or {@code null} until both corners are set. */
    @Nullable
    public Cuboid cuboid() {
        return isComplete() ? Cuboid.of(pos1, pos2) : null;
    }
}
