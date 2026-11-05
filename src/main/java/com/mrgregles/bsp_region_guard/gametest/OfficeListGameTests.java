package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.ClaimService;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.claim.Claims;
import com.mrgregles.bsp_region_guard.claim.OfficeLists;
import com.mrgregles.bsp_region_guard.command.RegionEdits;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.network.OfficeEditPacket;
import com.mrgregles.bsp_region_guard.network.OfficeSync;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** OFFICE PLOTS (D140 to D148): which plots a Land Office lists, ticks, sale groups, renames, copies, forgetting. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class OfficeListGameTests {
    private static final String EMPTY = "empty";

    private static Region define(GameTestHelper h, String id, int x, RegionType type) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(new BlockPos(x, 1, 1)), h.absolutePos(new BlockPos(x + 2, 4, 3))), type);
        RegionStore.get(h.getLevel()).add(r);
        return r;
    }

    private static Set<String> listed(ServerLevel level, BlockPos office, Set<String> mine) {
        ClaimService c = Claims.INSTANCE;
        Set<String> out = c.plotsForSale(level, office).stream().map(ClaimService.Listing::id).collect(Collectors.toSet());
        c.openAreas(level, office).forEach(l -> out.add(l.id()));
        out.retainAll(mine); // the test world may hold other tests' plots
        return out;
    }

    private static String edit(ServerLevel level, BlockPos office, String action, String a, String b) {
        return OfficeSync.change(level, new OfficeEditPacket(office, action, a, b));
    }

    @GameTest(template = EMPTY)
    public void an_office_lists_ticked_plots_and_ticked_groups(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        RegionStore store = RegionStore.get(level);
        BlockPos office = h.absolutePos(new BlockPos(0, 2, 0));
        Set<String> all = new java.util.HashSet<>(Set.of("ol_a", "ol_b", "ol_open", "ol_a2"));
        try {
            define(h, "ol_a", 1, RegionType.PLOT_FOR_SALE);
            define(h, "ol_b", 5, RegionType.PLOT_FOR_SALE);
            define(h, "ol_open", 9, RegionType.OPEN_FOR_BUYING);
            h.assertTrue(listed(level, office, all).equals(Set.of("ol_a", "ol_b", "ol_open")), "an office never set up lists everything");

            String m = edit(level, office, OfficeEditPacket.TICK, "ol_a", "");
            h.assertTrue(listed(level, office, all).equals(Set.of("ol_a")), "the first tick makes the office list only chosen plots: " + m);
            h.assertTrue(m.contains("only the chosen"), "and says so: " + m);

            edit(level, office, OfficeEditPacket.SET_GROUP, "ol_b", "Market");
            h.assertTrue("market".equals(store.get("ol_b").flag(Flags.SALE_GROUP)), "the group is the plot's sale-group flag, lower case");
            edit(level, office, OfficeEditPacket.TICK_GROUP, "market", "");
            h.assertTrue(listed(level, office, all).equals(Set.of("ol_a", "ol_b")), "a ticked group lists its plots");
            h.assertTrue(!Claims.INSTANCE.listedAt(level, office, "ol_open") && Claims.INSTANCE.listedAt(level, office, "ol_b"),
                    "listedAt follows the same rule (D145)");
            h.assertTrue(listed(level, office.east(40), all).size() == 3, "another office still lists everything");

            Region copy = define(h, "ol_a2", 13, RegionType.PLOT_FOR_SALE);
            OfficeLists.get(level).copied("ol_a", copy.id());
            h.assertTrue(listed(level, office, all).contains("ol_a2"), "a copy of a ticked plot is ticked too (D146)");

            var renamed = RegionEdits.rename(store, store.get("ol_a"), "ol renamed");
            all.add("ol_renamed");
            h.assertTrue(renamed.ok() && listed(level, office, all).contains("ol_renamed"), "a renamed plot stays ticked: " + renamed.message());

            edit(level, office, OfficeEditPacket.MODE, "0", "");
            h.assertTrue(listed(level, office, all).size() == 4, "EVERY PLOT lists everything again");
            edit(level, office, OfficeEditPacket.MODE, "1", "");
            OfficeLists.get(level).forget(office);
            h.assertTrue(listed(level, office, all).size() == 4, "a forgotten (broken) office lists everything (D148)");

            h.assertTrue(edit(level, office, OfficeEditPacket.NEW_GROUP, "bad name!", "").startsWith("A group name"), "group names are checked");

            // audit fixes: a tick that ticks nothing leaves an EVERY PLOT office alone; TICK ALL SHOWN goes by search
            String none = edit(level, office, OfficeEditPacket.TICK_MATCHING, "zzz_no_such_plot", "");
            h.assertTrue(none.startsWith("Nothing to tick") && OfficeLists.get(level).at(office) == null, "an empty tick changes nothing: " + none);
            edit(level, office, OfficeEditPacket.TICK_MATCHING, "ol_b", "");
            h.assertTrue(listed(level, office, all).equals(Set.of("ol_b")), "ticking by search ticks only what matches, and chooses");
        } finally {
            for (String id : List.of("ol_a", "ol_renamed", "ol_b", "ol_open", "ol_a2")) {
                store.remove(id);
            }
            OfficeLists.get(level).forget(office);
        }
        h.succeed();
    }
}
