package com.mrgregles.bsp_region_guard.mine;

import com.mrgregles.bsp_region_guard.region.Cuboid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays a mine out (owner decision D28): the heaviest entry fills the box, then every other entry is grown as veins by
 * random walks from a fresh seed, so each reset looks different while the amounts follow the weights. The plan is
 * built in memory and written to the world in batches over several ticks, so a big mine does not freeze the server.
 */
public final class MineFiller {
    /** Vein sizes, in blocks. */
    private static final int VEIN_MIN = 3, VEIN_MAX = 9;

    private MineFiller() {
    }

    /** A planned refill, applied by {@link #apply} in steps. */
    public static final class Job {
        final ServerLevel level;
        final Cuboid box;
        final BlockState[] palette;
        final byte[] plan;
        int cursor;

        Job(ServerLevel level, Cuboid box, BlockState[] palette, byte[] plan) {
            this.level = level;
            this.box = box;
            this.palette = palette;
            this.plan = plan;
        }

        public boolean done() {
            return cursor >= plan.length;
        }

        public long total() {
            return plan.length;
        }

        public int placed() {
            return cursor;
        }

        /** Writes up to {@code budget} blocks. Returns how many were written. */
        public int apply(int budget) {
            int sx = box.sizeX(), sz = box.sizeZ();
            int n = 0;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            while (cursor < plan.length && n < budget) {
                int i = cursor++;
                int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
                p.set(box.minX() + x, box.minY() + y, box.minZ() + z);
                level.setBlock(p, palette[plan[i] & 0xFF], Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                n++;
            }
            return n;
        }
    }

    /** Plans a refill from the fill table. Entries whose block does not exist are skipped; at most 255 entries are used. */
    public static Job plan(ServerLevel level, Cuboid box, List<MineData.Entry> entries, RandomSource random) {
        List<BlockState> states = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (MineData.Entry e : entries) {
            Block b = e.resolve();
            if (b != null && e.weight() > 0 && states.size() < 255) {
                states.add(b.defaultBlockState());
                weights.add(e.weight());
            }
        }
        long volume = box.volume();
        if (volume > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("the mine is too large to refill (" + volume + " blocks)");
        }
        byte[] plan = new byte[(int) volume];
        if (states.isEmpty()) {
            states.add(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            weights.add(1);
        }
        int filler = 0;
        for (int i = 1; i < weights.size(); i++) {
            if (weights.get(i) > weights.get(filler)) {
                filler = i;
            }
        }
        java.util.Arrays.fill(plan, (byte) filler);
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int sx = box.sizeX(), sy = box.sizeY(), sz = box.sizeZ();
        for (int e = 0; e < states.size(); e++) {
            if (e == filler) {
                continue;
            }
            long target = Math.round((double) plan.length * weights.get(e) / total);
            long placed = 0;
            int guard = 0;
            while (placed < target && guard++ < plan.length * 4L) {
                int x = random.nextInt(sx), y = random.nextInt(sy), z = random.nextInt(sz);
                int size = VEIN_MIN + random.nextInt(VEIN_MAX - VEIN_MIN + 1);
                for (int s = 0; s < size && placed < target; s++) {
                    int i = x + z * sx + y * sx * sz;
                    if ((plan[i] & 0xFF) == filler) {
                        plan[i] = (byte) e;
                        placed++;
                    }
                    switch (random.nextInt(6)) {
                        case 0 -> x = Math.min(sx - 1, x + 1);
                        case 1 -> x = Math.max(0, x - 1);
                        case 2 -> z = Math.min(sz - 1, z + 1);
                        case 3 -> z = Math.max(0, z - 1);
                        case 4 -> y = Math.min(sy - 1, y + 1);
                        default -> y = Math.max(0, y - 1);
                    }
                }
            }
        }
        return new Job(level, box, states.toArray(new BlockState[0]), plan);
    }
}
