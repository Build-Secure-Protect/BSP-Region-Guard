package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.Border;
import com.mrgregles.bsp_region_guard.command.RegionEdits;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.protection.WorldBorders;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The world border (D36 to D39). A border moves every player in the world, so these tests run in their own batch,
 * never beside the others.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class BorderGameTests {
    private static final String EMPTY = "empty", BATCH = "border";

    private static void at(GameTestHelper h, ServerPlayer p, double relX, double relZ) {
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        p.setPos(o.getX() + relX, o.getY() + 2, o.getZ() + relZ);
    }

    @GameTest(template = EMPTY, batch = BATCH)
    public void players_are_warned_then_pushed_back_and_admins_pass(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        // a 40-block square around the test's (0, 0): edges 20 blocks either way
        RegionEdits.Result set = RegionEdits.border(level, 40, o.getX(), o.getZ());
        Border b = WorldBorders.of(level);
        try {
            h.assertTrue(set.ok() && b != null && b.size() == 40, "border set: " + set.message());
            h.assertTrue(RegionStore.get(level).global().typeData().getInt(WorldBorders.SIZE) == 40, "it is kept on the world's global region");
            h.assertTrue(b.equals(com.mrgregles.bsp_region_guard.api.RegionGuardApi.get().regions().border(level)), "and the API reports it");
            h.assertTrue(com.mrgregles.bsp_region_guard.api.RegionGuardApi.get().regions().bounds(level, Region.GLOBAL_ID) == null, "the world default has no box");
            long t = 0;
            at(h, player, 0.5, 0.5);
            MovementTracker.tick(player, t++);
            h.assertFalse(MovementTracker.borderWarned(player), "no warning in the middle");
            at(h, player, 14.5, 0.5); // 5.5 blocks from the edge
            MovementTracker.tick(player, t++);
            h.assertTrue(MovementTracker.borderWarned(player), "warned within 10 blocks of the edge");

            at(h, player, 19.5, 0.5);
            MovementTracker.tick(player, t++);
            at(h, player, 20.6, 0.5); // a step over the edge
            MovementTracker.tick(player, t++);
            h.assertTrue(Math.abs(player.getX() - (o.getX() + 19.5)) < 0.01, "pushed back to where they stood: x " + (player.getX() - o.getX()));

            at(h, player, 60.5, -45.5); // far outside, as after a teleport
            MovementTracker.tick(player, t + 100);
            h.assertTrue(b.contains(player.getX(), player.getZ()) && b.toEdge(player.getX(), player.getZ()) >= 1,
                    "someone found outside is moved just inside: " + (player.getX() - o.getX()) + ", " + (player.getZ() - o.getZ()));

            h.assertFalse(RegionQuery.allows(level, o.offset(30, 2, 0), Flags.TELEPORT_IN, player), "teleports to outside the border are refused");
            h.assertTrue(RegionQuery.allows(level, o.offset(5, 2, 0), Flags.TELEPORT_IN, player), "and allowed inside");
            h.assertFalse(RegionQuery.allows(level, o.offset(30, 2, 0), Flags.SET_HOME, player), "no home outside either");

            at(h, admin, 0.5, 0.5);
            MovementTracker.tick(admin, t++);
            at(h, admin, 30.5, 0.5);
            MovementTracker.tick(admin, t++);
            h.assertTrue(Math.abs(admin.getX() - (o.getX() + 30.5)) < 0.01, "an admin with bypass on is not stopped");
            h.assertTrue(RegionQuery.allows(level, o.offset(30, 2, 0), Flags.TELEPORT_IN, admin), "nor refused a teleport");
        } finally {
            WorldBorders.set(level, null);
            BspCore.setAdminNames(new ArrayList<>(previousAdmins));
            MovementTracker.clearAll();
            player.discard();
            admin.discard();
        }
        h.assertTrue(WorldBorders.of(level) == null, "border removed");
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = BATCH)
    public void the_overworld_wall_follows_and_other_worlds_stay_unlimited(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.assertTrue(level.dimension() == net.minecraft.world.level.Level.OVERWORLD, "GameTests run in the Overworld");
        boolean wall = RegionGuardConfig.BORDER_VANILLA_WALL.get();
        RegionGuardConfig.BORDER_VANILLA_WALL.set(true);
        try {
            WorldBorders.set(level, new Border(100, -50, 2000));
            h.assertTrue(level.getWorldBorder().getSize() == 2000 && level.getWorldBorder().getCenterX() == 100 && level.getWorldBorder().getCenterZ() == -50,
                    "Minecraft's Overworld border matches: " + level.getWorldBorder().getSize());
            ServerLevel nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            if (nether != null) {
                h.assertTrue(nether.getWorldBorder().getSize() > 1.0E7, "the Nether's Minecraft border is lifted, not copied: " + nether.getWorldBorder().getSize());
            }
            Region global = RegionStore.get(level).global();
            h.assertTrue(global.isGlobal() && RegionStore.get(level).has(Region.GLOBAL_ID), "the global region exists");
        } finally {
            WorldBorders.set(level, null);
            RegionGuardConfig.BORDER_VANILLA_WALL.set(wall);
        }
        h.assertTrue(level.getWorldBorder().getSize() > 1.0E7, "switching the border off lifts the wall");
        h.succeed();
    }
}
