package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

public final class GameModeFlag extends Flag<GameType> {
    public GameModeFlag(String name) {
        super(name, null);
    }

    @Override
    public GameType parse(String input, FlagContext context) {
        GameType type = GameType.byName(input.toLowerCase(), null);
        if (type == null) {
            throw new IllegalArgumentException("'" + input + "' is not a game mode");
        }
        return type;
    }

    @Override
    public String format(GameType value) {
        return value.getName();
    }

    @Override
    public Tag save(GameType value) {
        return StringTag.valueOf(value.getName());
    }

    @Override
    @Nullable
    public GameType load(Tag tag) {
        return tag instanceof StringTag s ? GameType.byName(s.getAsString(), null) : null;
    }

    @Override
    public List<String> suggestions() {
        return Arrays.stream(GameType.values()).map(GameType::getName).toList();
    }
}
