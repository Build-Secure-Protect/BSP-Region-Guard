package com.mrgregles.bsp_region_guard.region;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All regions of one world, saved with that world ({@code data/bsp_region_guard_regions.dat}) and indexed for fast
 * lookup. This is the local truth; the cross-server mirror (Phase 7) reads and writes through it.
 */
public final class RegionStore extends SavedData {
    public static final String NAME = BSPRegionGuard.MODID + "_regions";

    private final ResourceKey<Level> dimension;
    private final Map<String, Region> regions = new LinkedHashMap<>();
    private final RegionIndex index = new RegionIndex();
    @Nullable
    private Region global;

    @Nullable
    private static java.util.function.Consumer<RegionStore> changeListener;

    private RegionStore(ResourceKey<Level> dimension) {
        this.dimension = dimension;
    }

    /** Told after every add, remove or touch, so clients can be kept up to date. */
    public static void setChangeListener(@Nullable java.util.function.Consumer<RegionStore> listener) {
        changeListener = listener;
    }

    private void changed() {
        setDirty();
        if (changeListener != null) {
            changeListener.accept(this);
        }
    }

    public static RegionStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                tag -> load(tag, level.dimension()),
                () -> new RegionStore(level.dimension()),
                NAME);
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    // ------------------------------------------------------------------ contents

    @Nullable
    public Region get(String id) {
        return regions.get(id.toLowerCase());
    }

    public boolean has(String id) {
        return regions.containsKey(id.toLowerCase());
    }

    /** Every region of the world, including the global one once it exists. */
    public Collection<Region> all() {
        return Collections.unmodifiableCollection(regions.values());
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(regions.keySet());
    }

    public int size() {
        return regions.size();
    }

    /** The world's global region, created on first use. */
    public Region global() {
        if (global == null) {
            global = Region.global(dimension);
            regions.put(global.id(), global);
            setDirty();
        }
        return global;
    }

    public boolean hasGlobal() {
        return global != null;
    }

    /** True when some region other than the global one exists. */
    public boolean hasBoundedRegions() {
        return regions.size() > (global == null ? 0 : 1);
    }

    /** Adds a new region. Throws when the id is taken or the region is for another world. */
    public void add(Region region) {
        if (!region.dimension().equals(dimension)) {
            throw new IllegalArgumentException(region.id() + " belongs to " + region.dimension().location() + ", not " + dimension.location());
        }
        if (regions.containsKey(region.id())) {
            throw new IllegalArgumentException("a region named '" + region.id() + "' already exists");
        }
        regions.put(region.id(), region);
        if (region.isGlobal()) {
            global = region;
        } else {
            index.add(region);
        }
        region.bumpVersion();
        changed();
    }

    /** Removes a region. Children of it lose their parent. Returns false when there was no such region. */
    public boolean remove(String id) {
        Region removed = regions.remove(id.toLowerCase());
        if (removed == null) {
            return false;
        }
        if (removed == global) {
            global = null;
        } else {
            index.remove(removed);
        }
        for (Region r : regions.values()) {
            if (removed.id().equals(r.parentId())) {
                r.setParentId(null);
                r.bumpVersion();
            }
        }
        changed();
        return true;
    }

    /** Call after changing a region in any way: re-indexes it (bounds may have moved) and marks the world data dirty. */
    public void touch(Region region) {
        if (regions.get(region.id()) != region) {
            throw new IllegalArgumentException(region.id() + " is not in this store");
        }
        if (!region.isGlobal()) {
            index.remove(region);
            index.add(region);
        }
        region.bumpVersion();
        changed();
    }

    /** Rebuilds the index from scratch (after a bulk load). */
    private void reindex() {
        index.clear();
        for (Region r : regions.values()) {
            if (!r.isGlobal()) {
                index.add(r);
            }
        }
    }

    // ------------------------------------------------------------------ lookups

    /** The cheap pre-check for mixins: true when no bounded region covers this column. */
    public boolean nothingAt(int x, int z) {
        return index.isEmptyAt(x, z);
    }

    /**
     * Regions containing the position, highest priority first, the global region last if it exists. Parents of a
     * region in the list are left out: a child speaks for its parent where it applies.
     */
    public List<Region> at(int x, int y, int z) {
        List<Region> found = index.at(x, y, z);
        if (found.isEmpty() && global == null) {
            return List.of();
        }
        List<Region> out = new ArrayList<>(found.size() + 1);
        for (Region r : found) {
            if (!hasDescendantIn(r, found)) {
                out.add(r);
            }
        }
        out.sort((a, b) -> b.priority() != a.priority() ? Integer.compare(b.priority(), a.priority()) : a.id().compareTo(b.id()));
        if (global != null) {
            out.add(global);
        }
        return out;
    }

    public List<Region> at(BlockPos pos) {
        return at(pos.getX(), pos.getY(), pos.getZ());
    }

    private boolean hasDescendantIn(Region ancestor, List<Region> candidates) {
        for (Region c : candidates) {
            if (c != ancestor && isAncestor(ancestor, c)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code ancestor} is in {@code region}'s parent chain (loops are cut off after 32 steps). */
    public boolean isAncestor(Region ancestor, Region region) {
        Region r = region;
        for (int i = 0; i < 32 && r.parentId() != null; i++) {
            r = regions.get(r.parentId());
            if (r == null) {
                return false;
            }
            if (r == ancestor) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public Region parentOf(Region region) {
        return region.parentId() == null ? null : regions.get(region.parentId());
    }

    /** Bounded regions overlapping a box. */
    public List<Region> intersecting(Cuboid box) {
        return index.intersecting(box);
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Region r : regions.values()) {
            list.add(r.save());
        }
        tag.put("regions", list);
        tag.putInt("format", 1);
        return tag;
    }

    private static RegionStore load(CompoundTag tag, ResourceKey<Level> dimension) {
        RegionStore store = new RegionStore(dimension);
        for (Tag t : tag.getList("regions", Tag.TAG_COMPOUND)) {
            try {
                Region r = Region.load((CompoundTag) t);
                if (!r.dimension().equals(dimension)) {
                    BSPRegionGuard.LOGGER.warn("Region {} is saved in {} but says it belongs to {}; keeping it here",
                            r.id(), dimension.location(), r.dimension().location());
                }
                store.regions.put(r.id(), r);
                if (r.isGlobal()) {
                    store.global = r;
                }
            } catch (RuntimeException e) {
                BSPRegionGuard.LOGGER.error("Could not read a region in {}: {}", dimension.location(), t, e);
            }
        }
        store.reindex();
        return store;
    }
}
