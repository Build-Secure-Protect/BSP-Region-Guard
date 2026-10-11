package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An allow / deny flag.
 *
 * <p>A <em>member-bypass</em> flag (the {@code build} family) has no fixed default: when no region sets it, owners
 * and members of the region may act and everyone else may not. A flag with a <em>fallback</em> takes the fallback
 * flag's value when it is unset itself ({@code block-break} falls back to {@code build}).
 */
public final class StateFlag extends Flag<State> {
    private final boolean memberBypass;
    @Nullable
    private final StateFlag fallback;

    public StateFlag(String name, @Nullable State defaultValue, boolean memberBypass, @Nullable StateFlag fallback) {
        super(name, defaultValue);
        this.memberBypass = memberBypass;
        this.fallback = fallback;
    }

    /** True when owners and members are exempt whenever the flag is not set explicitly. */
    public boolean memberBypass() {
        return memberBypass;
    }

    @Nullable
    public StateFlag fallback() {
        return fallback;
    }

    @Override
    public State parse(String input, FlagContext context) {
        State state = State.parse(input);
        if (state == null) {
            throw new IllegalArgumentException("'" + input + "' is not allow or deny");
        }
        return state;
    }

    @Override
    public String format(State value) {
        return value.key();
    }

    @Override
    public Tag save(State value) {
        return StringTag.valueOf(value.key());
    }

    @Override
    @Nullable
    public State load(Tag tag) {
        return tag instanceof StringTag s ? State.parse(s.getAsString()) : null;
    }

    @Override
    public List<String> suggestions() {
        return List.of("allow", "deny");
    }
}
