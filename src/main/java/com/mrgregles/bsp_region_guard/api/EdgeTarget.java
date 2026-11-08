package com.mrgregles.bsp_region_guard.api;

/**
 * The far end of an edge link in {@link EdgeWorld#links()} (API 9).
 *
 * @param server    the other world's server ({@code storage.serverId})
 * @param dimension the other world's id
 * @param side      the other world's edge: {@code "north"}, {@code "east"}, {@code "south"} or {@code "west"}
 */
public record EdgeTarget(String server, String dimension, String side) {
}
