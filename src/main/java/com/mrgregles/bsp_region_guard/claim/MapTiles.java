package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The plot map's terrain (owner decision D61): for each chunk, the 16 x 16 colours a Minecraft map would show
 * (packed map colours with height shading, 256 bytes). Loaded chunks are coloured at once; others are looked up on
 * disk first and, when they exist and are finished, loaded a few per tick to be coloured. Chunks that were never
 * generated come back empty and are never generated. Colours are cached per world until a refresh is asked for.
 */
public final class MapTiles {
    /** The most chunks one request may ask for. */
    public static final int MAX_PER_REQUEST = 256;
    /** Chunks loaded from disk per server tick, to keep the server smooth. */
    private static final int LOADS_PER_TICK = 4;
    /** The answer for a chunk with nothing on disk. */
    public static final byte[] NONE = new byte[0];

    private record Key(ResourceKey<Level> dimension, long chunk) {
    }

    private record Pending(ServerLevel level, ChunkPos pos, Consumer<Map<Long, byte[]>> reply) {
    }

    private static final Map<Key, byte[]> CACHE = new HashMap<>();
    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();

    private MapTiles() {
    }

    /**
     * Answers a request for chunks of one world: what is cached or loaded right away in one reply, the rest as each
     * one is read (each reply maps chunk positions, as {@link ChunkPos#toLong()}, to 256 colours or {@link #NONE}).
     * Server thread only.
     */
    public static void request(ServerLevel level, List<Long> chunks, boolean refresh, Consumer<Map<Long, byte[]>> reply) {
        Map<Long, byte[]> now = new LinkedHashMap<>();
        for (long c : chunks.subList(0, Math.min(chunks.size(), MAX_PER_REQUEST))) {
            Key key = new Key(level.dimension(), c);
            if (refresh) {
                CACHE.remove(key);
            }
            byte[] cached = CACHE.get(key);
            if (cached != null) {
                now.put(c, cached);
                continue;
            }
            ChunkPos pos = new ChunkPos(c);
            LevelChunk loaded = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (loaded != null) {
                byte[] colours = colours(level, loaded);
                CACHE.put(key, colours);
                now.put(c, colours);
                continue;
            }
            // not loaded: is it on disk, finished? (read in the background; never generates anything)
            level.getChunkSource().chunkMap.read(pos).whenComplete((tag, error) -> level.getServer().execute(() -> {
                boolean finished = error == null && tag.isPresent() && tag.get().getString("Status").endsWith("full");
                if (finished) {
                    QUEUE.add(new Pending(level, pos, reply));
                } else {
                    CACHE.put(key, NONE);
                    reply.accept(Map.of(c, NONE));
                }
            }));
        }
        if (!now.isEmpty()) {
            reply.accept(now);
        }
    }

    /** Loads a few finished chunks from disk each tick and colours them. */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (int i = 0; i < LOADS_PER_TICK && !QUEUE.isEmpty(); i++) {
            Pending p = QUEUE.poll();
            Key key = new Key(p.level().dimension(), p.pos().toLong());
            byte[] colours = CACHE.get(key);
            if (colours == null) {
                try {
                    ChunkAccess chunk = p.level().getChunk(p.pos().x, p.pos().z, ChunkStatus.FULL, true);
                    colours = chunk instanceof LevelChunk lc ? colours(p.level(), lc) : NONE;
                } catch (RuntimeException e) {
                    BSPRegionGuard.LOGGER.debug("Could not read chunk {} for the plot map: {}", p.pos(), e.toString());
                    colours = NONE;
                }
                CACHE.put(key, colours);
            }
            p.reply().accept(Map.of(p.pos().toLong(), colours));
        }
    }

    /**
     * The 256 packed map colours of a chunk, row by row (index {@code z * 16 + x}): the top block's map colour, brighter
     * where the ground rises from the block to the north and darker where it falls, and water by its depth.
     */
    public static byte[] colours(ServerLevel level, LevelChunk chunk) {
        byte[] out = new byte[256];
        int[] northHeight = new int[16];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockState state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                MapColor colour = MapColor.NONE;
                while (y > level.getMinBuildHeight()) {
                    pos.set(baseX + x, y, baseZ + z);
                    state = chunk.getBlockState(pos);
                    colour = state.getMapColor(level, pos);
                    if (colour != MapColor.NONE) {
                        break;
                    }
                    y--;
                }
                MapColor.Brightness light;
                if (!state.getFluidState().isEmpty()) {
                    int depth = 0;
                    BlockPos.MutableBlockPos below = pos.mutable();
                    while (depth < 10 && below.getY() > level.getMinBuildHeight() && !chunk.getBlockState(below.move(0, -1, 0)).getFluidState().isEmpty()) {
                        depth++;
                    }
                    light = depth < 2 ? MapColor.Brightness.HIGH : depth < 5 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW;
                } else {
                    int north = z == 0 ? y : northHeight[x];
                    light = y > north ? MapColor.Brightness.HIGH : y < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                northHeight[x] = y;
                out[z * 16 + x] = colour.getPackedId(light);
            }
        }
        return out;
    }

    /** For tests and the screen's refresh: forget a world's colours. */
    public static void clear(ResourceKey<Level> dimension) {
        CACHE.keySet().removeIf(k -> k.dimension().equals(dimension));
    }

    public static void clearAll() {
        CACHE.clear();
        QUEUE.clear();
    }
}
