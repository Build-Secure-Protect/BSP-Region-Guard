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
    /** What fired the trigger: walking in, walking out, the timer while inside, or the server stopping. */
    public enum Cause {
        /** The player walked or teleported into the region. */
        ENTER,
        /** The player left the region. */
        EXIT,
        /** The trigger's interval passed while the player was inside. */
        TIMER,
        /** The server is stopping and the player is inside (the "holding server" case). */
        SHUTDOWN,
        /**
         * The player walked to a world-border edge linked to another server's world and chose to travel (owner
         * decisions D40 to D42). The region is the world default ({@code __global__}); the destination is on the other
         * server, 16 blocks inside its matching edge. Not something an admin adds with {@code /rg trigger}.
         */
        EDGE
    }

    private final ServerPlayer player;
    private final String regionId;
    private final ResourceKey<Level> regionDimension;
    private final Cause cause;
    private final String destinationServer, destinationWorld;
    private final double x, y, z;
    private final String message;

    /** Fired by Region Guard; other mods only listen. */
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

    /** The player the trigger is for. */
    public ServerPlayer player() {
        return player;
    }

    /** The region's id. */
    public String regionId() {
        return regionId;
    }

    /** The world of the region that fired. */
    public ResourceKey<Level> regionDimension() {
        return regionDimension;
    }

    /** What fired it. */
    public Cause cause() {
        return cause;
    }

    /** The target server's name in the network, or empty for this server. */
    public String destinationServer() {
        return destinationServer;
    }

    /** True when the destination is on another server (BSP Teleport carries those out). */
    public boolean crossServer() {
        return !destinationServer.isEmpty();
    }

    /** The destination world id, for example {@code minecraft:overworld}. */
    public String destinationWorld() {
        return destinationWorld;
    }

    /** Destination x. */
    public double x() {
        return x;
    }

    /** Destination y. */
    public double y() {
        return y;
    }

    /** Destination z. */
    public double z() {
        return z;
    }

    /** Shown to the player on arrival; empty for none. */
    public String message() {
        return message;
    }
}
