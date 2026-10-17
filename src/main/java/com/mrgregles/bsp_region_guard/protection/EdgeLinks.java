package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.Border;
import com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent;
import com.mrgregles.bsp_region_guard.api.event.RemoteMessageEvent;
import com.mrgregles.bsp_region_guard.network.EdgePromptPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.storage.MirrorBackend;
import com.mrgregles.bsp_region_guard.storage.RegionMirror;
import com.mrgregles.bsp_region_guard.trigger.Trigger;
import com.mrgregles.bsp_region_guard.trigger.Triggers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Edge links between servers (owner decisions D40 to D42). An admin links one edge of this world's border to one
 * edge of another server's world; a player who walks into a linked edge is asked whether to travel, and on yes is
 * handed to BSP Teleport (a {@link RegionTriggerEvent} with cause {@code EDGE}) to arrive at the same fraction along
 * the other edge, {@link #ARRIVAL_INSET} blocks inside it.
 *
 * <p>Each server owns its own links (kept on its world default, like the border, D36); a link is two-way because
 * every server also reads the other servers' world defaults from the shared storage and treats a link that points at
 * one of its edges as its own. So a link is only ever written on the server where the admin made it.
 */
public final class EdgeLinks {
    /** Key in the world default's type data: side name to "server|dimension|side". */
    public static final String KEY = "edgeLinks";
    public static final String KIND_CHANGED = "edge-links", KIND_UNLINK = "edge-unlink", KIND_ARRIVAL = "edge-arrival";
    /** How far inside the other world's edge a traveller arrives (D41). */
    public static final int ARRIVAL_INSET = 16;
    /** How close to a linked edge (in blocks) a player is asked whether to travel; past the edge counts too. */
    public static final double REACH = 1.0;
    private static final long PROMPT_MS = 60_000, ARRIVAL_MS = 60_000, REFRESH_TICKS = 20 * 60 * 5;

    private EdgeLinks() {
    }

    // ------------------------------------------------------------------ model

    /** An edge of a square border. North is towards negative z, as in Minecraft. */
    public enum Side {
        NORTH, EAST, SOUTH, WEST;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        @Nullable
        public static Side parse(String text) {
            for (Side s : values()) {
                if (s.key().equalsIgnoreCase(text.trim()) || s.key().substring(0, 1).equalsIgnoreCase(text.trim())) {
                    return s;
                }
            }
            return null;
        }

        public Side opposite() {
            return values()[(ordinal() + 2) % 4];
        }
    }

    /** One edge of one world on one server. */
    public record Place(String server, String dimension, Side side) {
        public String serialize() {
            return server + "|" + dimension + "|" + side.key();
        }

        @Nullable
        public static Place parse(String text) {
            String[] p = text.split("\\|");
            Side s = p.length == 3 ? Side.parse(p[2]) : null;
            return s == null ? null : new Place(p[0], p[1], s);
        }

        String world() {
            return server + "|" + dimension;
        }
    }

    /** What an admin's link or unlink did: {@code ok} false means nothing changed. */
    public record Outcome(boolean ok, String message) {
    }

    /** Another server's world as this server last read it: its border and the links its admin made. */
    public record World(String server, String dimension, @Nullable Border border, Map<Side, Place> links) {
    }

    // ------------------------------------------------------------------ this server's links

    /** This server's name in the shared storage, or empty when storage is off (links then do nothing). */
    public static String thisServer() {
        RegionMirror m = RegionMirror.active();
        return m == null ? "" : m.serverId();
    }

    /** The links this server's admin made for a world. */
    public static Map<Side, Place> own(ServerLevel level) {
        return read(RegionStore.get(level).hasGlobal() ? RegionStore.get(level).global().typeData() : new CompoundTag());
    }

    static Map<Side, Place> read(CompoundTag typeData) {
        Map<Side, Place> out = new EnumMap<>(Side.class);
        CompoundTag t = typeData.getCompound(KEY);
        for (Side s : Side.values()) {
            Place p = t.contains(s.key()) ? Place.parse(t.getString(s.key())) : null;
            if (p != null) {
                out.put(s, p);
            }
        }
        return out;
    }

    private static void write(ServerLevel level, Map<Side, Place> links) {
        RegionStore store = RegionStore.get(level);
        Region global = store.global();
        CompoundTag t = new CompoundTag();
        links.forEach((s, p) -> t.putString(s.key(), p.serialize()));
        if (t.isEmpty()) {
            global.typeData().remove(KEY);
        } else {
            global.typeData().put(KEY, t);
        }
        store.touch(global);
    }

    /** A world's links in both directions: the ones made here, and the ones other servers made that end at this world. */
    public static Map<Side, Place> effective(ServerLevel level) {
        Map<Side, Place> out = new EnumMap<>(own(level));
        String me = thisServer(), dim = level.dimension().location().toString();
        for (World w : NETWORK.values()) {
            w.links().forEach((side, p) -> {
                if (p.server().equals(me) && p.dimension().equals(dim) && !out.containsKey(p.side())) {
                    out.put(p.side(), new Place(w.server(), w.dimension(), side));
                }
            });
        }
        return out;
    }

    /** Whether an edge of another server's world is already linked: by its own server, by another one, or from this world. */
    private static boolean taken(ServerLevel level, Place edge) {
        if (own(level).containsValue(edge)) {
            return true;
        }
        World w = NETWORK.get(edge.world());
        if (w != null && w.links().containsKey(edge.side())) {
            return true;
        }
        for (World other : NETWORK.values()) {
            for (Place p : other.links().values()) {
                if (p.equals(edge)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Links an edge of this world to an edge of another server's world (the admin's action). Returns a sentence. */
    public static Outcome link(ServerLevel level, Side side, Place target) {
        if (thisServer().isEmpty()) {
            return new Outcome(false, "Edge links need the shared storage (storage.mode = mysql).");
        }
        if (WorldBorders.of(level) == null) {
            return new Outcome(false, "Set this world's border first: /rg border set <width>.");
        }
        if (target.server().equals(thisServer())) {
            return new Outcome(false, "Link to a world on another server.");
        }
        World w = NETWORK.get(target.world());
        if (w == null) {
            return new Outcome(false, "No world " + target.dimension() + " on " + target.server() + " is known. It needs shared storage on, and a moment after it starts.");
        }
        if (w.border() == null) {
            return new Outcome(false, target.server() + " has no border in " + target.dimension() + "; set one there first.");
        }
        Map<Side, Place> mine = effective(level);
        if (mine.containsKey(side)) {
            return new Outcome(false, "This world's " + side.key() + " edge already leads to " + mine.get(side).server() + "; unlink it first.");
        }
        if (taken(level, target)) {
            return new Outcome(false, target.server() + "'s " + target.side().key() + " edge is already linked.");
        }
        Map<Side, Place> own = own(level);
        own.put(side, target);
        write(level, own);
        publish(KIND_CHANGED, level, "");
        return new Outcome(true, "The " + side.key() + " edge now leads to " + target.server() + " (" + target.dimension() + "), arriving at its " + target.side().key()
                + " edge. The link works both ways.");
    }

    /** Removes the link on an edge, whichever server made it (the other server is told to drop its side). */
    public static Outcome unlink(ServerLevel level, Side side) {
        Map<Side, Place> own = own(level);
        if (own.remove(side) != null) {
            write(level, own);
            publish(KIND_CHANGED, level, "");
            return new Outcome(true, "The " + side.key() + " edge no longer leads anywhere.");
        }
        Place theirs = effective(level).get(side);
        if (theirs == null) {
            return new Outcome(false, "The " + side.key() + " edge is not linked.");
        }
        publish(KIND_UNLINK, level, theirs.serialize());
        // drop it from the cache now; the owner removes it and tells everyone
        World w = NETWORK.get(theirs.world());
        if (w != null) {
            Map<Side, Place> links = new EnumMap<>(Side.class);
            links.putAll(w.links());
            links.remove(theirs.side());
            NETWORK.put(theirs.world(), new World(w.server(), w.dimension(), w.border(), links));
        }
        return new Outcome(true, "The " + side.key() + " edge no longer leads to " + theirs.server() + ".");
    }

    private static void publish(String kind, ServerLevel level, String payload) {
        RegionMirror m = RegionMirror.active();
        if (m != null) {
            m.publish(kind, level.dimension().location().toString(), Region.GLOBAL_ID, payload);
        }
    }

    // ------------------------------------------------------------------ the network cache

    /** Other servers' worlds, "server|dimension" to what was read. Server thread only. */
    private static final Map<String, World> NETWORK = new HashMap<>();
    private static long lastRefreshTick = Long.MIN_VALUE;

    public static List<World> network() {
        return new ArrayList<>(NETWORK.values());
    }

    /** Reads every server's world defaults from the shared storage (in the background) and rebuilds the cache. */
    public static void refresh() {
        refresh(() -> {
        });
    }

    /** As {@link #refresh()}, then runs {@code then} on the server thread once the cache is rebuilt. */
    public static void refresh(Runnable then) {
        RegionMirror m = RegionMirror.active();
        if (m == null) {
            NETWORK.clear();
            then.run();
            return;
        }
        String me = m.serverId();
        m.fetchGlobals(rows -> {
            NETWORK.clear();
            for (MirrorBackend.RegionRow row : rows) {
                if (row.server().equals(me)) {
                    continue;
                }
                try {
                    Region global = RegionMirror.deserialize(row.data());
                    CompoundTag t = global.typeData();
                    int size = t.getInt(WorldBorders.SIZE);
                    Border b = size > 0 ? new Border(t.getDouble(WorldBorders.CENTER_X), t.getDouble(WorldBorders.CENTER_Z), size) : null;
                    NETWORK.put(row.server() + "|" + row.dimension(), new World(row.server(), row.dimension(), b, read(t)));
                } catch (Exception e) {
                    BSPRegionGuard.LOGGER.debug("Skipping the world default of {} {}: {}", row.server(), row.dimension(), e.toString());
                }
            }
            then.run();
        });
    }

    /** The edges of another server's world that nobody has linked yet (neither its server, nor another, nor this world). */
    public static List<Side> freeSides(ServerLevel level, World w) {
        List<Side> out = new ArrayList<>();
        for (Side s : Side.values()) {
            if (!taken(level, new Place(w.server(), w.dimension(), s))) {
                out.add(s);
            }
        }
        return out;
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long now = event.getServer().getTickCount();
        // first read 10 s after start (the storage connects in the background), then every 5 minutes
        if (lastRefreshTick == Long.MIN_VALUE ? now >= 200 : now - lastRefreshTick >= REFRESH_TICKS) {
            if (lastRefreshTick == Long.MIN_VALUE) {
                checkServerName();
            }
            lastRefreshTick = now;
            refresh();
        }
    }

    /**
     * Cross-server moves name the other server by its {@code storage.serverId}, and BSP Teleport sends the player to
     * the proxy server of that name, so the two names must match on every server.
     */
    private static void checkServerName() {
        String mine = thisServer(), proxy = com.mrgregles.bsp_region_guard.compat.BspTeleport.serverName();
        if (!mine.isEmpty() && proxy != null && !proxy.isEmpty() && !proxy.equals(mine)) {
            BSPRegionGuard.LOGGER.warn("storage.serverId is '{}' but BSP Teleport calls this server '{}' in the proxy. Edge links and cross-server "
                    + "triggers send players to the server named by storage.serverId; make the two the same on every server.", mine, proxy);
        }
    }

    /** Another server changed its border or links, asks this one to drop a link, or announces a traveller. */
    public static void onRemoteMessage(RemoteMessageEvent event) {
        switch (event.kind()) {
            case KIND_CHANGED -> refresh();
            case KIND_UNLINK -> {
                Place p = Place.parse(event.payload());
                if (p == null || !p.server().equals(thisServer())) {
                    return;
                }
                MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server == null) {
                    return;
                }
                for (ServerLevel level : server.getAllLevels()) {
                    if (level.dimension().location().toString().equals(p.dimension())) {
                        Map<Side, Place> own = own(level);
                        Place there = own.get(p.side());
                        if (there != null && there.server().equals(event.source())) {
                            own.remove(p.side());
                            write(level, own);
                            publish(KIND_CHANGED, level, "");
                        }
                    }
                }
            }
            case KIND_ARRIVAL -> {
                String[] a = event.payload().split(";");
                if (a.length == 5 && a[1].equals(thisServer())) {
                    try {
                        ARRIVALS.put(UUID.fromString(a[0]), new Arrival(event.dimension(), Double.parseDouble(a[3]), Double.parseDouble(a[4]),
                                System.currentTimeMillis() + ARRIVAL_MS));
                    } catch (IllegalArgumentException ignored) {
                        // not ours or malformed
                    }
                }
            }
            default -> {
            }
        }
    }

    /** For tests: replaces the cache. */
    public static void setNetwork(List<World> worlds) {
        NETWORK.clear();
        for (World w : worlds) {
            NETWORK.put(w.server() + "|" + w.dimension(), w);
        }
    }

    // ------------------------------------------------------------------ geometry

    /** Which edge a point outside the border lies beyond (the one it is furthest past). */
    public static Side sideCrossed(Border b, double x, double z) {
        double dx = x - b.centerX(), dz = z - b.centerZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Side.EAST : Side.WEST;
        }
        return dz > 0 ? Side.SOUTH : Side.NORTH;
    }

    /** How far along an edge a point lies, 0 to 1: west to east for north and south edges, north to south for east and west edges. */
    public static double fraction(Border b, Side side, double x, double z) {
        double f = side == Side.NORTH || side == Side.SOUTH ? (x - b.minX()) / b.size() : (z - b.minZ()) / b.size();
        return Mth.clamp(f, 0, 1);
    }

    /** Where a traveller arrives: the same fraction along {@code side}, {@link #ARRIVAL_INSET} blocks inside (D41). Returns {x, z}. */
    public static double[] arrival(Border b, Side side, double f) {
        double in = Math.min(ARRIVAL_INSET, b.half());
        double along = Mth.clamp(f * b.size(), in, b.size() - in);
        return switch (side) {
            case NORTH -> new double[]{b.minX() + along, b.minZ() + in};
            case SOUTH -> new double[]{b.minX() + along, b.maxZ() - in};
            case WEST -> new double[]{b.minX() + in, b.minZ() + along};
            case EAST -> new double[]{b.maxX() - in, b.minZ() + along};
        };
    }

    // ------------------------------------------------------------------ the question and the move

    private record Prompt(int token, Side side, Place target, double x, double y, double z, long since) {
    }

    private static final Map<UUID, Prompt> PROMPTS = new HashMap<>();
    private static int nextToken = 1;

    /**
     * Called by the border when a player is within {@link #REACH} of its edge or past it. When the nearest edge is
     * linked: asks the player (once per approach) and returns true, so a player past the edge is pushed back without
     * the border's usual message. False for an unlinked edge.
     */
    static boolean atEdge(ServerPlayer player, MovementTracker.Tracked t, Border b, Vec3 pos) {
        ServerLevel level = player.serverLevel();
        Side side = sideCrossed(b, pos.x, pos.z);
        Place target = effective(level).get(side);
        World there = target == null ? null : NETWORK.get(target.world());
        if (target == null || there == null || there.border() == null) {
            return false;
        }
        if (!t.edgeAsked) {
            t.edgeAsked = true;
            double[] at = arrival(there.border(), target.side(), fraction(b, side, pos.x, pos.z));
            Prompt p = new Prompt(nextToken++, side, target, at[0], pos.y, at[1], System.currentTimeMillis());
            PROMPTS.put(player.getUUID(), p);
            RegionNetwork.sendTo(player, new EdgePromptPacket(p.token(), target.server(), target.dimension(), at[0], at[1]));
        }
        return true;
    }

    /** For tests: the token of the question waiting for this player, or 0. */
    public static int pendingToken(ServerPlayer player) {
        Prompt p = PROMPTS.get(player.getUUID());
        return p == null ? 0 : p.token();
    }

    /** The player's answer from the pop-up. TRAVEL hands the move to BSP Teleport; STAY just closes the question. */
    public static void answer(ServerPlayer player, int token, boolean travel) {
        Prompt p = PROMPTS.get(player.getUUID());
        if (p == null || p.token() != token) {
            return;
        }
        PROMPTS.remove(player.getUUID());
        if (!travel) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Border b = WorldBorders.of(level);
        int near = RegionGuardConfig.getOr(RegionGuardConfig.BORDER_WARN_DISTANCE, 10) + 3;
        if (System.currentTimeMillis() - p.since() > PROMPT_MS || b == null || b.toEdge(player.getX(), player.getZ()) > near
                || sideCrossed(b, player.getX(), player.getZ()) != p.side()) {
            player.displayClientMessage(Component.literal("Walk up to the edge again to travel."), true);
            return;
        }
        // tell the other server where this player should stand once they arrive (it finds standing room there)
        RegionMirror m = RegionMirror.active();
        if (m != null) {
            m.publish(KIND_ARRIVAL, p.target().dimension(), Region.GLOBAL_ID,
                    player.getUUID() + ";" + p.target().server() + ";" + p.target().dimension() + ";" + p.x() + ";" + p.z());
        }
        Trigger move = new Trigger(RegionTriggerEvent.Cause.EDGE, p.target().server(), p.target().dimension(), p.x(), p.y(), p.z(), 0, "");
        if (!Triggers.fire(player, RegionStore.get(level).global(), move)) {
            player.displayClientMessage(Component.literal("Travel to " + p.target().server() + " is not available right now."), true);
        }
    }

    // ------------------------------------------------------------------ arriving

    private record Arrival(String dimension, double x, double z, long until) {
    }

    private static final Map<UUID, Arrival> ARRIVALS = new HashMap<>();

    /**
     * Once a traveller from a linked edge stands at their arrival point here, moves them onto the ground at or near
     * that spot, never into a cave (the sending server could not know the ground here). Called every tick from the
     * movement tracker.
     */
    static void tickArrival(ServerPlayer player) {
        if (ARRIVALS.isEmpty()) {
            return;
        }
        Arrival a = ARRIVALS.get(player.getUUID());
        if (a == null) {
            return;
        }
        if (System.currentTimeMillis() > a.until()) {
            ARRIVALS.remove(player.getUUID());
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().toString().equals(a.dimension()) || Mth.square(player.getX() - a.x()) + Mth.square(player.getZ() - a.z()) > 36) {
            return; // not there yet (still connecting)
        }
        ARRIVALS.remove(player.getUUID());
        Vec3 spot = WorldBorders.groundSpot(level, WorldBorders.of(level), player.getX(), player.getZ(), player.getY());
        player.teleportTo(level, spot.x, spot.y, spot.z, player.getYRot(), player.getXRot());
        player.fallDistance = 0;
    }

    /** For tests: a traveller announced for this server. */
    public static boolean expecting(UUID player) {
        return ARRIVALS.containsKey(player);
    }

    public static void clearAll() {
        NETWORK.clear();
        PROMPTS.clear();
        ARRIVALS.clear();
        lastRefreshTick = Long.MIN_VALUE;
    }
}
