package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

public final class IntFlag extends Flag<Integer> {
    public IntFlag(String name, @Nullable Integer defaultValue) {
        super(name, defaultValue);
    }

    @Override
    public Integer parse(String input, FlagContext context) {
        try {
            return Integer.parseInt(input.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + input + "' is not a whole number");
        }
    }

    @Override
    public String format(Integer value) {
        return value.toString();
    }

    @Override
    public Tag save(Integer value) {
        return IntTag.valueOf(value);
    }

    @Override
    @Nullable
    public Integer load(Tag tag) {
        return tag instanceof NumericTag n ? n.getAsInt() : null;
    }
}
