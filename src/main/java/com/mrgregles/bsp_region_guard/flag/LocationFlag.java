package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A location. Typed as {@code here} (the player's position and facing), {@code x y z}, {@code x y z yaw pitch} or
 * {@code dimension x y z yaw pitch}; without a dimension the region's world is used.
 */
public final class LocationFlag extends Flag<FlagLocation> {
    public LocationFlag(String name) {
        super(name, null);
    }

    @Override
    public FlagLocation parse(String input, FlagContext context) {
        String[] parts = input.trim().split("\\s+");
        if (parts.length == 1 && parts[0].equalsIgnoreCase("here")) {
            if (context.player() == null) {
                throw new IllegalArgumentException("'here' needs a player");
            }
            var p = context.player();
            return FlagLocation.of(p.serverLevel(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        }
        try {
            int i = 0;
            ResourceKey<net.minecraft.world.level.Level> dim = context.level().dimension();
            if (parts.length == 6) {
                dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(parts[i++]));
            } else if (parts.length != 3 && parts.length != 5) {
                throw new IllegalArgumentException("expected 'here', 'x y z', 'x y z yaw pitch' or 'dimension x y z yaw pitch'");
            }
            double x = Double.parseDouble(parts[i++]);
            double y = Double.parseDouble(parts[i++]);
            double z = Double.parseDouble(parts[i++]);
            float yaw = i < parts.length ? Float.parseFloat(parts[i++]) : 0;
            float pitch = i < parts.length ? Float.parseFloat(parts[i]) : 0;
            return new FlagLocation(dim, x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + input + "' has a part that is not a number");
        }
    }

    @Override
    public String format(FlagLocation value) {
        return value.format();
    }

    @Override
    public Tag save(FlagLocation value) {
        return value.save();
    }

    @Override
    @Nullable
    public FlagLocation load(Tag tag) {
        return tag instanceof CompoundTag c && c.contains("dim") ? FlagLocation.load(c) : null;
    }

    @Override
    public List<String> suggestions() {
        return List.of("here");
    }
}
