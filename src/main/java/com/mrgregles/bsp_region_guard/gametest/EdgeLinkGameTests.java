package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Border;
import com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent;
import com.mrgregles.bsp_region_guard.api.event.RemoteMessageEvent;
import com.mrgregles.bsp_region_guard.protection.EdgeLinks;
import com.mrgregles.bsp_region_guard.protection.EdgeLinks.Place;
import com.mrgregles.bsp_region_guard.protection.EdgeLinks.Side;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.protection.WorldBorders;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.storage.MemoryBackend;
import com.mrgregles.bsp_region_guard.storage.MirrorBackend;
import com.mrgregles.bsp_region_guard.storage.RegionMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Edge links between servers (D40 to D42): the geometry, two-way links read from another server's world default,
 * the travel question at a linked edge, the hand-over to BSP Teleport, and standing room on arrival. In the border
 * batch, because a border moves every player in the world.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class EdgeLinkGameTests {
    private static final String EMPTY = "empty", BATCH = "border";

    @GameTest(template = EMPTY, batch = BATCH)
    public void arrival_is_the_same_fraction_along_and_16_blocks_in(GameTestHelper h) {
        Border here = new Border(0, 0, 1000), there = new Border(0, 0, 4000);
        h.assertTrue(EdgeLinks.sideCrossed(here, 510, 0) == Side.EAST && EdgeLinks.sideCrossed(here, 0, -503) == Side.NORTH, "the side crossed");
        double f = EdgeLinks.fraction(here, Side.EAST, 500, -250);
        h.assertTrue(Math.abs(f - 0.25) < 1e-9, "a quarter of the way along the east edge, north to south: " + f);
        double[] at = EdgeLinks.arrival(there, Side.WEST, f);
        h.assertTrue(at[0] == -2000 + 16 && at[1] == -1000, "a quarter along the other world's west edge, 16 blocks in: " + at[0] + ", " + at[1]);
        double[] corner = EdgeLinks.arrival(there, Side.NORTH, 0);
        h.assertTrue(corner[0] == -2000 + 16 && corner[1] == -2000 + 16, "never closer than 16 blocks to a corner either");
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = BATCH)
    public void a_link_made_on_the_other_server_works_both_ways(GameTestHelper h) throws java.io.IOException {
        ServerLevel level = h.getLevel();
        String dim = level.dimension().location().toString();
        MemoryBackend backend = new MemoryBackend();
        RegionMirror alpha = RegionMirror.startWith(level.getServer(), backend, "alpha");
        RegionMirror.setActive(alpha);
        try {
            alpha.drain();
            WorldBorders.set(level, new Border(0, 0, 2000));
            // beta's world default, as beta would have stored it: a border, and its west edge linked to our east edge
            Region beta = Region.global(level.dimension());
            beta.typeData().putInt(WorldBorders.SIZE, 3000);
            CompoundTag links = new CompoundTag();
            links.putString("west", new Place("alpha", dim, Side.EAST).serialize());
            beta.typeData().put(EdgeLinks.KEY, links);
            backend.upsertRegion(new MirrorBackend.RegionRow("beta", dim, Region.GLOBAL_ID, 1, 1, RegionMirror.serialize(beta), false));
            EdgeLinks.refresh();
            alpha.drain();

            Map<Side, Place> mine = EdgeLinks.effective(level);
            h.assertTrue(new Place("beta", dim, Side.WEST).equals(mine.get(Side.EAST)), "beta's link shows as our east edge: " + mine);
            h.assertTrue(EdgeLinks.own(level).isEmpty(), "but it is beta's, not written here");
            var again = EdgeLinks.link(level, Side.EAST, new Place("beta", dim, Side.SOUTH));
            h.assertTrue(!again.ok() && again.message().contains("already leads"), "one link per edge: " + again.message());
            var sameTarget = EdgeLinks.link(level, Side.NORTH, new Place("beta", dim, Side.WEST));
            h.assertTrue(!sameTarget.ok(), "and one link per edge on the other side too: " + sameTarget.message());

            var north = EdgeLinks.link(level, Side.NORTH, new Place("beta", dim, Side.SOUTH));
            h.assertTrue(north.ok() && EdgeLinks.own(level).containsKey(Side.NORTH), "a new link is written on this server: " + north.message());
            var unknown = EdgeLinks.link(level, Side.SOUTH, new Place("gamma", dim, Side.NORTH));
            h.assertTrue(!unknown.ok(), "a world nobody has heard of cannot be linked");

            int before = backend.messages();
            var off = EdgeLinks.unlink(level, Side.EAST);
            alpha.drain();
            h.assertTrue(off.ok() && !EdgeLinks.effective(level).containsKey(Side.EAST) && backend.messages() > before,
                    "unlinking beta's link asks beta to drop it: " + off.message());
        } finally {
            WorldBorders.set(level, null);
            EdgeLinks.clearAll();
            com.mrgregles.bsp_region_guard.region.RegionStore.get(level).global().typeData().remove(EdgeLinks.KEY);
            RegionMirror.setActive(null);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = BATCH)
    public void a_linked_edge_asks_once_and_travel_goes_to_bsp_teleport(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String dim = level.dimension().location().toString();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        MemoryBackend backend = new MemoryBackend();
        RegionMirror alpha = RegionMirror.startWith(level.getServer(), backend, "alpha");
        RegionMirror.setActive(alpha);
        AtomicReference<RegionTriggerEvent> moved = new AtomicReference<>();
        Consumer<RegionTriggerEvent> teleportMod = e -> { // plays BSP Teleport: takes cross-server moves over
            if (e.crossServer()) {
                moved.set(e);
                e.setCanceled(true);
            }
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegionTriggerEvent.class, teleportMod);
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        try {
            alpha.drain();
            WorldBorders.set(level, new Border(o.getX(), o.getZ(), 40));
            EdgeLinks.setNetwork(List.of(new EdgeLinks.World("beta", dim, new Border(0, 0, 3000), Map.of())));
            h.assertTrue(EdgeLinks.link(level, Side.EAST, new Place("beta", dim, Side.WEST)).ok(), "linked");
            long t = 0;
            player.setPos(o.getX() + 0.5, o.getY() + 2, o.getZ() + 0.5);
            MovementTracker.tick(player, t++);
            player.setPos(o.getX() + 19.5, o.getY() + 2, o.getZ() + 10.5);
            MovementTracker.tick(player, t++);
            player.setPos(o.getX() + 20.7, o.getY() + 2, o.getZ() + 10.5); // over the east edge, three quarters along
            MovementTracker.tick(player, t++);
            int token = EdgeLinks.pendingToken(player);
            h.assertTrue(token != 0, "the travel question is asked");
            h.assertTrue(Math.abs(player.getX() - (o.getX() + 19.5)) < 0.01, "and the player is held at the edge");

            EdgeLinks.answer(player, token, false);
            player.setPos(o.getX() + 20.7, o.getY() + 2, o.getZ() + 10.5);
            MovementTracker.tick(player, t++);
            h.assertTrue(EdgeLinks.pendingToken(player) == 0 && moved.get() == null, "after STAY: pushed back, not asked again on this approach");

            player.setPos(o.getX() + 0.5, o.getY() + 2, o.getZ() + 0.5); // walks well away from the edge (out of the warning band)
            MovementTracker.tick(player, t++);
            player.setPos(o.getX() + 19.5, o.getY() + 2, o.getZ() + 10.5);
            MovementTracker.tick(player, t++);
            player.setPos(o.getX() + 20.7, o.getY() + 2, o.getZ() + 10.5);
            MovementTracker.tick(player, t++);
            int second = EdgeLinks.pendingToken(player);
            h.assertTrue(second != 0 && second != token, "coming back asks again");

            EdgeLinks.answer(player, second, true);
            alpha.drain();
            RegionTriggerEvent e = moved.get();
            h.assertTrue(e != null && e.cause() == RegionTriggerEvent.Cause.EDGE && "beta".equals(e.destinationServer()) && dim.equals(e.destinationWorld()),
                    "TRAVEL hands an EDGE move to the teleport mod");
            h.assertTrue(e.x() == -1500 + 16 && Math.abs(e.z() - (-1500 + 0.7625 * 3000)) < 1, "arriving 16 blocks inside beta's west edge, the same fraction along: "
                    + e.x() + ", " + e.z());
            h.assertTrue(backend.messages() >= 1, "and beta is told where to find standing room for the player");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(teleportMod);
            WorldBorders.set(level, null);
            com.mrgregles.bsp_region_guard.region.RegionStore.get(level).global().typeData().remove(EdgeLinks.KEY);
            EdgeLinks.clearAll();
            MovementTracker.clearAll();
            RegionMirror.setActive(null);
            player.discard();
        }
        h.succeed();
    }

    /**
     * Bug B1: in the Overworld Minecraft's own wall stands on the edge, so a player never gets past it on foot. The
     * question must come while they stand just inside, without moving them; admins with bypass are asked too.
     */
    @GameTest(template = EMPTY, batch = BATCH)
    public void a_linked_edge_asks_from_just_inside_the_wall(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String dim = level.dimension().location().toString();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previousAdmins = com.mrgregles.bsp_region_guard.compat.BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        RegionMirror alpha = RegionMirror.startWith(level.getServer(), new MemoryBackend(), "alpha");
        RegionMirror.setActive(alpha);
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        try {
            alpha.drain();
            WorldBorders.set(level, new Border(o.getX(), o.getZ(), 40));
            EdgeLinks.setNetwork(List.of(new EdgeLinks.World("beta", dim, new Border(0, 0, 3000), Map.of())));
            h.assertTrue(EdgeLinks.link(level, Side.NORTH, new Place("beta", dim, Side.SOUTH)).ok(), "linked");
            long t = 0;
            for (ServerPlayer p : List.of(player, admin)) {
                String who = p == admin ? "admin with bypass" : "player";
                p.setPos(o.getX() + 5.5, o.getY() + 2, o.getZ() + 0.5);
                MovementTracker.tick(p, t++);
                p.setPos(o.getX() + 5.5, o.getY() + 2, o.getZ() - 18.5); // 1.5 from the edge: not yet
                MovementTracker.tick(p, t++);
                h.assertTrue(EdgeLinks.pendingToken(p) == 0, who + ": not asked 1.5 blocks away");
                p.setPos(o.getX() + 5.5, o.getY() + 2, o.getZ() - 19.7); // against the wall, 0.3 inside
                MovementTracker.tick(p, t++);
                int token = EdgeLinks.pendingToken(p);
                h.assertTrue(token != 0, who + ": asked while standing against the wall");
                h.assertTrue(Math.abs(p.getZ() - (o.getZ() - 19.7)) < 0.01, who + ": and not moved");
                MovementTracker.tick(p, t++);
                h.assertTrue(EdgeLinks.pendingToken(p) == token, who + ": asked once, not every tick");
                EdgeLinks.answer(p, token, false);
            }
        } finally {
            com.mrgregles.bsp_region_guard.compat.BspCore.setAdminNames(new java.util.ArrayList<>(previousAdmins));
            WorldBorders.set(level, null);
            com.mrgregles.bsp_region_guard.region.RegionStore.get(level).global().typeData().remove(EdgeLinks.KEY);
            EdgeLinks.clearAll();
            MovementTracker.clearAll();
            RegionMirror.setActive(null);
            player.discard();
            admin.discard();
        }
        h.succeed();
    }

    /** Bug B2: the BORDER tab showed "around 0.0d, 0.0d"; numbers reach the screen plain. */
    @GameTest(template = EMPTY, batch = BATCH)
    public void the_screen_gets_plain_numbers(GameTestHelper h) {
        CompoundTag t = new CompoundTag();
        t.putDouble("x", 0.0);
        t.putLong("price", 500L);
        t.putInt("size", 100);
        t.putString("name", "spawn");
        h.assertTrue("0.0".equals(com.mrgregles.bsp_region_guard.network.RegionSync.dataText(t.get("x"))), "a double: " + t.get("x"));
        h.assertTrue("500".equals(com.mrgregles.bsp_region_guard.network.RegionSync.dataText(t.get("price"))), "a long");
        h.assertTrue("100".equals(com.mrgregles.bsp_region_guard.network.RegionSync.dataText(t.get("size"))), "an int");
        h.assertTrue("spawn".equals(com.mrgregles.bsp_region_guard.network.RegionSync.dataText(t.get("name"))), "text unchanged");
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = BATCH)
    public void an_arriving_traveller_is_put_on_standing_room(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String dim = level.dimension().location().toString();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        RegionMirror here = RegionMirror.startWith(level.getServer(), new MemoryBackend(), "beta");
        RegionMirror.setActive(here);
        BlockPos o = h.absolutePos(new BlockPos(3, 0, 3));
        try {
            double x = o.getX() + 0.5, z = o.getZ() + 0.5;
            MinecraftForge.EVENT_BUS.post(new RemoteMessageEvent("alpha", EdgeLinks.KIND_ARRIVAL, dim, Region.GLOBAL_ID,
                    player.getUUID() + ";beta;" + dim + ";" + x + ";" + z));
            h.assertTrue(EdgeLinks.expecting(player.getUUID()), "the traveller is expected");
            double landed = level.getMinBuildHeight() + 3;
            player.setPos(x, landed, z); // BSP Teleport landed them at the sending server's height, here deep underground
            MovementTracker.tick(player, 0);
            BlockPos feet = player.blockPosition();
            h.assertTrue(!EdgeLinks.expecting(player.getUUID()) && player.getY() != landed, "moved: y " + player.getY());
            h.assertTrue(level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP), "onto solid ground with room to stand");
        } finally {
            EdgeLinks.clearAll();
            MovementTracker.clearAll();
            RegionMirror.setActive(null);
            player.discard();
        }
        h.succeed();
    }

    /**
     * Bug B4: travellers sometimes landed underground. The test area itself lies deep underground in the GameTest
     * world, so it is a cave: a pillar with a gap in it there, a tree on the surface straight above, and an arrival
     * there must land on the surface beside the tree (the old search refused the trunk and searched from the
     * traveller's height, finding the cave).
     * Lava and water pools laid on the real surface are passed over for dry ground beside them.
     */
    @GameTest(template = EMPTY, batch = BATCH)
    public void arrivals_land_on_the_ground_never_in_a_cave(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var surfaceMap = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        net.minecraft.world.level.block.state.BlockState stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        for (int y = 1; y <= 8; y++) {
            h.setBlock(new BlockPos(2, y, 2), y == 3 || y == 4 ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : stone);
        }
        h.setBlock(new BlockPos(2, 9, 2), net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState());
        BlockPos pillar = h.absolutePos(new BlockPos(2, 0, 2));
        // and a small tree on the world's surface above it (the owner's test world is spruce forest): the old search
        // refused the trunk's top, capped by leaves, and fell back to the traveller's own height, into the cave
        BlockPos base = new BlockPos(pillar.getX(), level.getHeight(surfaceMap, pillar.getX(), pillar.getZ()), pillar.getZ());
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> saved = new java.util.HashMap<>();
        for (int y = 0; y <= 4; y++) {
            BlockPos p = base.above(y);
            saved.put(p, level.getBlockState(p));
            level.setBlock(p, (y < 4 ? net.minecraft.world.level.block.Blocks.SPRUCE_LOG : net.minecraft.world.level.block.Blocks.SPRUCE_LEAVES).defaultBlockState()
                    .trySetValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
        }
        try {
            net.minecraft.world.phys.Vec3 spot = WorldBorders.groundSpot(level, null, pillar.getX() + 0.5, pillar.getZ() + 0.5, h.absolutePos(new BlockPos(2, 3, 2)).getY());
            BlockPos feet = BlockPos.containing(spot);
            h.assertTrue(feet.getY() >= level.getHeight(surfaceMap, feet.getX(), feet.getZ()) - 1 && feet.getY() > h.absolutePos(new BlockPos(2, 10, 2)).getY(),
                    "on the world's surface, not in the cave below: " + spot);
            h.assertTrue(!(feet.getX() == base.getX() && feet.getZ() == base.getZ()) && Math.abs(feet.getX() - base.getX()) <= WorldBorders.GROUND_SEARCH,
                    "beside the tree, not on top of it: " + spot);
        } finally {
            saved.forEach((p, st) -> level.setBlock(p, st, 2));
        }

        // pools laid on the real surface next to the test: the traveller is put on dry ground beside them
        net.minecraft.world.level.block.state.BlockState[] pools = {net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState(), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()};
        for (int i = 0; i < pools.length; i++) {
            BlockPos col = h.absolutePos(new BlockPos(6 + 4 * i, 0, 6));
            BlockPos poolPos = new BlockPos(col.getX(), level.getHeight(surfaceMap, col.getX(), col.getZ()), col.getZ());
            net.minecraft.world.level.block.state.BlockState before = level.getBlockState(poolPos);
            level.setBlock(poolPos, pools[i], 2);
            try {
                net.minecraft.world.phys.Vec3 s2 = WorldBorders.groundSpot(level, null, col.getX() + 0.5, col.getZ() + 0.5, h.absolutePos(new BlockPos(6, 2, 6)).getY());
                BlockPos feet = BlockPos.containing(s2), below = feet.below();
                String what = pools[i].getBlock().getName().getString();
                h.assertTrue(!(Math.floor(s2.x) == col.getX() && Math.floor(s2.z) == col.getZ()), "not on the " + what + ": " + s2);
                h.assertTrue(Math.abs(s2.x - col.getX()) <= WorldBorders.GROUND_SEARCH + 1 && Math.abs(s2.z - col.getZ()) <= WorldBorders.GROUND_SEARCH + 1, "but close by");
                h.assertTrue(level.getBlockState(below).getFluidState().isEmpty() && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                        && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty(), "on dry, solid ground beside the " + what + ": " + level.getBlockState(below));
                h.assertTrue(feet.getY() >= level.getHeight(surfaceMap, feet.getX(), feet.getZ()) - 1, "on the surface beside the " + what);
            } finally {
                level.setBlock(poolPos, before, 2);
            }
        }
        h.succeed();
    }

    /** The BORDER tab's actions reach the same code as /rg border (D42). */
    @GameTest(template = EMPTY, batch = BATCH)
    public void the_border_tab_sets_the_border_and_links_edges(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String dim = level.dimension().location().toString();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        RegionMirror alpha = RegionMirror.startWith(level.getServer(), new MemoryBackend(), "alpha");
        RegionMirror.setActive(alpha);
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        try {
            alpha.drain();
            com.mrgregles.bsp_region_guard.network.RegionSync.apply(admin, new com.mrgregles.bsp_region_guard.network.RegionActionPacket(
                    com.mrgregles.bsp_region_guard.network.RegionActionPacket.BORDER_SET, Region.GLOBAL_ID, "400", o.getX() + " " + o.getZ()));
            Border b = WorldBorders.of(level);
            h.assertTrue(b != null && b.size() == 400 && b.centerX() == o.getX(), "SET from the tab: " + b);
            EdgeLinks.setNetwork(List.of(new EdgeLinks.World("beta", dim, new Border(0, 0, 3000), Map.of())));
            String linked = com.mrgregles.bsp_region_guard.network.RegionSync.apply(admin, new com.mrgregles.bsp_region_guard.network.RegionActionPacket(
                    com.mrgregles.bsp_region_guard.network.RegionActionPacket.EDGE_LINK, Region.GLOBAL_ID, "east", "beta|" + dim + "|west"));
            h.assertTrue(new Place("beta", dim, Side.WEST).equals(EdgeLinks.own(level).get(Side.EAST)), "picking a world links the edge: " + linked);
            var free = EdgeLinks.freeSides(level, EdgeLinks.network().get(0));
            h.assertTrue(!free.contains(Side.WEST) && free.size() == 3, "and that edge of beta is no longer offered: " + free);
            com.mrgregles.bsp_region_guard.network.RegionSync.apply(admin, new com.mrgregles.bsp_region_guard.network.RegionActionPacket(
                    com.mrgregles.bsp_region_guard.network.RegionActionPacket.EDGE_UNLINK, Region.GLOBAL_ID, "east", ""));
            h.assertTrue(EdgeLinks.own(level).isEmpty(), "x unlinks it");
            com.mrgregles.bsp_region_guard.network.RegionSync.apply(admin, new com.mrgregles.bsp_region_guard.network.RegionActionPacket(
                    com.mrgregles.bsp_region_guard.network.RegionActionPacket.BORDER_OFF, Region.GLOBAL_ID, "", ""));
            h.assertTrue(WorldBorders.of(level) == null, "and off removes the border");
        } finally {
            WorldBorders.set(level, null);
            com.mrgregles.bsp_region_guard.region.RegionStore.get(level).global().typeData().remove(EdgeLinks.KEY);
            EdgeLinks.clearAll();
            RegionMirror.setActive(null);
            admin.discard();
        }
        h.succeed();
    }

    /** Bug B5: a network of 55 worlds pushed linkable ones past the screen's 50-entry list. Only bordered worlds are sent, sorted. */
    @GameTest(template = EMPTY, batch = BATCH)
    public void every_bordered_world_reaches_the_border_tab_however_many_worlds_there_are(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<EdgeLinks.World> worlds = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            worlds.add(new EdgeLinks.World("srv" + i, "mod:dim" + i, null, Map.of()));
        }
        worlds.add(new EdgeLinks.World("zz-last", "minecraft:overworld", new Border(0, 0, 3000), Map.of()));
        EdgeLinks.setNetwork(worlds);
        try {
            Map<String, String> data = new java.util.LinkedHashMap<>();
            com.mrgregles.bsp_region_guard.network.RegionSync.borderData(level, data);
            long nets = data.keySet().stream().filter(k -> k.startsWith("net.")).count();
            h.assertTrue(nets == 1 && data.get("net.1").startsWith("zz-last|minecraft:overworld|3000|"), "only the bordered world is sent, whatever its place: " + data);
            h.assertTrue(EdgeLinks.network().get(0).server().equals("srv0"), "the network is listed in a fixed order");
        } finally {
            EdgeLinks.clearAll();
        }
        h.succeed();
    }
}
