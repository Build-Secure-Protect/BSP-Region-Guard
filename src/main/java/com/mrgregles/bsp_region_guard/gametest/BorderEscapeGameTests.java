package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Border;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.command.RegionEdits;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.protection.Protection;
import com.mrgregles.bsp_region_guard.protection.WorldBorders;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Ways past the world border the owner found in game (bugs B25 to B27). Each test sets a 40-block border around the
 * test's (0, 0), so the edges are 20 blocks away; they share the border batch so no other test runs beside them.
 *
 * <p>The movement tests run the player's real connection tick ({@code connection.tick()}), not just the tracker: the
 * escape in B25 came from what Minecraft does around the player's tick.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class BorderEscapeGameTests {
    private static final String EMPTY = "empty", BATCH = "border";

    private interface Body {
        void run(ServerLevel level, BlockPos o, Border b);
    }

    private static void withBorder(GameTestHelper h, Body body, ServerPlayer... players) {
        ServerLevel level = h.getLevel();
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        RegionEdits.border(level, 40, o.getX(), o.getZ());
        try {
            body.run(level, o, WorldBorders.of(level));
        } finally {
            WorldBorders.set(level, null);
            MovementTracker.clearAll();
            for (ServerPlayer p : players) {
                p.discard();
            }
        }
        h.succeed();
    }

    private static String rel(ServerPlayer p, BlockPos o) {
        return String.format("x %.2f, z %.2f", p.getX() - o.getX(), p.getZ() - o.getZ());
    }

    @GameTest(template = EMPTY, batch = BATCH)
    public void a_push_back_from_the_border_sticks_on_the_server(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        withBorder(h, (level, o, b) -> {
            player.setPos(o.getX() + 18.5, o.getY() + 2, o.getZ() + 0.5);
            player.connection.tick(); // the tracker records where they stand
            h.assertTrue(b.contains(player.getX(), player.getZ()), "starts inside: " + rel(player, o));
            // a mod moves the player 7 blocks past the edge without any teleport event (Travel Anchors with its event
            // switched off does exactly this)
            player.connection.teleport(o.getX() + 25.5, o.getY() + 2, o.getZ() + 0.5, player.getYRot(), player.getXRot());
            h.assertFalse(b.contains(player.getX(), player.getZ()), "the mod put them outside: " + rel(player, o));
            player.connection.tick();
            // before the fix Minecraft put the server's copy back at the tick's starting point (outside) right after
            // Region Guard's push-back, so this read x 25.50
            h.assertTrue(b.contains(player.getX(), player.getZ()), "back inside after one tick and it stays: " + rel(player, o));
            player.connection.tick();
            h.assertTrue(b.contains(player.getX(), player.getZ()), "still inside a tick later: " + rel(player, o));
        }, player);
    }

    @GameTest(template = EMPTY, batch = BATCH)
    public void a_boat_pushed_at_the_border_is_reset_in_the_drivers_game_and_getting_out_stays_inside(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        List<Boat> boats = new ArrayList<>();
        withBorder(h, (level, o, b) -> {
            Boat boat = EntityType.BOAT.create(level);
            boats.add(boat);
            boat.setPos(o.getX() + 18.5, o.getY() + 2, o.getZ() + 0.5);
            level.addFreshEntity(boat);
            h.assertTrue(player.startRiding(boat, true), "the player gets in");
            player.connection.tick();
            TestPlayers.sentPackets(player); // drop what was sent so far
            // the driver's game paddles the boat over the edge and the server accepts it (it trusts the driver)
            boat.absMoveTo(o.getX() + 20.8, boat.getY(), o.getZ() + 0.5, boat.getYRot(), boat.getXRot());
            boat.positionRider(player);
            player.connection.tick();
            h.assertTrue(b.contains(boat.getX(), boat.getZ()), "the boat is put back inside: " + String.format("x %.2f", boat.getX() - o.getX()));
            h.assertTrue(player.getVehicle() == boat, "the rider stays in it");
            boolean reset = TestPlayers.sentPackets(player).stream().anyMatch(p -> p instanceof ClientboundMoveVehiclePacket);
            h.assertTrue(reset, "the driver's game is told where the boat is (before the fix it kept the boat outside)");
            // getting out where the driver's boat still was: Minecraft sets the spot without telling the player's game
            player.stopRiding();
            player.setPos(o.getX() + 21.6, player.getY(), o.getZ() + 0.5);
            player.connection.tick();
            h.assertTrue(b.contains(player.getX(), player.getZ()), "on foot, back inside: " + rel(player, o));
            player.connection.tick();
            h.assertTrue(b.contains(player.getX(), player.getZ()), "and it stays: " + rel(player, o));
        }, player);
        boats.forEach(Boat::discard);
    }

    @GameTest(template = EMPTY, batch = BATCH)
    public void teleports_that_would_land_past_the_border_are_refused(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        try {
            withBorder(h, (level, o, b) -> {
                player.setPos(o.getX() + 17.5, o.getY() + 2, o.getZ() + 0.5);
                admin.setPos(o.getX() + 17.5, o.getY() + 2, o.getZ() + 0.5);
                // Travel Anchors' Teleportation enchantment fires the plain teleport event before it moves anyone
                EntityTeleportEvent out = new EntityTeleportEvent(player, o.getX() + 24.5, o.getY() + 2, o.getZ() + 0.5);
                MinecraftForge.EVENT_BUS.post(out);
                h.assertTrue(out.isCanceled(), "a short-range teleport past the edge is refused");
                EntityTeleportEvent in = new EntityTeleportEvent(player, o.getX() + 12.5, o.getY() + 2, o.getZ() + 0.5);
                MinecraftForge.EVENT_BUS.post(in);
                h.assertFalse(in.isCanceled(), "one that stays inside goes ahead");
                EntityTeleportEvent.ChorusFruit chorus = new EntityTeleportEvent.ChorusFruit(player, o.getX() + 0.5, o.getY() + 2, o.getZ() - 27.5);
                MinecraftForge.EVENT_BUS.post(chorus);
                h.assertTrue(chorus.isCanceled(), "the rule covers every kind of teleport event (chorus fruit here)");
                EntityTeleportEvent.TeleportCommand tp = new EntityTeleportEvent.TeleportCommand(player, o.getX() + 24.5, o.getY() + 2, o.getZ() + 0.5);
                MinecraftForge.EVENT_BUS.post(tp);
                h.assertFalse(tp.isCanceled(), "/tp is left to the border's tick: its event doesn't say which world it lands in");
                EntityTeleportEvent byAdmin = new EntityTeleportEvent(admin, o.getX() + 24.5, o.getY() + 2, o.getZ() + 0.5);
                MinecraftForge.EVENT_BUS.post(byAdmin);
                h.assertFalse(byAdmin.isCanceled(), "an admin with bypass on may");
            }, player, admin);
        } finally {
            BspCore.setAdminNames(new ArrayList<>(previousAdmins));
        }
    }

    @GameTest(template = EMPTY, batch = BATCH)
    public void nothing_past_the_border_can_be_broken_by_any_tool(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        RegionStore store = RegionStore.get(h.getLevel());
        try {
            withBorder(h, (level, o, b) -> {
                player.setPos(o.getX() + 18.5, o.getY() + 2, o.getZ() + 0.5);
                BlockPos past = o.offset(22, 1, 0), past2 = o.offset(23, 1, 0), near = o.offset(18, 1, 0);
                level.setBlockAndUpdate(past, Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(past2, Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(near, Blocks.STONE.defaultBlockState());
                // the Mining Gadget fires the break event with the real player for each block before it starts on it
                BlockEvent.BreakEvent gadget = new BlockEvent.BreakEvent(level, past, level.getBlockState(past), player);
                MinecraftForge.EVENT_BUS.post(gadget);
                h.assertTrue(gadget.isCanceled(), "a block past the edge cannot be broken (Mining Gadget)");
                BlockEvent.BreakEvent inside = new BlockEvent.BreakEvent(level, near, level.getBlockState(near), player);
                MinecraftForge.EVENT_BUS.post(inside);
                h.assertFalse(inside.isCanceled(), "one inside can");
                h.assertFalse(Protection.mayPlayer(level, past, Flags.BLOCK_PLACE, player), "nor can anything be placed past it");
                BlockEvent.BreakEvent byAdmin = new BlockEvent.BreakEvent(level, past, level.getBlockState(past), admin);
                MinecraftForge.EVENT_BUS.post(byAdmin);
                h.assertFalse(byAdmin.isCanceled(), "an admin with bypass on may");
                // Just Hammers breaks the blocks around the mined one with Level.destroyBlock and no break event
                h.assertFalse(level.destroyBlock(past, false, player), "a hammer's extra block past the edge is refused");
                h.assertTrue(level.getBlockState(past).is(Blocks.STONE), "and the block is still there");
                h.assertTrue(level.destroyBlock(past2, false, admin), "an admin's hammer may");
                h.assertTrue(level.destroyBlock(near, false, player), "inside, with no region, it breaks");
                // the same gap let hammers break into protected regions: the extra blocks now follow block-break too
                Region r = new Region("b26_hammer", level.dimension(), Cuboid.of(o.offset(2, 1, 2), o.offset(6, 4, 6)), RegionType.PROTECTED);
                store.add(r);
                BlockPos claimed = o.offset(4, 2, 4);
                level.setBlockAndUpdate(claimed, Blocks.STONE.defaultBlockState());
                h.assertFalse(level.destroyBlock(claimed, false, player), "a hammer's extra block in a protected region is refused");
                h.assertTrue(level.getBlockState(claimed).is(Blocks.STONE), "and stays");
                h.assertTrue(level.destroyBlock(claimed, false, null), "breaks with no player behind them are left alone");
            }, player, admin);
        } finally {
            ServerLevel level = h.getLevel();
            BlockPos o = h.absolutePos(BlockPos.ZERO);
            for (BlockPos p : new BlockPos[]{o.offset(22, 1, 0), o.offset(23, 1, 0), o.offset(18, 1, 0), o.offset(4, 2, 4)}) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState()); // the blocks past the edge are outside the test's own area
            }
            store.remove("b26_hammer");
            BspCore.setAdminNames(new ArrayList<>(previousAdmins));
        }
    }
}
