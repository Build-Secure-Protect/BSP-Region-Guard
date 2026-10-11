package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Free text, or one of a fixed set of words when {@code choices} is given. */
public final class StringFlag extends Flag<String> {
    private final List<String> choices;

    public StringFlag(String name, @Nullable String defaultValue, String... choices) {
        super(name, defaultValue);
        this.choices = List.of(choices);
    }

    @Override
    public String parse(String input, FlagContext context) {
        if (!choices.isEmpty() && !choices.contains(input.toLowerCase())) {
            throw new IllegalArgumentException("'" + input + "' must be one of " + String.join(", ", choices));
        }
        return choices.isEmpty() ? input : input.toLowerCase();
    }

    @Override
    public String format(String value) {
        return value;
    }

    @Override
    public Tag save(String value) {
        return StringTag.valueOf(value);
    }

    @Override
    @Nullable
    public String load(Tag tag) {
        return tag instanceof StringTag s ? s.getAsString() : null;
    }

    @Override
    public List<String> suggestions() {
        return choices;
    }
}
