package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/** A set of words, typed as a comma-separated list (mob ids, commands, rank names). */
public final class StringSetFlag extends Flag<Set<String>> {
    public StringSetFlag(String name) {
        super(name, null);
    }

    @Override
    public Set<String> parse(String input, FlagContext context) {
        Set<String> out = new TreeSet<>();
        Arrays.stream(input.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        if (out.isEmpty()) {
            throw new IllegalArgumentException("give at least one entry, separated by commas");
        }
        return out;
    }

    @Override
    public String format(Set<String> value) {
        return String.join(",", value);
    }

    @Override
    public Tag save(Set<String> value) {
        ListTag list = new ListTag();
        value.forEach(s -> list.add(StringTag.valueOf(s)));
        return list;
    }

    @Override
    @Nullable
    public Set<String> load(Tag tag) {
        if (!(tag instanceof ListTag list)) {
            return null;
        }
        Set<String> out = new TreeSet<>();
        for (Tag t : list) {
            out.add(t.getAsString());
        }
        return out;
    }
}
