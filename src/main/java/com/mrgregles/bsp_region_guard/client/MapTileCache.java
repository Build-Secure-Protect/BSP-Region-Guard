package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.MapTilesPacket;
import com.mrgregles.bsp_region_guard.network.MapTilesRequestPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plot map's terrain on the client (D61): chunk colours as the server sent them, per world, and which chunks are
 * still on their way. The screen asks for what it shows and draws what has arrived.
 */
public final class MapTileCache {
    private static final Map<String, Map<Long, byte[]>> TILES = new HashMap<>();
    private static final Set<String> ASKED = new HashSet<>();

    private MapTileCache() {
    }

    public static void receive(MapTilesPacket msg) {
        Map<Long, byte[]> world = TILES.computeIfAbsent(msg.dimension(), k -> new HashMap<>());
        for (int i = 0; i < msg.chunks().size(); i++) {
            world.put(msg.chunks().get(i), msg.colours().get(i));
            ASKED.remove(msg.dimension() + "|" + msg.chunks().get(i));
        }
    }

    /** The colours of a chunk (256, row by row), an empty array for empty ground, or null when not here yet. */
    @Nullable
    public static byte[] chunk(String dimension, long chunk) {
        Map<Long, byte[]> world = TILES.get(dimension);
        return world == null ? null : world.get(chunk);
    }

    /** The ARGB colour of one packed map colour. */
    public static int argb(byte packed) {
        int abgr = MapColor.getColorFromPackedId(packed & 0xFF);
        return 0xFF000000 | (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16 & 0xFF);
    }

    /** Asks the server for any of these chunks not here and not already asked for. */
    public static void want(String dimension, List<Long> chunks, boolean refresh) {
        List<Long> ask = new ArrayList<>();
        for (long c : chunks) {
            if ((refresh || chunk(dimension, c) == null) && ASKED.add(dimension + "|" + c)) {
                ask.add(c);
            }
        }
        for (int i = 0; i < ask.size(); i += com.mrgregles.bsp_region_guard.claim.MapTiles.MAX_PER_REQUEST) {
            RegionNetwork.CHANNEL.sendToServer(new MapTilesRequestPacket(dimension, refresh,
                    ask.subList(i, Math.min(ask.size(), i + com.mrgregles.bsp_region_guard.claim.MapTiles.MAX_PER_REQUEST))));
        }
    }

    public static void clear() {
        TILES.clear();
        ASKED.clear();
    }
}
