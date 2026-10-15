package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.protection.EdgeLinks;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.storage.MirrorBackend;
import com.mrgregles.bsp_region_guard.storage.RegionMirror;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Admins looking at another server's regions in the admin screen (owner decision D44, the server switcher). The
 * regions come from the shared storage and are read-only: while an admin looks, every edit from their screen is
 * refused, so nothing on this server can be changed by mistake under a region id the two servers share.
 */
public final class RemoteViews {
    /** What one admin is looking at: another server's regions in the world they stand in. */
    public record View(String server, String dimension, Map<String, Region> regions) {
    }

    private static final Map<UUID, View> VIEWS = new HashMap<>();

    private RemoteViews() {
    }

    /** The admin's view of another server, if it is still for the world they stand in. */
    @Nullable
    public static View of(ServerPlayer player) {
        View v = VIEWS.get(player.getUUID());
        if (v != null && !v.dimension().equals(player.serverLevel().dimension().location().toString())) {
            VIEWS.remove(player.getUUID()); // they changed world: back to this server
            return null;
        }
        return v;
    }

    public static void clear(ServerPlayer player) {
        VIEWS.remove(player.getUUID());
    }

    /** The servers the picker offers: this one first, then every other server the shared storage knows, by name. */
    public static List<String> servers() {
        String me = EdgeLinks.thisServer();
        if (me.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(me);
        TreeSet<String> others = new TreeSet<>();
        for (EdgeLinks.World w : EdgeLinks.network()) {
            others.add(w.server());
        }
        others.remove(me);
        out.addAll(others);
        return out;
    }

    /** Switches an admin's screen to {@code server} (empty or this server's name: back to this server). Returns a message or null. */
    @Nullable
    static String view(ServerPlayer player, String server) {
        if (server.isEmpty() || server.equals(EdgeLinks.thisServer())) {
            clear(player);
            RegionSync.open(player, false);
            return null;
        }
        RegionMirror mirror = RegionMirror.active();
        if (mirror == null) {
            return "Shared storage is off; there are no other servers to look at.";
        }
        String dim = player.serverLevel().dimension().location().toString();
        mirror.fetchRegionsOf(server, dim, rows -> {
            Map<String, Region> regions = new LinkedHashMap<>();
            for (MirrorBackend.RegionRow row : rows) {
                if (row.deleted()) {
                    continue;
                }
                try {
                    Region r = RegionMirror.deserialize(row.data());
                    regions.put(r.id(), r);
                } catch (Exception e) {
                    BSPRegionGuard.LOGGER.debug("Skipping {}'s region {}: {}", server, row.id(), e.toString());
                }
            }
            if (player.hasDisconnected()) {
                return;
            }
            View v = new View(server, dim, regions);
            VIEWS.put(player.getUUID(), v);
            String first = regions.values().stream().filter(r -> !r.isGlobal()).map(Region::id).findFirst()
                    .orElse(regions.isEmpty() ? null : regions.keySet().iterator().next());
            RegionSync.sendRemoteList(player, v, first);
            if (first != null) {
                RegionSync.sendDetail(player, regions.get(first), v);
            }
        });
        return null;
    }

    /** For tests. */
    public static void clearAll() {
        VIEWS.clear();
    }
}
