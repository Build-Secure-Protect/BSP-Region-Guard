package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

public final class DoubleFlag extends Flag<Double> {
    public DoubleFlag(String name, @Nullable Double defaultValue) {
        super(name, defaultValue);
    }

    @Override
    public Double parse(String input, FlagContext context) {
        try {
            return Double.parseDouble(input.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + input + "' is not a number");
        }
    }

    @Override
    public String format(Double value) {
        return value % 1 == 0 ? String.valueOf(value.longValue()) : value.toString();
    }

    @Override
    public Tag save(Double value) {
        return DoubleTag.valueOf(value);
    }

    @Override
    @Nullable
    public Double load(Tag tag) {
        return tag instanceof NumericTag n ? n.getAsDouble() : null;
    }
}
