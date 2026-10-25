package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.claim.ClaimData;
import com.mrgregles.bsp_region_guard.claim.PlotMap;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.network.PlotMapActionPacket;
import com.mrgregles.bsp_region_guard.network.PlotMapStatePacket;
import com.mrgregles.bsp_region_guard.network.PlotMapSync;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The region map's editing (D82 to D91): drawing each kind of region, deleting with undo, the card's quick edits and MINE extras. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class MapEditorGameTests {
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

    private static void run(GameTestHelper h, Body body, String... ids) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previous = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        boolean claimsWere = com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.get();
        com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(true); // the test world counts as a PvP world
        try {
            body.run(admin);
        } finally {
            com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(claimsWere);
            BspCore.setAdminNames(new ArrayList<>(previous));
            for (String id : ids) {
                if (store(h).has(id)) {
                    store(h).remove(id);
                }
            }
            PlotMap.forget(admin.getUUID());
            admin.discard();
        }
        h.succeed();
    }

    private static PlotMapStatePacket draw(ServerPlayer admin, String id, String type, Cuboid b, int priority, String price) {
        return PlotMapSync.apply(admin, new PlotMapActionPacket(PlotMapActionPacket.CREATE, id, type, price, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(),
                priority, false, false, 0));
    }

    private static PlotMapStatePacket act(ServerPlayer admin, String action, String id, String b, int priority) {
        return PlotMapSync.apply(admin, new PlotMapActionPacket(action, id, b, "", 0, 0, 0, 0, 0, 0, priority, false, false, 0));
    }

    @GameTest(template = EMPTY)
    public void drawing_makes_each_kind_and_undo_takes_it_back(GameTestHelper h) {
        run(h, admin -> {
            RegionStore store = store(h);
            h.assertTrue(draw(admin, "me_prot", "protected", box(h, 1, 1, 4, 4), 3, "").ok(), "REGION mode draws a protected region");
            h.assertTrue(store.get("me_prot").type() == RegionType.PROTECTED && store.get("me_prot").priority() == 3, "with its priority");
            h.assertFalse(draw(admin, "me_plot", "plot_for_sale", box(h, 6, 1, 3, 3), 0, "").ok(), "a plot needs a price");
            h.assertFalse(draw(admin, "me_plot", "plot_for_sale", box(h, 6, 1, 3, 3), 0, "abc").ok(), "a price is a number");
            h.assertTrue(draw(admin, "me_plot", "plot_for_sale", box(h, 6, 1, 3, 3), 0, "2500").ok(), "PLOT mode draws a plot for sale");
            Region plot = store.get("me_plot");
            h.assertTrue(plot.type() == RegionType.PLOT_FOR_SALE && plot.typeData().getLong(ClaimData.PRICE) == 2500, "for sale at its price");
            h.assertFalse(draw(admin, "me_plot2", "plot_for_sale", box(h, 7, 2, 4, 4), 0, "100").ok(), "a plot may not partly overlap another (D59)");
            h.assertTrue(draw(admin, "me_mine", "mine", box(h, 1, 7, 3, 3), 0, "").ok() && store.get("me_mine").type() == RegionType.MINE, "MINE mode draws a mine");
            h.assertFalse(draw(admin, "me_prot", "protected", box(h, 10, 10, 2, 2), 0, "").ok(), "an id already used is refused");
            h.assertFalse(draw(admin, "me_claim", "claim", box(h, 10, 10, 2, 2), 0, "").ok(), "the map makes no claims");
            Cuboid high = new Cuboid(box(h, 10, 10, 2, 2).minX(), 0, box(h, 10, 10, 2, 2).minZ(), box(h, 10, 10, 2, 2).maxX(), h.getLevel().getMaxBuildHeight() + 1,
                    box(h, 10, 10, 2, 2).maxZ());
            h.assertFalse(draw(admin, "me_high", "protected", high, 0, "").ok(), "above the world is refused");

            h.assertTrue(PlotMap.undo(admin).ok() && !store.has("me_mine"), "undo takes the mine away");
            h.assertTrue(PlotMap.undo(admin).ok() && !store.has("me_plot"), "then the plot");
            h.assertTrue(PlotMap.undo(admin).ok() && !store.has("me_prot"), "then the protected region");
            h.assertTrue(PlotMap.redo(admin).ok() && store.has("me_prot") && store.get("me_prot").priority() == 3, "redo makes it again");
        }, "me_prot", "me_plot", "me_plot2", "me_mine", "me_claim", "me_high");
    }

    @GameTest(template = EMPTY)
    public void delete_from_the_map_and_undo_brings_everything_back(GameTestHelper h) {
        run(h, admin -> {
            RegionStore store = store(h);
            Region r = new Region("me_del", h.getLevel().dimension(), box(h, 1, 1, 4, 4), RegionType.PROTECTED);
            UUID owner = UUID.randomUUID();
            r.addOwner(owner);
            r.setFlag(Flags.PVP, State.DENY);
            r.setFlag(Flags.GREETING, "Hello");
            r.setPriority(7);
            store.add(r);
            ServerPlayer stranger = TestPlayers.mockServerPlayer(h);
            h.assertFalse(act(stranger, PlotMapActionPacket.DELETE, "me_del", "", 0).ok() || !store.has("me_del"), "a non-admin is refused");
            stranger.discard();
            h.assertTrue(act(admin, PlotMapActionPacket.DELETE, "me_del", "", 0).ok() && !store.has("me_del"), "deleted");
            h.assertFalse(act(admin, PlotMapActionPacket.DELETE, Region.GLOBAL_ID, "", 0).ok(), "the world default can't be deleted");
            h.assertTrue(PlotMap.undo(admin).ok(), "undo");
            Region back = store.get("me_del");
            h.assertTrue(back != null && back.isOwner(owner) && back.flag(Flags.PVP) == State.DENY && "Hello".equals(back.flag(Flags.GREETING))
                    && back.priority() == 7 && back.bounds().equals(box(h, 1, 1, 4, 4)), "back with its owner, flags, priority and box");
            h.assertTrue(PlotMap.redo(admin).ok() && !store.has("me_del"), "redo deletes it again");
        }, "me_del");
    }

    @GameTest(template = EMPTY)
    public void the_card_edits_and_mine_extras_work_from_the_map(GameTestHelper h) {
        run(h, admin -> {
            RegionStore store = store(h);
            draw(admin, "me_card", "protected", box(h, 1, 1, 3, 3), 0, "");
            // B16: the id follows the name ("ME Card" keeps me_card), so the rest of the test still finds it
            h.assertTrue(act(admin, PlotMapActionPacket.RENAME, "me_card", "ME Card", 0).ok() && store.get("me_card").name().equals("ME Card"), "rename");
            h.assertTrue(act(admin, PlotMapActionPacket.PRIORITY, "me_card", "", 4).ok() && store.get("me_card").priority() == 4, "priority");
            h.assertTrue(act(admin, PlotMapActionPacket.TYPE, "me_card", "open_for_buying", 0).ok() && store.get("me_card").type() == RegionType.OPEN_FOR_BUYING, "type");
            h.assertFalse(act(admin, PlotMapActionPacket.RESET, "me_card", "", 0).ok(), "RESET needs a mine");

            draw(admin, "me_pit", "mine", box(h, 6, 6, 3, 3), 2, "");
            h.assertTrue(act(admin, PlotMapActionPacket.SHELL, "me_pit", "", 0).ok() && store.has("me_pit_shell"), "SHELL makes the mine's shell");
            h.assertTrue(store.get("me_pit_shell").bounds().minX() == store.get("me_pit").bounds().minX() - 5, "five blocks around it");
            h.assertTrue(act(admin, PlotMapActionPacket.RESET, "me_pit", "", 0).ok(), "RESET on a mine");
        }, "me_card", "me_pit", "me_pit_shell");
    }
}
