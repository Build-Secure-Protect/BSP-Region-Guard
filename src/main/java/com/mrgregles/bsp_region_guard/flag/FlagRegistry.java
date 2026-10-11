package com.mrgregles.bsp_region_guard.flag;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every flag the server knows, by name. Region Guard registers its own in {@link Flags}; other mods may add theirs
 * through the API before the server starts.
 */
public final class FlagRegistry {
    private static final Map<String, Flag<?>> FLAGS = new LinkedHashMap<>();

    private FlagRegistry() {
    }

    public static synchronized <F extends Flag<?>> F register(F flag) {
        Flag<?> previous = FLAGS.putIfAbsent(flag.name(), flag);
        if (previous != null && previous != flag) {
            throw new IllegalStateException("a flag named '" + flag.name() + "' is already registered");
        }
        return flag;
    }

    @Nullable
    public static Flag<?> get(String name) {
        return FLAGS.get(name.toLowerCase());
    }

    public static Collection<Flag<?>> all() {
        return Collections.unmodifiableCollection(FLAGS.values());
    }

    public static Collection<String> names() {
        return Collections.unmodifiableSet(FLAGS.keySet());
    }
}
