package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.network.BannedListPacket;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What players are shown of the banned lists (owner decisions D133 to D139): the entries in force at a spot, for the
 * inventory's BANNED button and {@code /claim banned}, or at a region's middle, for the Land Office and the bought-plot
 * pop-up. The entries are sent as they are written ({@code minecraft:tnt}, {@code @create}, {@code #bsp:sanctuary_bans});
 * the player's game opens tags and whole mods into items itself, with the same matching the server enforces.
 */
public final class BannedList {
    private BannedList() {
    }

    /** One rule's entries at a spot, as the server checks them (CARRY entries count for the other three too). */
    public static Map<BannedBlocks.Rule, List<String>> at(ServerLevel level, BlockPos pos) {
        Map<BannedBlocks.Rule, List<String>> out = new EnumMap<>(BannedBlocks.Rule.class);
        for (BannedBlocks.Rule rule : BannedBlocks.Rule.values()) {
            out.put(rule, new ArrayList<>(BannedBlocks.entries(level, pos, rule).keySet()));
        }
        return out;
    }

    /** Whether anything is banned in the lists. */
    public static boolean any(Map<BannedBlocks.Rule, List<String>> lists) {
        return lists.values().stream().anyMatch(l -> !l.isEmpty());
    }

    /**
     * The region the screen is named after: the highest-priority region at the spot that bans something itself (or
     * through a parent, which then gives the name), so a shop inside a banned world does not claim the world's bans.
     * Null when nothing at the spot bans anything.
     */
    @Nullable
    static Region banning(RegionStore store, ServerLevel level, BlockPos pos) {
        java.util.Set<String> banners = new java.util.HashSet<>();
        for (BannedBlocks.Rule rule : BannedBlocks.Rule.values()) {
            banners.addAll(BannedBlocks.entries(level, pos, rule).values());
        }
        if (banners.isEmpty()) {
            return null;
        }
        for (Region r : store.at(pos)) { // highest priority first, the world default last
            Region a = r;
            for (int i = 0; i < 32 && a != null; i++, a = store.parentOf(a)) { // as entries(): parents, never round a loop
                if (banners.contains(a.id())) {
                    return a;
                }
            }
        }
        return null;
    }

    /** The spot a region's bans are read at: the middle of its box, half way up. Null for the world default or no corners. */
    @Nullable
    public static BlockPos middle(Region r) {
        if (r.isGlobal() || !r.hasCorners()) {
            return null;
        }
        Cuboid b = r.bounds();
        return new BlockPos((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, (b.minZ() + b.maxZ()) / 2);
    }

    /** The lists where a player stands, named after the region that matters most there. */
    public static BannedListPacket here(ServerPlayer player, int purpose) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        RegionStore store = RegionStore.get(level);
        Region named = banning(store, level, pos);
        boolean world = named == null || named.isGlobal(); // the world default's bans read as "this world" on the screen
        return new BannedListPacket(purpose, world ? "" : named.id(), world ? "" : named.name(), at(level, pos));
    }

    /**
     * The lists for a region by id, looked for in the player's world first, then in every world. Null when there is no
     * such region or it has no box to read from.
     */
    @Nullable
    public static BannedListPacket region(ServerPlayer player, String id, int purpose) {
        List<ServerLevel> levels = new ArrayList<>();
        levels.add(player.serverLevel());
        for (ServerLevel l : player.server.getAllLevels()) {
            if (l != player.serverLevel()) {
                levels.add(l);
            }
        }
        for (ServerLevel level : levels) {
            Region r = RegionStore.get(level).get(id);
            BlockPos mid = r == null ? null : middle(r);
            if (mid != null) {
                return new BannedListPacket(purpose, r.id(), r.name(), at(level, mid));
            }
        }
        return null;
    }
}
