package com.mrgregles.bsp_region_guard.api.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

/**
 * Fired on the Forge bus (server thread) when a region trigger fires for a player: on entering or leaving the region,
 * on the region's timer, or when the server is stopping. The destination is a server name (empty for this server),
 * a world id and a position. Region Guard moves the player itself when the destination is on this server; a
 * cross-server destination is for BSP Teleport to handle. Cancel the event to take the teleport over entirely.
 */
@Cancelable
public class RegionTriggerEvent extends Event {
    public enum Cause { ENTER, EXIT, TIMER, SHUTDOWN }

    private final ServerPlayer player;
    private final String regionId;
    private final ResourceKey<Level> regionDimension;
    private final Cause cause;
    private final String destinationServer, destinationWorld;
    private final double x, y, z;
    private final String message;

    public RegionTriggerEvent(ServerPlayer player, String regionId, ResourceKey<Level> regionDimension, Cause cause,
                              String destinationServer, String destinationWorld, double x, double y, double z, String message) {
        this.player = player;
        this.regionId = regionId;
        this.regionDimension = regionDimension;
        this.cause = cause;
        this.destinationServer = destinationServer;
        this.destinationWorld = destinationWorld;
        this.x = x;
        this.y = y;
        this.z = z;
        this.message = message;
    }

    public ServerPlayer player() {
        return player;
    }

    public String regionId() {
        return regionId;
    }

    public ResourceKey<Level> regionDimension() {
        return regionDimension;
    }

    public Cause cause() {
        return cause;
    }

    /** The target server's name in the network, or empty for this server. */
    public String destinationServer() {
        return destinationServer;
    }

    public boolean crossServer() {
        return !destinationServer.isEmpty();
    }

    public String destinationWorld() {
        return destinationWorld;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public String message() {
        return message;
    }
}
