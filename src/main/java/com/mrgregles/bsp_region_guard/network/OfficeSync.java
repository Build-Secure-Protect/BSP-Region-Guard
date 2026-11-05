package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.claim.OfficeLists;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The server side of OFFICE PLOTS (owner decisions D140 to D148): admins open it with a sneak + right-click of the
 * Region Wand on a Land Office, or through BSP Core's ADMIN key ({@code api.client.OfficePlotsView}); every change is
 * checked here and answered with the office's new state. A broken Land Office forgets its list.
 */
@Mod.EventBusSubscriber(modid = com.mrgregles.bsp_region_guard.BSPRegionGuard.MODID)
public final class OfficeSync {
    /** BSP Core's Land Office block, by id. */
    public static final ResourceLocation LAND_OFFICE = com.mrgregles.bsp_region_guard.compat.BspCore.LAND_OFFICE_BLOCK;

    private OfficeSync() {
    }

    /** Whether the block at {@code pos} is a Land Office. */
    public static boolean isOffice(Level level, BlockPos pos) {
        return LAND_OFFICE.equals(ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()));
    }

    /** Opens OFFICE PLOTS for an admin; anyone else is told no. */
    public static void open(ServerPlayer player, BlockPos office) {
        apply(player, OfficeEditPacket.open(office));
    }

    static void apply(ServerPlayer player, OfficeEditPacket m) {
        if (!RegionSync.mayEdit(player)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Only admins can choose a Land Office's plots."), true);
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!isOffice(level, m.office())) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("There is no Land Office there any more."), true);
            return;
        }
        String message = change(level, m);
        RegionNetwork.sendTo(player, state(level, m.office(), message));
    }

    /** Applies one change and says what happened ("" for opening). Public for the GameTests; callers check admin rights first. */
    public static String change(ServerLevel level, OfficeEditPacket m) {
        OfficeLists lists = OfficeLists.get(level);
        RegionStore store = RegionStore.get(level);
        OfficeLists.Office before = lists.at(m.office());
        boolean wasEvery = before == null || !before.chosenOnly;
        boolean ticking = m.action().equals(OfficeEditPacket.TICK) || m.action().equals(OfficeEditPacket.TICK_MATCHING) || m.action().equals(OfficeEditPacket.TICK_GROUP);
        if (ticking && toTick(store, m).isEmpty() && !m.action().equals(OfficeEditPacket.TICK_GROUP)) {
            return "Nothing to tick: no plot for sale matches."; // and the office keeps listing what it listed
        }
        if (ticking && m.action().equals(OfficeEditPacket.TICK_GROUP) && OfficeLists.normal(m.a()).isEmpty()) {
            return "Nothing to tick.";
        }
        if (wasEvery && ticking) {
            // ticking means choosing: the office stops listing every plot (it would do nothing otherwise)
            lists.edit(m.office()).chosenOnly = true;
            String done = change(level, m);
            return done.isEmpty() ? "This office now lists only the chosen plots and groups." : done;
        }
        switch (m.action()) {
            case OfficeEditPacket.MODE -> {
                boolean chosen = "1".equals(m.a());
                lists.edit(m.office()).chosenOnly = chosen;
                return chosen ? "This office lists only the chosen plots and groups." : "This office lists every plot in the world.";
            }
            case OfficeEditPacket.TICK, OfficeEditPacket.TICK_MATCHING -> lists.edit(m.office()).plots.addAll(toTick(store, m));
            case OfficeEditPacket.UNTICK -> lists.edit(m.office()).plots.remove(m.a());
            case OfficeEditPacket.CLEAR -> {
                var o = lists.edit(m.office());
                o.plots.clear();
                o.groups.clear();
                return "Nothing is chosen.";
            }
            case OfficeEditPacket.TICK_GROUP -> lists.edit(m.office()).groups.add(OfficeLists.normal(m.a()));
            case OfficeEditPacket.UNTICK_GROUP -> lists.edit(m.office()).groups.remove(OfficeLists.normal(m.a()));
            case OfficeEditPacket.NEW_GROUP -> {
                String g = OfficeLists.normal(m.a());
                if (g.isEmpty() || !g.matches("[a-z0-9_-]{1,32}")) {
                    return "A group name is 1 to 32 letters, digits, - or _.";
                }
                lists.makeGroup(g);
                return "Group " + g + " made. Put plots in it on the PLOTS tab.";
            }
            case OfficeEditPacket.SET_GROUP -> {
                Region r = store.get(m.a());
                if (!sellable(r)) {
                    return "That plot is no longer for sale.";
                }
                String g = OfficeLists.normal(m.b());
                r.setFlag(Flags.SALE_GROUP, g.isEmpty() ? null : g);
                store.touch(r);
                if (!g.isEmpty()) {
                    lists.makeGroup(g);
                }
                return g.isEmpty() ? r.id() + " is in no group." : r.id() + " is in group " + g + ".";
            }
            default -> {
            }
        }
        return "";
    }

    /**
     * The plots a TICK or TICK_MATCHING would tick: the one named, or every plot for sale and open area that matches
     * the search the screen shows (the same rule, {@link OfficeLists#matches}), worked out here so no long id list is sent.
     */
    static List<String> toTick(RegionStore store, OfficeEditPacket m) {
        List<String> out = new ArrayList<>();
        if (m.action().equals(OfficeEditPacket.TICK)) {
            if (sellable(store.get(m.a()))) {
                out.add(m.a());
            }
        } else if (m.action().equals(OfficeEditPacket.TICK_MATCHING)) {
            for (Region r : store.all()) {
                if (sellable(r) && OfficeLists.matches(r.id(), r.name(), OfficeLists.group(r), m.a())) {
                    out.add(r.id());
                }
            }
        }
        return out;
    }

    static boolean sellable(Region r) {
        return r != null && r.bounds() != null && (r.type() == RegionType.PLOT_FOR_SALE || r.type() == RegionType.OPEN_FOR_BUYING);
    }

    /** The office's state as the screen shows it. */
    static OfficeStatePacket state(ServerLevel level, BlockPos office, String message) {
        OfficeLists lists = OfficeLists.get(level);
        OfficeLists.Office o = lists.at(office);
        List<OfficeStatePacket.Plot> plots = new ArrayList<>();
        Set<String> groups = new TreeSet<>(lists.madeGroups());
        for (Region r : RegionStore.get(level).all()) {
            if (sellable(r)) {
                String g = OfficeLists.group(r);
                if (!g.isEmpty()) {
                    groups.add(g);
                }
                var b = r.bounds();
                plots.add(new OfficeStatePacket.Plot(r.id(), r.name(), b.minX(), b.minZ(), b.maxX(), b.maxZ(), r.type() == RegionType.OPEN_FOR_BUYING, g));
            }
        }
        plots.sort((x, y) -> x.id().compareTo(y.id()));
        if (o != null) {
            groups.addAll(o.groups);
        }
        return new OfficeStatePacket(office, level.dimension().location().toString(), o != null && o.chosenOnly,
                o == null ? List.of() : new ArrayList<>(o.plots), o == null ? List.of() : new ArrayList<>(o.groups),
                plots, new ArrayList<>(groups), message);
    }

    /**
     * A broken Land Office forgets which plots it listed (D148). Listens last and only to breaks nothing cancelled,
     * so an office kept by protection (a cancelled break) keeps its list.
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level && LAND_OFFICE.equals(ForgeRegistries.BLOCKS.getKey(event.getState().getBlock()))) {
            OfficeLists.get(level).forget(event.getPos());
        }
    }
}
