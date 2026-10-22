package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.claim.PlotMap;
import com.mrgregles.bsp_region_guard.command.RegionEdits;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The plot map's server side of the screen: runs an action through {@link PlotMap} and answers with the world's state. */
public final class PlotMapSync {
    private PlotMapSync() {
    }

    /** Opens the plot map on the admin's own world. */
    public static void open(ServerPlayer player) {
        RegionNetwork.sendTo(player, state(player, player.serverLevel(), true, new PlotMap.Result(true, "", false), ""));
    }

    /** Opens the plot map on the admin's own world, waiting for the two corners of {@code regionId} (D71). */
    public static void openCorners(ServerPlayer player, String regionId, String message) {
        RegionNetwork.sendTo(player, cornersState(player, regionId, message));
    }

    public static PlotMapStatePacket cornersState(ServerPlayer player, String regionId, String message) {
        PlotMapStatePacket p = state(player, player.serverLevel(), true, new PlotMap.Result(true, message, false), "");
        return new PlotMapStatePacket(p.open(), p.dimension(), p.editable(), p.plots(), p.undo(), p.redo(), p.ok(), p.message(), p.askOwner(),
                p.playerX(), p.playerZ(), p.backupsOf(), p.backups(), regionId, p.bottom(), p.top(), p.others());
    }

    public static PlotMapStatePacket apply(ServerPlayer player, PlotMapActionPacket m) {
        ServerLevel own = player.serverLevel();
        if (!BspCore.isAdmin(player)) {
            return state(player, own, false, new PlotMap.Result(false, "Only BSP-Core admins can use the plot map.", false), "");
        }
        Cuboid box = new Cuboid(m.minX(), m.minY(), m.minZ(), m.maxX(), m.maxY(), m.maxZ());
        PlotMap.Result r = switch (m.action()) {
            case PlotMapActionPacket.VIEW -> {
                ResourceLocation id = ResourceLocation.tryParse(m.a());
                ServerLevel level = id == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
                yield null == level ? new PlotMap.Result(false, "No world " + m.a() + " on this server.", false) : null;
            }
            case PlotMapActionPacket.LOCK, PlotMapActionPacket.UNLOCK -> PlotMap.setLocked(player, m.a(), m.action().equals(PlotMapActionPacket.LOCK));
            case PlotMapActionPacket.PASTE -> PlotMap.paste(player, m.a(), m.b(), m.c(), box, m.priority(), m.blocks(), m.rotation());
            case PlotMapActionPacket.MOVE -> PlotMap.move(player, m.a(), box, m.priority(), m.flag(), m.blocks(), m.rotation());
            case PlotMapActionPacket.BACKUP -> PlotMap.backup(player, m.a());
            case PlotMapActionPacket.BACKUPS -> new PlotMap.Result(true, "", false);
            case PlotMapActionPacket.RESTORE -> PlotMap.restore(player, m.a());
            case PlotMapActionPacket.UNDO -> PlotMap.undo(player);
            case PlotMapActionPacket.REDO -> PlotMap.redo(player);
            case PlotMapActionPacket.CORNERS -> PlotMap.setCorners(player, m.a(), box);
            case PlotMapActionPacket.CREATE -> {
                long price;
                try {
                    price = m.c().isBlank() ? 0 : Long.parseLong(m.c().trim());
                } catch (NumberFormatException e) {
                    price = -1;
                }
                yield PlotMap.create(player, m.a(), m.b(), box, m.priority(), price);
            }
            case PlotMapActionPacket.DELETE -> PlotMap.delete(player, m.a());
            case PlotMapActionPacket.RENAME, PlotMapActionPacket.PRIORITY, PlotMapActionPacket.TYPE, PlotMapActionPacket.RESET, PlotMapActionPacket.SHELL ->
                    quickEdit(player, m);
            default -> new PlotMap.Result(false, "Unknown action " + m.action(), false);
        };
        if (m.action().equals(PlotMapActionPacket.VIEW)) {
            ResourceLocation id = ResourceLocation.tryParse(m.a());
            ServerLevel level = id == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            return state(player, level == null ? own : level, m.flag(), r == null ? new PlotMap.Result(true, "", false) : r, "");
        }
        // the backups list follows the plot named in a BACKUPS or BACKUP request; for a restore, the file's plot
        String of = switch (m.action()) {
            case PlotMapActionPacket.BACKUPS, PlotMapActionPacket.BACKUP -> m.a();
            case PlotMapActionPacket.RESTORE -> m.a().contains("__") ? m.a().substring(0, m.a().indexOf("__")) : "";
            default -> "";
        };
        return state(player, own, false, r, of);
    }

    /** The card's quick edits and MINE mode's extras (D86, D91), through the same {@link RegionEdits} the admin screen uses. */
    private static PlotMap.Result quickEdit(ServerPlayer player, PlotMapActionPacket m) {
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region r = store.get(m.a());
        if (r == null || r.isGlobal()) {
            return new PlotMap.Result(false, "No region called '" + m.a() + "' in this world.", false);
        }
        RegionEdits.Result out = switch (m.action()) {
            case PlotMapActionPacket.RENAME -> RegionEdits.rename(store, r, m.b());
            case PlotMapActionPacket.PRIORITY -> RegionEdits.priority(store, r, m.priority());
            case PlotMapActionPacket.TYPE -> RegionEdits.type(level, store, r, m.b());
            case PlotMapActionPacket.RESET -> RegionEdits.mineReset(level, store, r);
            default -> RegionEdits.mineShell(level, store, r, 5);
        };
        return new PlotMap.Result(out.ok(), out.message(), false);
    }

    /**
     * A world's plots (plots for sale, claims and open areas) for the screen, biggest first so small ones draw on top,
     * and its other regions with corners as outlines.
     */
    static PlotMapStatePacket state(ServerPlayer player, ServerLevel level, boolean open, PlotMap.Result r, String backupsOf) {
        List<PlotMapStatePacket.Plot> plots = new ArrayList<>(), others = new ArrayList<>();
        List<Region> regions = new ArrayList<>(RegionStore.get(level).all());
        regions.sort(Comparator.comparingLong((Region x) -> x.bounds() == null ? 0 : -(long) x.bounds().sizeX() * x.bounds().sizeZ()));
        for (Region x : regions) {
            if (x.isGlobal() || x.bounds() == null || !x.hasCorners()) {
                continue;
            }
            Cuboid b = x.bounds();
            if (!PlotMap.isPlot(x)) {
                others.add(new PlotMapStatePacket.Plot(x.id(), x.name(), x.type().key(), x.priority(), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), "", true));
                continue;
            }
            String owner = x.owners().stream().findFirst().map(u -> player.server.getProfileCache().get(u).map(p -> p.getName()).orElse(u.toString().substring(0, 8))).orElse("");
            plots.add(new PlotMapStatePacket.Plot(x.id(), x.name(), x.type().key(), x.priority(), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(),
                    owner, PlotMap.locked(x)));
        }
        boolean editable = level == player.serverLevel();
        return new PlotMapStatePacket(open, level.dimension().location().toString(), editable, plots, PlotMap.undoCount(player), PlotMap.redoCount(player),
                r.ok(), r.message(), r.askOwner(), player.getBlockX(), player.getBlockZ(), backupsOf,
                backupsOf.isEmpty() ? List.of() : com.mrgregles.bsp_region_guard.claim.PlotBlocks.backups(player.server, backupsOf),
                "", level.getMinBuildHeight(), level.getMaxBuildHeight() - 1, others);
    }
}
