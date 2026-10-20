package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.claim.PlotBlocks;
import com.mrgregles.bsp_region_guard.claim.PlotMap;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Moving, copying, backing up and restoring a plot's blocks (D67 to D70). In their own batch: they rewrite ground. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class PlotBlockGameTests {
    private static final String EMPTY = "empty", BATCH = "plot-blocks";

    /** A box of 3 x 2 blocks, 2 high, at relative x, z (y 2 to 3). */
    private static Cuboid box(GameTestHelper h, int x, int z, int w, int d) {
        return Cuboid.of(h.absolutePos(new BlockPos(x, 2, z)), h.absolutePos(new BlockPos(x + w - 1, 3, z + d - 1)));
    }

    private static ServerPlayer admin(GameTestHelper h, List<String> previous) {
        ServerPlayer a = TestPlayers.mockServerPlayer(h);
        previous.addAll(BspCore.setAdminNames(List.of(a.getGameProfile().getName())));
        return a;
    }

    /** Builds the test plot: a chest facing north holding a diamond, a stone beside it, a gold block in the back row. */
    private static void build(GameTestHelper h, Cuboid b) {
        ServerLevel level = h.getLevel();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        BlockPos chest = new BlockPos(b.minX(), b.minY(), b.minZ());
        level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH), 3);
        ((ChestBlockEntity) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        level.setBlock(chest.east(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(b.minX() + 2, b.minY(), b.minZ() + 1), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
    }

    @GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public void moving_blocks_turns_them_keeps_contents_backs_up_and_undoes(GameTestHelper h) {
        List<String> previous = new ArrayList<>();
        ServerPlayer admin = admin(h, previous);
        ServerLevel level = h.getLevel();
        Cuboid from = box(h, 2, 2, 3, 2);
        build(h, from);
        Region r = new Region("pb_move", level.dimension(), from, RegionType.PLOT_FOR_SALE);
        RegionStore.get(level).add(r);
        Cuboid to = box(h, 12, 2, 2, 3); // turned: 2 wide, 3 deep
        PlotMap.setLocked(admin, "pb_move", false);
        var moved = PlotMap.move(admin, "pb_move", to, 0, false, true, 1);
        h.assertTrue(moved.ok(), "moved: " + moved.message());
        PlotBlocks.finishAll();

        // a quarter turn clockwise: (x, z) lands at (d-1-z, x); d = 2
        BlockPos chestNow = new BlockPos(to.minX() + 1, to.minY(), to.minZ());
        h.assertTrue(level.getBlockState(chestNow).is(Blocks.CHEST), "the chest went along: " + level.getBlockState(chestNow));
        h.assertTrue(level.getBlockState(chestNow).getValue(ChestBlock.FACING) == Direction.EAST, "and turned with the plot");
        h.assertTrue(((ChestBlockEntity) level.getBlockEntity(chestNow)).getItem(0).is(Items.DIAMOND), "with its diamonds");
        h.assertTrue(level.getBlockState(new BlockPos(to.minX(), to.minY(), to.minZ() + 2)).is(Blocks.GOLD_BLOCK), "the gold block too");
        BlockPos chestWas = new BlockPos(from.minX(), from.minY(), from.minZ());
        h.assertFalse(level.getBlockState(chestWas).is(Blocks.CHEST) || level.getBlockState(chestWas.east().east().south()).is(Blocks.GOLD_BLOCK),
                "the old spot is ground again");
        h.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(chestWas).inflate(4)).isEmpty(), "nothing spilled out of the chest");

        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(PlotBlocks.backups(level.getServer(), "pb_move").size() == 2, "both spots were backed up"))
                .thenExecute(() -> {
                    h.assertTrue(PlotMap.undo(admin).ok(), "undo");
                    PlotBlocks.finishAll();
                    h.assertTrue(level.getBlockState(chestWas).is(Blocks.CHEST) && ((ChestBlockEntity) level.getBlockEntity(chestWas)).getItem(0).is(Items.DIAMOND),
                            "undo puts the chest and its diamonds back");
                    h.assertFalse(level.getBlockState(chestNow).is(Blocks.CHEST), "and clears the new spot");
                    h.assertTrue(r.bounds().equals(from), "the plot is back too");
                })
                .thenExecute(() -> cleanUp(h, admin, previous, "pb_move"))
                .thenSucceed();
    }

    @GameTest(template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public void pasting_with_blocks_copies_them_and_a_backup_restores(GameTestHelper h) {
        List<String> previous = new ArrayList<>();
        ServerPlayer admin = admin(h, previous);
        ServerLevel level = h.getLevel();
        Cuboid from = box(h, 2, 12, 3, 2);
        build(h, from);
        RegionStore.get(level).add(new Region("pb_src", level.dimension(), from, RegionType.PLOT_FOR_SALE));
        Cuboid to = box(h, 12, 12, 3, 2);
        var pasted = PlotMap.paste(admin, "pb_src", "pb_copy", "", to, 0, true, 0);
        h.assertTrue(pasted.ok(), "pasted: " + pasted.message());
        PlotBlocks.finishAll();
        BlockPos copyChest = new BlockPos(to.minX(), to.minY(), to.minZ());
        h.assertTrue(level.getBlockState(copyChest).is(Blocks.CHEST) && level.getBlockState(new BlockPos(from.minX(), from.minY(), from.minZ())).is(Blocks.CHEST),
                "the copy has the chest, and the original keeps its own");

        // a backup of the source, a change, then a restore
        h.assertTrue(PlotMap.backup(admin, "pb_src").ok(), "backup");
        PlotBlocks.finishAll();
        BlockPos gold = new BlockPos(from.minX() + 2, from.minY(), from.minZ() + 1);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(PlotBlocks.backups(level.getServer(), "pb_src").stream().anyMatch(f -> f.endsWith("__backup.nbt")), "the backup is written"))
                .thenExecute(() -> {
                    level.setBlock(gold, Blocks.DIRT.defaultBlockState(), 3);
                    String file = PlotBlocks.backups(level.getServer(), "pb_src").stream().filter(f -> f.endsWith("__backup.nbt")).findFirst().orElseThrow();
                    h.assertTrue(PlotMap.restore(admin, file).ok(), "restore");
                    PlotBlocks.finishAll();
                    h.assertTrue(level.getBlockState(gold).is(Blocks.GOLD_BLOCK), "the gold block is back");
                    h.assertFalse(PlotMap.restore(admin, "../../level.dat").ok(), "only backup files can be restored");
                })
                .thenExecute(() -> cleanUp(h, admin, previous, "pb_src", "pb_copy"))
                .thenSucceed();
    }

    private static void cleanUp(GameTestHelper h, ServerPlayer admin, List<String> previous, String... ids) {
        for (String id : ids) {
            RegionStore.get(h.getLevel()).remove(id);
            for (String f : PlotBlocks.backups(h.getLevel().getServer(), id)) {
                try {
                    java.nio.file.Files.deleteIfExists(h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve(PlotBlocks.FOLDER).resolve(f));
                } catch (java.io.IOException ignored) {
                    // a test file left behind is harmless
                }
            }
        }
        PlotMap.forget(admin.getUUID());
        BspCore.setAdminNames(new ArrayList<>(previous));
        admin.discard();
    }
}
