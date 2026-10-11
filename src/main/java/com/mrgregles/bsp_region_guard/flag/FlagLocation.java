package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** A point with a facing, in a named dimension. Used by the {@code teleport} and {@code spawn} flags. */
public record FlagLocation(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", dimension.location().toString());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putFloat("yaw", yaw);
        tag.putFloat("pitch", pitch);
        return tag;
    }

    public static FlagLocation load(CompoundTag tag) {
        return new FlagLocation(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("dim"))),
                tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
                tag.getFloat("yaw"), tag.getFloat("pitch"));
    }

    public static FlagLocation of(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        return new FlagLocation(level.dimension(), x, y, z, yaw, pitch);
    }

    public String format() {
        return String.format("%s %.1f %.1f %.1f %.0f %.0f", dimension.location(), x, y, z, yaw, pitch);
    }
}
