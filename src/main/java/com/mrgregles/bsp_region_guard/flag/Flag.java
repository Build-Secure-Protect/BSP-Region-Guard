package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A typed region setting. Flags are registered once in {@link FlagRegistry}; a region stores a value per flag.
 *
 * @param <T> the value type
 */
public abstract class Flag<T> {
    private final String name;
    @Nullable
    private final T defaultValue;
    private String category = "Other";
    private String hint = "";

    protected Flag(String name, @Nullable T defaultValue) {
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("flag names are lower-case words joined with '-': " + name);
        }
        this.name = name;
        this.defaultValue = defaultValue;
    }

    public final String name() {
        return name;
    }

    /** The value used when no region sets the flag, or {@code null} when "unset" has its own meaning. */
    @Nullable
    public T defaultValue() {
        return defaultValue;
    }

    /** Parses a value typed in a command. Throws {@link IllegalArgumentException} with a message for the player. */
    public abstract T parse(String input, FlagContext context);

    public abstract String format(T value);

    public abstract Tag save(T value);

    /** Reads a saved value; {@code null} when the tag is not usable. */
    @Nullable
    public abstract T load(Tag tag);

    /** The group the flag is shown under in the screen and the docs. */
    public String category() {
        return category;
    }

    /** One short line saying what the flag does, shown as a hint in the screen. */
    public String hint() {
        return hint;
    }

    /** Sets the category and hint; returns this flag as its own type so it can be assigned directly. */
    @SuppressWarnings("unchecked")
    public <F extends Flag<T>> F describe(String category, String hint) {
        this.category = category;
        this.hint = hint;
        return (F) this;
    }

    /** Values offered as command completions. */
    public List<String> suggestions() {
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public final T cast(Object value) {
        return (T) value;
    }

    @Override
    public final String toString() {
        return name;
    }
}
