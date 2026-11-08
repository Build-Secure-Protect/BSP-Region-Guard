package com.mrgregles.bsp_region_guard.api;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A world on the network that has a border or edge links, as {@link RegionService#edgeWorlds()} gives it (API 9).
 *
 * @param server    the server's name in the shared storage ({@code storage.serverId}, equal to its Velocity name)
 * @param dimension the world's id, for example {@code minecraft:overworld}
 * @param border    the world's border, or {@code null} when it has none
 * @param links     the links this world's admin made: the key is the side of this world ({@code "north"},
 *                  {@code "east"}, {@code "south"} or {@code "west"}), the value the other world's edge. Each link
 *                  appears once, here on the world whose admin made it, not again on the other world. Unmodifiable,
 *                  in the order north, east, south, west.
 */
public record EdgeWorld(String server, String dimension, @Nullable Border border, Map<String, EdgeTarget> links) {
    public EdgeWorld {
        links = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(links));
    }
}
