package com.mrgregles.bsp_region_guard.trigger;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.event.RegionEnterEvent;
import com.mrgregles.bsp_region_guard.api.event.RegionLeaveEvent;
import com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.storage.RegionMirror;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Fires region triggers and carries out the ones this server can do itself. Enter and exit come from the movement
 * tracker's events; timers tick once a second per region; shutdown triggers fire for everyone inside when the server
 * stops. Each firing is a {@link RegionTriggerEvent}: BSP Teleport listens for cross-server destinations (and may
 * cancel to take over); when nobody cancels and the destination is on this server, the player is moved here.
 */
public final class Triggers {
    public static final String KIND_TRIGGER = "trigger";
    /** "dimension|region" to the game time of the last timer firing. */
    private static final Map<String, Long> LAST_TIMER = new HashMap<>();
    private static long lastCheck;

    private Triggers() {
    }

    public static void onEnter(RegionEnterEvent event) {
        fireFor(event.getEntity(), event.dimension(), event.regionId(), RegionTriggerEvent.Cause.ENTER);
    }

    public static void onLeave(RegionLeaveEvent event) {
        fireFor(event.getEntity(), event.dimension(), event.regionId(), RegionTriggerEvent.Cause.EXIT);
    }

    private static void fireFor(ServerPlayer player, ResourceKey<Level> dimension, String regionId, RegionTriggerEvent.Cause cause) {
        ServerLevel level = player.server.getLevel(dimension);
        if (level == null) {
            return;
        }
        Region region = RegionStore.get(level).get(regionId);
        if (region == null) {
            return;
        }
        for (Trigger t : Trigger.of(region.typeData())) {
            if (t.cause() == cause) {
                fire(player, region, t);
            }
        }
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        if (now - lastCheck < 20) {
            return;
        }
        lastCheck = now;
        for (ServerLevel level : server.getAllLevels()) {
            RegionStore store = RegionStore.get(level);
            if (!store.hasBoundedRegions()) {
                continue;
            }
            for (Region region : store.all()) {
                if (region.bounds() == null || !region.typeData().contains(Trigger.KEY)) {
                    continue;
                }
                for (Trigger t : Trigger.of(region.typeData())) {
                    if (t.cause() != RegionTriggerEvent.Cause.TIMER || t.intervalSeconds() <= 0) {
                        continue;
                    }
                    String key = level.dimension().location() + "|" + region.id();
                    long last = LAST_TIMER.getOrDefault(key, now);
                    if (!LAST_TIMER.containsKey(key)) {
                        LAST_TIMER.put(key, now);
                        continue;
                    }
                    if (now - last >= t.intervalSeconds() * 20L) {
                        LAST_TIMER.put(key, now);
                        for (ServerPlayer p : level.players()) {
                            if (region.contains(p.blockPosition())) {
                                fire(p, region, t);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            RegionStore store = RegionStore.get(level);
            for (Region region : store.all()) {
                if (!region.typeData().contains(Trigger.KEY)) {
                    continue;
                }
                for (Trigger t : Trigger.of(region.typeData())) {
                    if (t.cause() == RegionTriggerEvent.Cause.SHUTDOWN) {
                        for (ServerPlayer p : level.players()) {
                            if (region.contains(p.blockPosition())) {
                                fire(p, region, t);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Fires one trigger for one player: the event first, then the move if nobody took it over and it is local.
     * Returns true when the move was carried out (by a listener such as BSP Teleport, or here); false when a
     * cross-server move found nobody to do it, or the world does not exist.
     */
    public static boolean fire(ServerPlayer player, Region region, Trigger t) {
        RegionTriggerEvent event = new RegionTriggerEvent(player, region.id(), region.dimension(), t.cause(), t.server(), t.world(), t.x(), t.y(), t.z(), t.message());
        if (MinecraftForge.EVENT_BUS.post(event)) {
            return true;
        }
        if (!t.message().isEmpty()) {
            player.displayClientMessage(Component.literal(t.message()), false);
        }
        if (t.server().isEmpty()) {
            ServerLevel dest = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.world())));
            if (dest == null) {
                BSPRegionGuard.LOGGER.warn("Trigger on {} points at a world that does not exist: {}", region.id(), t.world());
                return false;
            }
            player.teleportTo(dest, t.x(), t.y(), t.z(), player.getYRot(), player.getXRot());
            return true;
        } else if (RegionMirror.active() != null) {
            // nobody on this server handles cross-server moves yet: let the network know (BSP Teleport can act on it)
            RegionMirror.active().publish(KIND_TRIGGER, region.dimension().location().toString(), region.id(),
                    player.getUUID() + ";" + t.server() + ";" + t.world() + ";" + t.x() + ";" + t.y() + ";" + t.z());
        } else {
            BSPRegionGuard.LOGGER.warn("Trigger on {} needs server '{}' but no teleport mod handled it and shared storage is off", region.id(), t.server());
        }
        return false;
    }

    /**
     * Parses what an admin typed: {@code here [message]} (the player's world and position), {@code <world> <x> <y> <z> [message]}
     * or {@code <server>/<world> <x> <y> <z> [message]}; a TIMER takes {@code <seconds>} first. Throws with a message for the admin.
     */
    public static Trigger parse(ServerPlayer admin, RegionTriggerEvent.Cause cause, String destination) {
        String[] parts = destination.trim().split("\\s+");
        int i = 0, interval = 0;
        if (cause == RegionTriggerEvent.Cause.TIMER) {
            try {
                interval = Integer.parseInt(parts[i++]);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("a timer needs the seconds first, then the destination");
            }
            if (interval <= 0) {
                throw new IllegalArgumentException("the timer must be at least 1 second");
            }
        }
        if (i >= parts.length || parts[i].isEmpty()) {
            throw new IllegalArgumentException("give a destination: 'here', '<world> <x> <y> <z>' or '<server>/<world> <x> <y> <z>', then an optional message");
        }
        String server = "", world;
        double x, y, z;
        if (parts[i].equalsIgnoreCase("here")) {
            world = admin.serverLevel().dimension().location().toString();
            x = admin.getX();
            y = admin.getY();
            z = admin.getZ();
            i++;
        } else {
            if (i + 3 >= parts.length) {
                throw new IllegalArgumentException("give a world and x y z");
            }
            String w = parts[i++];
            int slash = w.indexOf('/');
            if (slash > 0) {
                server = w.substring(0, slash);
                w = w.substring(slash + 1);
            }
            world = w.contains(":") ? w : "minecraft:" + w;
            try {
                x = Double.parseDouble(parts[i++]);
                y = Double.parseDouble(parts[i++]);
                z = Double.parseDouble(parts[i++]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("x, y and z must be numbers");
            }
        }
        StringBuilder message = new StringBuilder();
        for (; i < parts.length; i++) {
            message.append(message.length() == 0 ? "" : " ").append(parts[i]);
        }
        return new Trigger(cause, server, world, x, y, z, interval, message.toString());
    }

    public static void clearAll() {
        LAST_TIMER.clear();
    }
}
