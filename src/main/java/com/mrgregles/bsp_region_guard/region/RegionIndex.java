package com.mrgregles.bsp_region_guard.region;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the regions that may contain a position without scanning them all.
 *
 * <p>The world is cut into square cells of 64 by 64 blocks (full height). Every region is listed in each cell its box
 * touches, except regions so large that listing them would cost more than checking them, which go in one short
 * "large" list. A lookup is one hash read plus a bounds check per candidate. Random-tick mixins call this for every
 * tick in the world, so an empty cell must be answered without allocating.
 */
final class RegionIndex {
    /** Cell edge is 2^6 = 64 blocks. */
    static final int CELL_SHIFT = 6;
    /** Regions touching more cells than this are kept in the large list instead. */
    static final int LARGE_CELLS = 4096;

    private final Long2ObjectOpenHashMap<List<Region>> cells = new Long2ObjectOpenHashMap<>();
    private final List<Region> large = new ArrayList<>();

    void add(Region region) {
        Cuboid b = region.bounds();
        if (b == null) {
            return; // the global region is not indexed; the store appends it itself
        }
        int cx0 = b.minX() >> CELL_SHIFT, cx1 = b.maxX() >> CELL_SHIFT;
        int cz0 = b.minZ() >> CELL_SHIFT, cz1 = b.maxZ() >> CELL_SHIFT;
        long cellCount = (long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
        if (cellCount > LARGE_CELLS) {
            large.add(region);
            return;
        }
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                cells.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> new ArrayList<>(2)).add(region);
            }
        }
    }

    void remove(Region region) {
        large.remove(region);
        Cuboid b = region.bounds();
        if (b == null) {
            return;
        }
        int cx0 = b.minX() >> CELL_SHIFT, cx1 = b.maxX() >> CELL_SHIFT;
        int cz0 = b.minZ() >> CELL_SHIFT, cz1 = b.maxZ() >> CELL_SHIFT;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                List<Region> list = cells.get(key);
                if (list != null && list.remove(region) && list.isEmpty()) {
                    cells.remove(key);
                }
            }
        }
    }

    void clear() {
        cells.clear();
        large.clear();
    }

    /** True when nothing but the global region could apply at this column. The cheap pre-check for mixins. */
    boolean isEmptyAt(int x, int z) {
        return large.isEmpty() && !cells.containsKey(ChunkPos.asLong(x >> CELL_SHIFT, z >> CELL_SHIFT));
    }

    /** Regions whose box contains the position, unsorted. */
    List<Region> at(int x, int y, int z) {
        List<Region> out = null;
        List<Region> cell = cells.get(ChunkPos.asLong(x >> CELL_SHIFT, z >> CELL_SHIFT));
        if (cell != null) {
            for (Region r : cell) {
                if (r.contains(x, y, z)) {
                    (out == null ? (out = new ArrayList<>(4)) : out).add(r);
                }
            }
        }
        for (Region r : large) {
            if (r.contains(x, y, z)) {
                (out == null ? (out = new ArrayList<>(4)) : out).add(r);
            }
        }
        return out == null ? List.of() : out;
    }

    /** Regions whose box overlaps the given box (for claim placement checks). */
    List<Region> intersecting(Cuboid box) {
        List<Region> out = new ArrayList<>();
        int cx0 = box.minX() >> CELL_SHIFT, cx1 = box.maxX() >> CELL_SHIFT;
        int cz0 = box.minZ() >> CELL_SHIFT, cz1 = box.maxZ() >> CELL_SHIFT;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<Region> cell = cells.get(ChunkPos.asLong(cx, cz));
                if (cell != null) {
                    for (Region r : cell) {
                        if (!out.contains(r) && r.bounds() != null && r.bounds().intersects(box)) {
                            out.add(r);
                        }
                    }
                }
            }
        }
        for (Region r : large) {
            if (r.bounds() != null && r.bounds().intersects(box)) {
                out.add(r);
            }
        }
        return out;
    }
}
