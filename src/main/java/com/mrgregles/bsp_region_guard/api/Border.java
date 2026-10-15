package com.mrgregles.bsp_region_guard.api;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A world's border (owner decisions D36 to D39): a square {@code size} blocks across, centred on
 * {@code (centerX, centerZ)}, the whole height of the world. It belongs to the world's global region and travels
 * with it through the shared storage. Read it with {@link RegionService#border}.
 *
 * @param centerX the centre's x
 * @param centerZ the centre's z
 * @param size    the side length in blocks
 */
public record Border(double centerX, double centerZ, int size) {
    /** Half the side: the distance from the centre to each edge. */
    public double half() {
        return size / 2.0;
    }

    /** The west edge (smallest x). */
    public double minX() {
        return centerX - half();
    }

    /** The east edge (largest x). */
    public double maxX() {
        return centerX + half();
    }

    /** The north edge (smallest z). */
    public double minZ() {
        return centerZ - half();
    }

    /** The south edge (largest z). */
    public double maxZ() {
        return centerZ + half();
    }

    /** Blocks from a point to the nearest edge: positive inside, negative outside. */
    public double toEdge(double x, double z) {
        return half() - Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
    }

    /** True when the point is inside the border or on its edge. */
    public boolean contains(double x, double z) {
        return toEdge(x, z) >= 0;
    }

    /** The nearest point at least {@code margin} blocks inside. */
    public Vec3 clampInside(Vec3 p, double margin) {
        double m = Math.min(margin, half());
        return new Vec3(Mth.clamp(p.x, minX() + m, maxX() - m), p.y, Mth.clamp(p.z, minZ() + m, maxZ() - m));
    }

    @Override
    public String toString() {
        return size + " x " + size + " blocks around " + fmt(centerX) + ", " + fmt(centerZ)
                + " (x " + fmt(minX()) + " to " + fmt(maxX()) + ", z " + fmt(minZ()) + " to " + fmt(maxZ()) + ")";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
