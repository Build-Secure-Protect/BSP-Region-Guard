package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.mine.MineData;
import com.mrgregles.bsp_region_guard.mine.MineFiller;
import com.mrgregles.bsp_region_guard.mine.Mines;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Mines: the vein layout, the reset, the shell and the timer. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class MineGameTests {
    private static final String EMPTY = "empty";
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone"), IRON = ResourceLocation.parse("minecraft:iron_ore"),
            GOLD = ResourceLocation.parse("minecraft:gold_ore");

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    private static Region mine(GameTestHelper h, String id, BlockPos a, BlockPos b) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(a), h.absolutePos(b)), RegionType.PROTECTED);
        store(h).add(r);
        Mines.makeMine(store(h), r);
        MineData.put(r, STONE, 80);
        MineData.put(r, IRON, 15);
        MineData.put(r, GOLD, 5);
        store(h).touch(r);
        return r;
    }

    private static int count(GameTestHelper h, Cuboid box, net.minecraft.world.level.block.Block block) {
        int n = 0;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    if (h.getLevel().getBlockState(new BlockPos(x, y, z)).is(block)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @GameTest(template = EMPTY)
    public void the_fill_follows_the_weights_and_changes_each_time(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Region m = mine(h, "mn_fill", new BlockPos(1, 1, 1), new BlockPos(20, 6, 20));
        Cuboid box = m.bounds();
        try {
            MineFiller.Job job = MineFiller.plan(level, box, MineData.fill(m), RandomSource.create(1));
            job.apply(Integer.MAX_VALUE);
            int total = (int) box.volume(), stone = count(h, box, Blocks.STONE), iron = count(h, box, Blocks.IRON_ORE), gold = count(h, box, Blocks.GOLD_ORE);
            h.assertTrue(stone + iron + gold == total, "every block is from the table (" + stone + "+" + iron + "+" + gold + " of " + total + ")");
            h.assertTrue(Math.abs(iron - total * 0.15) < total * 0.02, "about 15% iron: " + iron + " of " + total);
            h.assertTrue(Math.abs(gold - total * 0.05) < total * 0.02, "about 5% gold: " + gold + " of " + total);
            // a vein is a cluster: an iron block usually has an iron neighbour
            int withNeighbour = 0, ironSeen = 0;
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!level.getBlockState(p).is(Blocks.IRON_ORE)) {
                            continue;
                        }
                        ironSeen++;
                        for (var d : net.minecraft.core.Direction.values()) {
                            if (level.getBlockState(p.relative(d)).is(Blocks.IRON_ORE)) {
                                withNeighbour++;
                                break;
                            }
                        }
                    }
                }
            }
            h.assertTrue(withNeighbour > ironSeen * 0.6, "ore comes in veins: " + withNeighbour + " of " + ironSeen + " iron blocks touch another");
            // a second plan from another seed differs
            byte[] first = planBytes(level, box, m, 1), second = planBytes(level, box, m, 2);
            int differ = 0;
            for (int i = 0; i < first.length; i++) {
                if (first[i] != second[i]) {
                    differ++;
                }
            }
            h.assertTrue(differ > first.length / 10, "a new seed gives a new layout: " + differ + " of " + first.length + " blocks differ");
        } finally {
            store(h).remove("mn_fill");
        }
        h.succeed();
    }

    private static byte[] planBytes(ServerLevel level, Cuboid box, Region m, long seed) {
        MineFiller.Job job = MineFiller.plan(level, box, MineData.fill(m), RandomSource.create(seed));
        // read the plan back by applying into a scratch copy: easier to compare block ids after applying
        byte[] out = new byte[(int) box.volume()];
        job.apply(Integer.MAX_VALUE);
        int i = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    var s = level.getBlockState(new BlockPos(x, y, z));
                    out[i++] = (byte) (s.is(Blocks.IRON_ORE) ? 1 : s.is(Blocks.GOLD_ORE) ? 2 : 0);
                }
            }
        }
        return out;
    }

    @GameTest(template = EMPTY)
    public void a_reset_moves_players_out_refills_and_schedules_the_next(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer digger = TestPlayers.mockServerPlayer(h);
        Region m = mine(h, "mn_reset", new BlockPos(2, 1, 2), new BlockPos(9, 5, 9));
        try {
            BlockPos in = h.absolutePos(new BlockPos(5, 3, 5));
            digger.setPos(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
            level.addNewPlayer(digger); // the mock is not in the world's player list until it is added
            h.assertTrue(Mines.playersInside(level, m).contains(digger), "the digger stands inside");
            Mines.schedule(store(h), m, 600);
            String msg = Mines.reset(level, store(h), m);
            h.assertTrue(msg.contains("resetting"), msg);
            h.assertFalse(Mines.playersInside(level, m).contains(digger), "the digger was moved out");
            h.assertTrue(digger.getY() >= m.bounds().maxY() + 1, "to above the mine (no spawn set)");
            h.assertTrue(Mines.busy(), "the refill is queued in batches");
            Mines.flush();
            h.assertTrue(count(h, m.bounds(), Blocks.AIR) == 0, "no air left inside after the refill");
            long left = Mines.secondsToReset(m);
            h.assertTrue(left > 590 && left <= 600, "the next reset is scheduled in about 600 s: " + left);

            // placing a block inside is refused, breaking allowed, for anyone
            Player stranger = h.makeMockPlayer();
            h.assertTrue(RegionQuery.allows(level, in, Flags.BLOCK_BREAK, stranger), "anyone may dig in the mine");
            h.assertFalse(RegionQuery.allows(level, in, Flags.BLOCK_PLACE, stranger), "nobody may place in the mine");
        } finally {
            Mines.clearAll();
            store(h).remove("mn_reset");
            level.removePlayerImmediately(digger, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_shell_lets_only_the_mine_be_dug(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Player digger = h.makeMockPlayer();
        Region m = mine(h, "mn_shell", new BlockPos(6, 2, 6), new BlockPos(12, 5, 12));
        try {
            Region shell = Mines.makeShell(level, store(h), m, 4);
            h.assertTrue(store(h).has("mn_shell_shell") && shell.bounds().minX() == m.bounds().minX() - 4, "shell made 4 blocks around");
            h.assertTrue(m.priority() > shell.priority(), "the mine outranks its shell");
            BlockPos inMine = h.absolutePos(new BlockPos(8, 3, 8)), inShell = h.absolutePos(new BlockPos(3, 3, 8)), outside = h.absolutePos(new BlockPos(20, 3, 20));
            for (BlockPos p : new BlockPos[]{inMine, inShell, outside}) {
                level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
            }
            BlockEvent.BreakEvent mineBreak = new BlockEvent.BreakEvent(level, inMine, level.getBlockState(inMine), digger);
            MinecraftForge.EVENT_BUS.post(mineBreak);
            h.assertFalse(mineBreak.isCanceled(), "digging in the mine is fine");
            BlockEvent.BreakEvent shellBreak = new BlockEvent.BreakEvent(level, inShell, level.getBlockState(inShell), digger);
            MinecraftForge.EVENT_BUS.post(shellBreak);
            h.assertTrue(shellBreak.isCanceled(), "digging the shell around the mine is refused");
            BlockEvent.BreakEvent farBreak = new BlockEvent.BreakEvent(level, outside, level.getBlockState(outside), digger);
            MinecraftForge.EVENT_BUS.post(farBreak);
            h.assertFalse(farBreak.isCanceled(), "outside the shell the world is unprotected as before");
            h.assertFalse(RegionQuery.allows(level, inShell, Flags.BLOCK_PLACE, digger), "nothing may be placed in the shell");
        } finally {
            store(h).remove("mn_shell_shell");
            store(h).remove("mn_shell");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void the_timer_resets_an_overdue_mine(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Region m = mine(h, "mn_timer", new BlockPos(1, 1, 1), new BlockPos(5, 3, 5));
        MineData.setIntervalSeconds(m, 300);
        MineData.setNextResetAt(m, System.currentTimeMillis() - 1000); // overdue
        store(h).touch(m);
        h.runAfterDelay(40, () -> { // the manager checks once a second
            try {
                Mines.flush();
                h.assertTrue(count(h, m.bounds(), Blocks.AIR) == 0, "the overdue mine was refilled by the timer");
                h.assertTrue(Mines.secondsToReset(m) > 290, "and rescheduled: " + Mines.secondsToReset(m));
            } finally {
                Mines.clearAll();
                store(h).remove("mn_timer");
            }
            h.succeed();
        });
    }
}
