package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.claim.MapTiles;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The plot map's terrain (D61): map colours for loaded chunks, and never generating the world to draw it. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class MapTileGameTests {
    private static final String EMPTY = "empty";

    @GameTest(template = EMPTY)
    public void a_loaded_chunk_is_coloured_from_its_top_blocks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos column = h.absolutePos(new BlockPos(3, 0, 3));
        BlockPos top = new BlockPos(column.getX(), level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ()), column.getZ());
        BlockState before = level.getBlockState(top);
        level.setBlock(top, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        try {
            ChunkPos chunk = new ChunkPos(top);
            Map<Long, byte[]> got = new ConcurrentHashMap<>();
            MapTiles.request(level, List.of(chunk.toLong()), true, got::putAll);
            byte[] colours = got.get(chunk.toLong());
            h.assertTrue(colours != null && colours.length == 256, "a loaded chunk is answered at once with 256 colours");
            int index = (top.getZ() - chunk.getMinBlockZ()) * 16 + (top.getX() - chunk.getMinBlockX());
            h.assertTrue((colours[index] & 0xFF) >> 2 == MapColor.GOLD.id, "the gold block on top shows gold: " + ((colours[index] & 0xFF) >> 2));
            int other = (colours[index] & 0xFF) >> 2;
            long notGold = java.util.stream.IntStream.range(0, 256).filter(i -> ((colours[i] & 0xFF) >> 2) != other).count();
            h.assertTrue(notGold > 0, "and the ground around it does not");
        } finally {
            level.setBlock(top, before, 3);
            MapTiles.clear(level.dimension());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void ground_never_generated_comes_back_empty_and_stays_ungenerated(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos far = new ChunkPos(180_000, 180_000);
        Map<Long, byte[]> got = new ConcurrentHashMap<>();
        MapTiles.request(level, List.of(far.toLong()), true, got::putAll);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(got.containsKey(far.toLong()), "the answer arrives"))
                .thenExecute(() -> {
                    h.assertTrue(got.get(far.toLong()).length == 0, "empty: nothing there");
                    h.assertTrue(level.getChunkSource().getChunkNow(far.x, far.z) == null, "and the chunk was not generated or loaded");
                })
                .thenSucceed();
    }
}
