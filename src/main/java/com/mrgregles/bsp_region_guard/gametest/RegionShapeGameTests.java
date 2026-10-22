package com.mrgregles.bsp_region_guard.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.api.RegionGuardApi;
import com.mrgregles.bsp_region_guard.claim.PlotMap;
import com.mrgregles.bsp_region_guard.command.RegionCommands;
import com.mrgregles.bsp_region_guard.command.RegionEdits;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.network.PlotMapActionPacket;
import com.mrgregles.bsp_region_guard.network.PlotMapStatePacket;
import com.mrgregles.bsp_region_guard.network.PlotMapSync;
import com.mrgregles.bsp_region_guard.network.RegionActionPacket;
import com.mrgregles.bsp_region_guard.network.RegionSync;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import com.mrgregles.bsp_region_guard.selection.Selections;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Regions without corners, vertical fill, typed corners and corners clicked on the plot map (B6, B7; D71 to D79). */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class RegionShapeGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    /** A box in test-relative coordinates, two blocks high. */
    private static Cuboid box(GameTestHelper h, int x, int z, int w, int d) {
        return Cuboid.of(h.absolutePos(new BlockPos(x, 1, z)), h.absolutePos(new BlockPos(x + w - 1, 2, z + d - 1)));
    }

    private interface Body {
        void run(ServerPlayer admin);
    }

    /** Runs {@code body} with a BSP-Core admin standing in the test, then removes the regions it made. */
    private static void run(GameTestHelper h, Body body, String... ids) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        admin.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        List<? extends String> previous = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        try {
            body.run(admin);
        } finally {
            BspCore.setAdminNames(new ArrayList<>(previous));
            for (String id : ids) {
                if (store(h).has(id)) {
                    store(h).remove(id);
                }
            }
            PlotMap.forget(admin.getUUID());
            Selections.forget(admin.getUUID());
            admin.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_region_without_corners_protects_nothing(GameTestHelper h) {
        run(h, admin -> {
            ServerLevel level = h.getLevel();
            RegionEdits.Result made = RegionEdits.create(level, "rs_none", null, 50);
            Region r = store(h).get("rs_none");
            h.assertTrue(made.ok() && r != null && !r.hasCorners() && !r.isGlobal(), "made without corners, not the world default: " + made.message());
            r.setFlag(Flags.BUILD, State.DENY);
            r.setFlag(Flags.PVP, State.DENY);
            store(h).touch(r);
            for (BlockPos p : new BlockPos[]{h.absolutePos(new BlockPos(2, 1, 2)), new BlockPos(0, 64, 0), new BlockPos(0, level.getMinBuildHeight(), 0),
                    Region.NO_CORNERS.min()}) {
                h.assertTrue(RegionQuery.at(level, p).stream().noneMatch(x -> x == r), "applies nowhere, also not at " + p.toShortString());
            }
            h.assertTrue(RegionGuardApi.get().regions().bounds(level, "rs_none") == null, "the API gives no box");

            CompoundTag saved = r.save();
            h.assertTrue(saved.contains("bounds"), "saved with a box, so an older Region Guard never takes it for the world default");
            Region back = Region.load(saved);
            h.assertTrue(!back.hasCorners() && !back.isGlobal() && back.priority() == 50 && back.flag(Flags.BUILD) == State.DENY, "loads back without corners");
        }, "rs_none");
    }

    @GameTest(template = EMPTY)
    public void a_region_without_corners_refuses_what_needs_a_place(GameTestHelper h) {
        run(h, admin -> {
            ServerLevel level = h.getLevel();
            RegionStore store = store(h);
            RegionEdits.create(level, "rs_wait", null, 0);
            Region r = store.get("rs_wait");
            h.assertFalse(RegionEdits.teleport(admin, store, r).ok(), "no teleport");
            h.assertFalse(RegionEdits.sale(level, store, r, 1000).ok(), "not for sale");
            h.assertFalse(RegionEdits.open(level, store, r, 10).ok(), "not open for buying");
            h.assertFalse(RegionEdits.makeMine(store, r).ok(), "not a mine");
            for (String type : new String[]{"claim", "plot_for_sale", "open_for_buying", "mine"}) {
                h.assertFalse(RegionEdits.type(level, store, r, type).ok(), "not a " + type);
            }
            h.assertTrue(r.type() == RegionType.PROTECTED, "still protected");
            h.assertFalse(RegionEdits.selectBounds(admin, r).ok(), "nothing to select");
            h.assertFalse(RegionEdits.verticalFill(level, store, r).ok(), "nothing to fill");
            h.assertTrue(RegionEdits.flag(store, r, Flags.GREETING, "Soon", new com.mrgregles.bsp_region_guard.flag.FlagContext(level, admin)).ok() && RegionEdits.rename(store, r, "Harbour").ok(),
                    "flags and names can be set before the corners");
        }, "rs_wait");
    }

    @GameTest(template = EMPTY)
    public void vertical_fill_spans_the_whole_world_height(GameTestHelper h) {
        run(h, admin -> {
            ServerLevel level = h.getLevel();
            RegionStore store = store(h);
            Cuboid b = box(h, 1, 1, 4, 3);
            RegionEdits.create(level, "rs_tall", b, 0);
            RegionEdits.Result filled = RegionEdits.verticalFill(level, store, store.get("rs_tall"));
            Cuboid now = store.get("rs_tall").bounds();
            h.assertTrue(filled.ok() && now.minY() == level.getMinBuildHeight() && now.maxY() == level.getMaxBuildHeight() - 1
                    && now.minX() == b.minX() && now.maxX() == b.maxX() && now.minZ() == b.minZ() && now.maxZ() == b.maxZ(),
                    "bottom to top, same ground: " + filled.message());
            BlockPos high = new BlockPos(b.minX(), level.getMaxBuildHeight() - 1, b.minZ());
            h.assertTrue(RegionQuery.at(level, high).stream().anyMatch(x -> x.id().equals("rs_tall")), "found at the top of the world");
            h.assertFalse(RegionEdits.verticalFill(level, store, store.global()).ok(), "the world default is refused");

            h.assertFalse(RegionEdits.verticalFillSelection(admin).ok(), "no selection, nothing to stretch");
            Selections.of(admin).set(level.dimension(), box(h, 2, 2, 2, 2));
            h.assertTrue(RegionEdits.verticalFillSelection(admin).ok(), "the selection stretches");
            Cuboid sel = RegionEdits.selectionIn(admin);
            h.assertTrue(sel != null && sel.minY() == level.getMinBuildHeight() && sel.maxY() == level.getMaxBuildHeight() - 1, "selection is full height");
        }, "rs_tall");
    }

    @GameTest(template = EMPTY)
    public void typed_corners_resize_a_region_and_leave_its_old_spot(GameTestHelper h) {
        run(h, admin -> {
            ServerLevel level = h.getLevel();
            RegionStore store = store(h);
            Cuboid first = box(h, 1, 1, 3, 3);
            RegionEdits.create(level, "rs_size", first, 0);
            BlockPos oldSpot = first.min();
            BlockPos a = h.absolutePos(new BlockPos(6, 1, 6)), b = h.absolutePos(new BlockPos(9, 3, 8));
            String msg = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.RESIZE, "rs_size",
                    b.getX() + " " + b.getY() + " " + b.getZ() + " " + a.getX() + " " + a.getY() + " " + a.getZ(), ""));
            Region r = store.get("rs_size");
            h.assertTrue(r.bounds().equals(Cuboid.of(a, b)), "the screen's six numbers, corners in any order: " + msg);
            h.assertTrue(RegionQuery.at(level, oldSpot).stream().noneMatch(x -> x == r), "gone from its old spot");
            h.assertTrue(RegionQuery.at(level, a).stream().anyMatch(x -> x == r), "found at its new spot");

            String bad = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.RESIZE, "rs_size", "1 2 three 4 5 6", ""));
            h.assertTrue(bad != null && r.bounds().equals(Cuboid.of(a, b)), "text that is not six numbers changes nothing");
            String high = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.RESIZE, "rs_size",
                    a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + (level.getMaxBuildHeight() + 5) + " " + b.getZ(), ""));
            h.assertTrue(high != null && r.bounds().equals(Cuboid.of(a, b)), "above the world is refused: " + high);

            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.VFILL, "rs_size", "", ""));
            h.assertTrue(r.bounds().minY() == level.getMinBuildHeight() && r.bounds().maxY() == level.getMaxBuildHeight() - 1, "VERTICAL FILL from the screen");
        }, "rs_size");
    }

    @GameTest(template = EMPTY)
    public void the_commands_make_regions_without_corners_type_corners_and_fill(GameTestHelper h) {
        run(h, admin -> {
            ServerLevel level = h.getLevel();
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            RegionCommands.register(dispatcher);
            CommandSourceStack src = admin.createCommandSourceStack().withSuppressedOutput();
            try {
                h.assertTrue(dispatcher.execute("rg create rs_cmd", src) == 1, "create without a selection");
                Region r = store(h).get("rs_cmd");
                h.assertTrue(r != null && !r.hasCorners(), "without corners");
                h.assertTrue(dispatcher.execute("rg define rs_cmd2 4", src) == 1 && !store(h).get("rs_cmd2").hasCorners(), "define does the same");
                BlockPos a = h.absolutePos(new BlockPos(1, 1, 1)), b = h.absolutePos(new BlockPos(5, 2, 4));
                h.assertTrue(dispatcher.execute("rg redefine rs_cmd " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + b.getY() + " " + b.getZ(), src) == 1
                        && r.bounds().equals(Cuboid.of(a, b)), "typed corners");
                h.assertTrue(dispatcher.execute("rg vfill rs_cmd", src) == 1 && r.bounds().minY() == level.getMinBuildHeight(), "vfill a region");
                Selections.of(admin).set(level.dimension(), Cuboid.of(a, b));
                h.assertTrue(dispatcher.execute("rg vfill", src) == 1 && RegionEdits.selectionIn(admin).maxY() == level.getMaxBuildHeight() - 1, "vfill the selection");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                h.fail("command failed: " + e.getMessage());
            }
        }, "rs_cmd", "rs_cmd2");
    }

    @GameTest(template = EMPTY)
    public void new_without_a_selection_asks_for_corners_on_the_plot_map(GameTestHelper h) {
        run(h, admin -> {
            ServerLevel level = h.getLevel();
            String msg = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.CREATE, "", "rs_map", ""));
            Region r = store(h).get("rs_map");
            h.assertTrue(r != null && !r.hasCorners(), "+ NEW made it without corners: " + msg);

            PlotMapStatePacket asked = PlotMapSync.cornersState(admin, "rs_map", "Click the first corner of rs_map.");
            h.assertTrue(asked.open() && asked.editable() && asked.corners().equals("rs_map") && asked.bottom() == level.getMinBuildHeight()
                    && asked.top() == level.getMaxBuildHeight() - 1, "the plot map opens waiting for rs_map's corners, with the world's height");
            h.assertTrue(asked.plots().stream().noneMatch(p -> p.id().equals("rs_map")) && asked.others().stream().noneMatch(p -> p.id().equals("rs_map")),
                    "a region without corners is not drawn");
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            PlotMapStatePacket.encode(asked, buf);
            PlotMapStatePacket back = PlotMapStatePacket.decode(buf);
            h.assertTrue(back.corners().equals("rs_map") && back.top() == asked.top() && back.others().equals(asked.others()), "the packet arrives intact");

            Cuboid clicked = new Cuboid(h.absolutePos(new BlockPos(1, 0, 1)).getX(), level.getMinBuildHeight(), h.absolutePos(new BlockPos(1, 0, 1)).getZ(),
                    h.absolutePos(new BlockPos(6, 0, 4)).getX(), level.getMaxBuildHeight() - 1, h.absolutePos(new BlockPos(6, 0, 4)).getZ());
            PlotMapStatePacket set = PlotMapSync.apply(admin, corners("rs_map", clicked));
            h.assertTrue(set.ok() && r.bounds().equals(clicked) && set.undo() == 1, "corners set, one step to undo: " + set.message());
            h.assertTrue(RegionQuery.at(level, h.absolutePos(new BlockPos(2, 1, 2))).stream().anyMatch(x -> x == r), "it applies now");
            h.assertTrue(set.others().stream().anyMatch(p -> p.id().equals("rs_map")), "drawn as an outline among the other regions");

            h.assertTrue(PlotMap.undo(admin).ok() && !r.hasCorners(), "undo takes the corners back");
            h.assertTrue(RegionQuery.at(level, h.absolutePos(new BlockPos(2, 1, 2))).stream().noneMatch(x -> x == r), "and it applies nowhere again");
            h.assertTrue(PlotMap.redo(admin).ok() && r.bounds().equals(clicked), "redo puts them back");

            Cuboid tooHigh = new Cuboid(clicked.minX(), clicked.minY(), clicked.minZ(), clicked.maxX(), level.getMaxBuildHeight() + 10, clicked.maxZ());
            h.assertFalse(PlotMapSync.apply(admin, corners("rs_map", tooHigh)).ok(), "above the world is refused");
            h.assertFalse(PlotMapSync.apply(admin, corners(Region.GLOBAL_ID, clicked)).ok(), "the world default has no corners");

            ServerPlayer stranger = TestPlayers.mockServerPlayer(h);
            h.assertFalse(PlotMapSync.apply(stranger, corners("rs_map", box(h, 8, 8, 2, 2))).ok() || !r.bounds().equals(clicked), "a non-admin is refused");
            stranger.discard();
        }, "rs_map");
    }

    @GameTest(template = EMPTY)
    public void corners_of_a_plot_keep_to_the_overlap_rule(GameTestHelper h) {
        run(h, admin -> {
            ServerLevel level = h.getLevel();
            RegionStore store = store(h);
            Region sale = new Region("rs_plot_a", level.dimension(), box(h, 1, 1, 3, 3), RegionType.PLOT_FOR_SALE);
            store.add(sale);
            Region moving = new Region("rs_plot_b", level.dimension(), box(h, 8, 1, 3, 3), RegionType.PLOT_FOR_SALE);
            store.add(moving);
            h.assertFalse(PlotMap.setCorners(admin, "rs_plot_b", box(h, 2, 2, 4, 4)).ok(), "a plot may not partly overlap another plot (D59)");
            h.assertTrue(moving.bounds().equals(box(h, 8, 1, 3, 3)), "unchanged");
            RegionEdits.create(level, "rs_prot", null, 0);
            h.assertTrue(PlotMap.setCorners(admin, "rs_prot", box(h, 0, 0, 6, 6)).ok(), "a protected region may lie over plots (D78)");
            store.get("rs_prot").setType(RegionType.MINE);
            h.assertFalse(PlotMap.undo(admin).ok() || !store.get("rs_prot").hasCorners(), "a mine keeps its corners: undo refuses to take them away");
        }, "rs_plot_a", "rs_plot_b", "rs_prot");
    }

    /** B10: the plot map lists every region with corners (plots, protected areas, mines), never the world default or one without corners. */
    @GameTest(template = EMPTY)
    public void the_plot_map_lists_every_region(GameTestHelper h) {
        run(h, admin -> {
            ServerLevel level = h.getLevel();
            RegionEdits.create(level, "rs_l_prot", box(h, 1, 1, 3, 3), 2);
            RegionEdits.create(level, "rs_l_mine", box(h, 6, 1, 3, 3), 0);
            store(h).get("rs_l_mine").setType(RegionType.MINE);
            RegionEdits.create(level, "rs_l_none", null, 0);
            Region sale = new Region("rs_l_sale", level.dimension(), box(h, 1, 6, 3, 3), RegionType.PLOT_FOR_SALE);
            store(h).add(sale);
            PlotMapStatePacket view = PlotMapSync.apply(admin, PlotMapActionPacket.simple(PlotMapActionPacket.VIEW, level.dimension().location().toString(), true));
            h.assertTrue(view.others().stream().anyMatch(p -> p.id().equals("rs_l_prot") && p.type().equals("protected") && p.priority() == 2),
                    "a protected region is listed with its type and priority");
            h.assertTrue(view.others().stream().anyMatch(p -> p.id().equals("rs_l_mine") && p.type().equals("mine")), "a mine is listed");
            h.assertTrue(view.plots().stream().anyMatch(p -> p.id().equals("rs_l_sale")) && view.others().stream().noneMatch(p -> p.id().equals("rs_l_sale")),
                    "plots stay in the plots list");
            h.assertTrue(view.others().stream().noneMatch(p -> p.id().equals("rs_l_none") || p.id().equals(Region.GLOBAL_ID)),
                    "neither a region without corners nor the world default");
            h.assertTrue(PlotMapSync.apply(admin, corners("rs_l_prot", box(h, 1, 1, 5, 5))).ok() && store(h).get("rs_l_prot").bounds().equals(box(h, 1, 1, 5, 5)),
                    "SET CORNERS on a protected region from the map");
        }, "rs_l_prot", "rs_l_mine", "rs_l_none", "rs_l_sale");
    }

    /** B9: corner SNAP never pulls a corner toward the region's own old box, and only within two blocks of another's side. */
    @GameTest(template = EMPTY)
    public void corner_snap_keeps_clicks_exact_near_the_region_itself(GameTestHelper h) {
        var self = new PlotMapStatePacket.Plot("rs_self", "rs_self", "protected", 0, 64, -64, 112, 79, 319, 127, "", true);
        var other = new PlotMapStatePacket.Plot("rs_other", "rs_other", "plot_for_sale", 0, 90, 60, 150, 99, 70, 159, "", true);
        List<PlotMapStatePacket.Plot> boxes = List.of(self, other);
        int[] own = PlotMap.snapCorner(65, 113, boxes, "rs_self");
        h.assertTrue(own[0] == 65 && own[1] == 113, "near its own old corner: exactly where clicked, got " + own[0] + ", " + own[1]);
        int[] far = PlotMap.snapCorner(84, 140, boxes, "rs_self");
        h.assertTrue(far[0] == 84 && far[1] == 140, "more than two blocks from any side: exactly where clicked");
        int[] near = PlotMap.snapCorner(87, 147, boxes, "rs_self");
        h.assertTrue(near[0] == 89 && near[1] == 149, "within two blocks of a neighbour: just outside its side, got " + near[0] + ", " + near[1]);
        h.succeed();
    }

    private static PlotMapActionPacket corners(String id, Cuboid b) {
        return new PlotMapActionPacket(PlotMapActionPacket.CORNERS, id, "", "", b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), 0, false, false, 0);
    }
}
