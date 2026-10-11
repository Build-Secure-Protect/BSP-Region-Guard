package com.mrgregles.bsp_region_guard.api.event;

import net.minecraftforge.eventbus.api.Event;

/**
 * Fired on the Forge bus (server thread) when another server in the network published a message through Region
 * Guard's storage: {@code kind} is free text ({@code mine-reset} is Region Guard's own), {@code source} the sending
 * server's id. Other mods publish with {@code RegionGuardApi.get().publish(...)} once that lands in the api; until
 * then {@code storage.RegionMirror.publish}.
 */
public class RemoteMessageEvent extends Event {
    private final String source, kind, dimension, regionId, payload;

    public RemoteMessageEvent(String source, String kind, String dimension, String regionId, String payload) {
        this.source = source;
        this.kind = kind;
        this.dimension = dimension;
        this.regionId = regionId;
        this.payload = payload;
    }

    public String source() {
        return source;
    }

    public String kind() {
        return kind;
    }

    public String dimension() {
        return dimension;
    }

    public String regionId() {
        return regionId;
    }

    public String payload() {
        return payload;
    }
}
