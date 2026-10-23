package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.claim.ClaimData;
import com.mrgregles.bsp_region_guard.claim.PlotMap;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
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
import java.util.Set;
import java.util.UUID;

/** The plot map's server side (D57 to D65): paste, the nesting rule, lock and move, undo and redo. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class PlotMapGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    /** A box in test-relative coordinates, two blocks high. */
    private static Cuboid box(GameTestHelper h, int x, int z, int w, int d) {
        return Cuboid.of(h.absolutePos(new BlockPos(x, 1, z)), h.absolutePos(new BlockPos(x + w - 1, 2, z + d - 1)));
    }

    private static Region plot(GameTestHelper h, String id, RegionType type, Cuboid b, int priority) {
        Region r = new Region(id, h.getLevel().dimension(), b, type);
        r.setPriority(priority);
        store(h).add(r);
        return r;
    }

    private interface Body {
        void run(ServerPlayer admin);
    }

    private static void run(GameTestHelper h, Body body, String... ids) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previous = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        try {
            body.run(admin);
        } finally {
            BspCore.setAdminNames(new ArrayList<>(previous));
            for (String id : ids) {
                store(h).remove(id);
            }
            PlotMap.forget(admin.getUUID());
            admin.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_paste_carries_the_rules_but_not_the_people(GameTestHelper h) {
        run(h, admin -> {
            Region src = plot(h, "pm_src", RegionType.CLAIM, box(h, 1, 1, 4, 3), 3);
            src.addOwner(UUID.randomUUID());
            src.addMember(UUID.randomUUID());
            src.setFlag(Flags.PVP, State.DENY);
            src.setFlag(Flags.DENY_CARRY, Set.of("minecraft:tnt"));
            src.setFlag(Flags.GREETING, "Welcome");
            BlockPos tp = h.absolutePos(new BlockPos(2, 1, 2));
            src.setFlag(Flags.TELEPORT, new FlagLocation(h.getLevel().dimension(), tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5, 0, 0));
            src.typeData().putLong(ClaimData.BOUGHT_FOR, 48000);
            src.typeData().putString(ClaimData.PLAYER_FLAGS, "entry,chest-access");
            new com.mrgregles.bsp_region_guard.claim.PlotLook("beacon", 0x5AA9FF, 2f, 1.5f, 6).save(src);
            store(h).touch(src);

            Cuboid there = box(h, 10, 1, 4, 3);
            var r = PlotMap.paste(admin, "pm_src", "pm_copy", "Bakery 2", there, 3);
            h.assertTrue(r.ok(), "pasted: " + r.message());
            Region copy = store(h).get("pm_copy");
            h.assertTrue(copy.type() == RegionType.PLOT_FOR_SALE && copy.owners().isEmpty() && copy.members().isEmpty(), "a plot for sale, nobody's");
            h.assertTrue(copy.bounds().equals(there) && "Bakery 2".equals(copy.name()), "where it was put, with its name");
            h.assertTrue(copy.flag(Flags.PVP) == State.DENY && Set.of("minecraft:tnt").equals(copy.flag(Flags.DENY_CARRY)) && "Welcome".equals(copy.flag(Flags.GREETING)),
                    "flags and banned blocks copied");
            h.assertTrue(copy.typeData().getLong(ClaimData.PRICE) == 48000 && !copy.typeData().contains(ClaimData.BOUGHT_FOR)
                    && "entry,chest-access".equals(copy.typeData().getString(ClaimData.PLAYER_FLAGS)), "priced as the original, the buyer's flags kept");
            FlagLocation moved = copy.flag(Flags.TELEPORT);
            h.assertTrue(moved != null && (int) Math.floor(moved.x()) == tp.getX() + 9, "the teleport point moved with it: " + moved);
            h.assertTrue(PlotMap.locked(copy), "and it starts locked");
            h.assertTrue(com.mrgregles.bsp_region_guard.claim.PlotLook.of(copy).equals(new com.mrgregles.bsp_region_guard.claim.PlotLook("beacon", 0x5AA9FF, 2f, 1.5f, 6)),
                    "the look comes with it (D112)");

            h.assertFalse(PlotMap.paste(admin, "pm_src", "pm_copy", "", box(h, 16, 1, 4, 3), 3).ok(), "a name in use is refused");
            h.assertFalse(PlotMap.paste(admin, "pm_src", "pm_big", "", box(h, 16, 1, 5, 3), 3).ok(), "so is another size");
            h.assertTrue(PlotMap.paste(admin, "pm_src", "pm_turned", "", box(h, 16, 6, 3, 4), 3).ok(), "but turned 90 degrees is fine");
        }, "pm_src", "pm_copy", "pm_big", "pm_turned");
    }

    @GameTest(template = EMPTY)
    public void plots_nest_inside_lower_priorities_and_never_partly_overlap(GameTestHelper h) {
        run(h, admin -> {
            plot(h, "pm_market", RegionType.OPEN_FOR_BUYING, box(h, 1, 1, 12, 12), 2);
            plot(h, "pm_stall", RegionType.PLOT_FOR_SALE, box(h, 14, 1, 3, 3), 2);
            RegionStore s = store(h);
            h.assertTrue(PlotMap.overlapProblem(s, box(h, 3, 3, 3, 3), 3, null) == null, "wholly inside with a higher priority: fine");
            h.assertTrue(PlotMap.overlapProblem(s, box(h, 3, 3, 3, 3), 2, null) != null, "the same priority: refused");
            h.assertTrue(PlotMap.overlapProblem(s, box(h, 11, 3, 3, 3), 3, null) != null, "partly in the market: refused");
            h.assertTrue(PlotMap.overlapProblem(s, box(h, 13, 0, 5, 5), 9, null) != null, "swallowing the stall: refused");
            h.assertTrue(PlotMap.overlapProblem(s, box(h, 17, 1, 3, 3), 1, null) == null, "touching the stall's side: fine");
        }, "pm_market", "pm_stall");
    }

    @GameTest(template = EMPTY)
    public void plots_move_only_when_unlocked_and_lock_again(GameTestHelper h) {
        run(h, admin -> {
            Region r = plot(h, "pm_move", RegionType.PLOT_FOR_SALE, box(h, 1, 1, 3, 3), 1);
            Cuboid there = box(h, 8, 1, 3, 3);
            h.assertFalse(PlotMap.move(admin, "pm_move", there, 1, false).ok(), "a locked plot does not move");
            h.assertTrue(PlotMap.setLocked(admin, "pm_move", false).ok() && !PlotMap.locked(r), "unlocked");
            var moved = PlotMap.move(admin, "pm_move", there, 1, false);
            h.assertTrue(moved.ok() && r.bounds().equals(there) && PlotMap.locked(r), "moved, and locked again: " + moved.message());

            Region owned = plot(h, "pm_owned", RegionType.CLAIM, box(h, 1, 8, 3, 3), 1);
            owned.addOwner(UUID.randomUUID());
            PlotMap.setLocked(admin, "pm_owned", false);
            var ask = PlotMap.move(admin, "pm_owned", box(h, 14, 8, 3, 3), 1, false);
            h.assertTrue(!ask.ok() && ask.askOwner() && owned.bounds().equals(box(h, 1, 8, 3, 3)), "someone's claim waits for the admin's yes");
            h.assertTrue(PlotMap.move(admin, "pm_owned", box(h, 14, 8, 3, 3), 1, true).ok(), "and moves with it");
        }, "pm_move", "pm_owned");
    }

    @GameTest(template = EMPTY)
    public void the_last_fifteen_actions_undo_and_redo(GameTestHelper h) {
        List<String> made = new ArrayList<>(List.of("pm_u_src", "pm_u_moved"));
        for (int i = 0; i < 16; i++) {
            made.add("pm_u_" + i);
        }
        run(h, admin -> {
            plot(h, "pm_u_src", RegionType.PLOT_FOR_SALE, box(h, 0, 0, 1, 1), 1);
            Region m = plot(h, "pm_u_moved", RegionType.PLOT_FOR_SALE, box(h, 0, 2, 1, 1), 1);
            Cuboid home = m.bounds();
            PlotMap.setLocked(admin, "pm_u_moved", false);
            PlotMap.move(admin, "pm_u_moved", box(h, 20, 2, 1, 1), 1, false);
            for (int i = 0; i < 16; i++) {
                h.assertTrue(PlotMap.paste(admin, "pm_u_src", "pm_u_" + i, "", box(h, 2 + i, 0, 1, 1), 1).ok(), "paste " + i);
            }
            h.assertTrue(PlotMap.undoCount(admin) == PlotMap.HISTORY, "fifteen kept, the oldest (the move) dropped: " + PlotMap.undoCount(admin));
            h.assertTrue(PlotMap.undo(admin).ok() && !store(h).has("pm_u_15"), "undo takes the last paste away");
            h.assertTrue(PlotMap.redo(admin).ok() && store(h).has("pm_u_15"), "redo puts it back");
            for (int i = 0; i < 15; i++) {
                PlotMap.undo(admin);
            }
            h.assertTrue(!store(h).has("pm_u_1") && store(h).has("pm_u_0"), "fifteen undone, the sixteenth kept");
            h.assertFalse(PlotMap.undo(admin).ok(), "and nothing more to undo");

            // a move undoes and redoes, and a bought paste stays
            PlotMap.forget(admin.getUUID());
            PlotMap.setLocked(admin, "pm_u_moved", false);
            Cuboid away = m.bounds();
            PlotMap.move(admin, "pm_u_moved", home, 1, false);
            h.assertTrue(PlotMap.undo(admin).ok() && m.bounds().equals(away), "undoing a move puts it back");
            h.assertTrue(PlotMap.redo(admin).ok() && m.bounds().equals(home), "redoing moves it again");
            PlotMap.paste(admin, "pm_u_src", "pm_u_1", "", box(h, 3, 0, 1, 1), 1);
            Region bought = store(h).get("pm_u_1");
            bought.setType(RegionType.CLAIM);
            bought.addOwner(UUID.randomUUID());
            h.assertFalse(PlotMap.undo(admin).ok() || !store(h).has("pm_u_1"), "a paste someone has bought is not undone");
        }, made.toArray(new String[0]));
    }

    /** The screen's server path: the world's plots, a paste as the screen sends it, locks, non-admins refused, the packet intact. */
    @GameTest(template = EMPTY)
    public void the_screen_sees_the_plots_and_pastes_through_its_packets(GameTestHelper h) {
        run(h, admin -> {
            Region src = plot(h, "pm_s_src", RegionType.PLOT_FOR_SALE, box(h, 1, 1, 3, 3), 2);
            plot(h, "pm_s_prot", RegionType.PROTECTED, box(h, 1, 6, 3, 3), 2);
            var view = com.mrgregles.bsp_region_guard.network.PlotMapSync.apply(admin, com.mrgregles.bsp_region_guard.network.PlotMapActionPacket.simple(
                    com.mrgregles.bsp_region_guard.network.PlotMapActionPacket.VIEW, h.getLevel().dimension().location().toString(), true));
            h.assertTrue(view.open() && view.editable(), "the admin's own world opens, editable");
            h.assertTrue(view.plots().stream().anyMatch(p -> p.id().equals("pm_s_src") && p.locked() && p.priority() == 2), "plots are listed, locked");
            h.assertTrue(view.plots().stream().noneMatch(p -> p.id().equals("pm_s_prot")), "protected regions are not plots");

            Cuboid there = box(h, 8, 1, 3, 3);
            var paste = com.mrgregles.bsp_region_guard.network.PlotMapSync.apply(admin, new com.mrgregles.bsp_region_guard.network.PlotMapActionPacket(
                    com.mrgregles.bsp_region_guard.network.PlotMapActionPacket.PASTE, "pm_s_src", "pm_s_copy", "", there.minX(), there.minY(), there.minZ(),
                    there.maxX(), there.maxY(), there.maxZ(), 2, false, false, 0));
            h.assertTrue(paste.ok() && paste.undo() == 1 && paste.plots().stream().anyMatch(p -> p.id().equals("pm_s_copy")), "pasted, one to undo: " + paste.message());
            var unlock = com.mrgregles.bsp_region_guard.network.PlotMapSync.apply(admin, com.mrgregles.bsp_region_guard.network.PlotMapActionPacket.simple(
                    com.mrgregles.bsp_region_guard.network.PlotMapActionPacket.UNLOCK, "pm_s_src", false));
            h.assertTrue(unlock.plots().stream().anyMatch(p -> p.id().equals("pm_s_src") && !p.locked()), "the list shows it unlocked");

            var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            com.mrgregles.bsp_region_guard.network.PlotMapStatePacket.encode(unlock, buf);
            var back = com.mrgregles.bsp_region_guard.network.PlotMapStatePacket.decode(buf);
            h.assertTrue(back.plots().equals(unlock.plots()) && back.undo() == unlock.undo() && back.dimension().equals(unlock.dimension()), "the state packet arrives intact");

            ServerPlayer stranger = TestPlayers.mockServerPlayer(h);
            var refused = com.mrgregles.bsp_region_guard.network.PlotMapSync.apply(stranger, com.mrgregles.bsp_region_guard.network.PlotMapActionPacket.simple(
                    com.mrgregles.bsp_region_guard.network.PlotMapActionPacket.UNLOCK, "pm_s_copy", false));
            h.assertTrue(!refused.ok() && PlotMap.locked(store(h).get("pm_s_copy")), "a non-admin is refused");
            stranger.discard();
        }, "pm_s_src", "pm_s_prot", "pm_s_copy");
    }
}
