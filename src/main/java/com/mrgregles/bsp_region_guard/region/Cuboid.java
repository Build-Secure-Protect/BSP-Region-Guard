package com.mrgregles.bsp_region_guard.region;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** An axis-aligned box of whole blocks, both corners inclusive. */
public record Cuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Cuboid of(BlockPos a, BlockPos b) {
        return new Cuboid(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean contains(double x, double y, double z) {
        return contains((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /** True when this box lies entirely inside {@code other}. */
    public boolean within(Cuboid other) {
        return minX >= other.minX && minY >= other.minY && minZ >= other.minZ
                && maxX <= other.maxX && maxY <= other.maxY && maxZ <= other.maxZ;
    }

    public boolean intersects(Cuboid other) {
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** Blocks in the box, as a long because large regions overflow an int. */
    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** Ground area in blocks (what a claim is priced on). */
    public long area() {
        return (long) sizeX() * sizeZ();
    }

    public BlockPos min() {
        return new BlockPos(minX, minY, minZ);
    }

    public BlockPos max() {
        return new BlockPos(maxX, maxY, maxZ);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("min", new int[]{minX, minY, minZ});
        tag.putIntArray("max", new int[]{maxX, maxY, maxZ});
        return tag;
    }

    public static Cuboid load(CompoundTag tag) {
        int[] min = tag.getIntArray("min");
        int[] max = tag.getIntArray("max");
        return new Cuboid(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    @Override
    public String toString() {
        return "(" + minX + ", " + minY + ", " + minZ + ") to (" + maxX + ", " + maxY + ", " + maxZ + ")";
    }
}
