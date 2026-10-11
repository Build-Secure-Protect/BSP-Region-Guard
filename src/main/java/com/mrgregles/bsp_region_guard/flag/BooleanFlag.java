package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class BooleanFlag extends Flag<Boolean> {
    public BooleanFlag(String name, @Nullable Boolean defaultValue) {
        super(name, defaultValue);
    }

    @Override
    public Boolean parse(String input, FlagContext context) {
        return switch (input.toLowerCase()) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> throw new IllegalArgumentException("'" + input + "' is not true or false");
        };
    }

    @Override
    public String format(Boolean value) {
        return value.toString();
    }

    @Override
    public Tag save(Boolean value) {
        return ByteTag.valueOf(value);
    }

    @Override
    @Nullable
    public Boolean load(Tag tag) {
        return tag instanceof ByteTag b ? b.getAsByte() != 0 : null;
    }

    @Override
    public List<String> suggestions() {
        return List.of("true", "false");
    }
}
