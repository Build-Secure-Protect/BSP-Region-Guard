package com.mrgregles.bsp_region_guard.selection;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-side selections per player. Not saved: a restart clears them. */
public final class Selections {
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    private Selections() {
    }

    public static Selection of(ServerPlayer player) {
        return SELECTIONS.computeIfAbsent(player.getUUID(), k -> new Selection());
    }

    /** Sends the player's selection to their client so it can be drawn. Call after every change. */
    public static void sync(ServerPlayer player) {
        Selection sel = of(player);
        com.mrgregles.bsp_region_guard.network.RegionNetwork.sendTo(player,
                new com.mrgregles.bsp_region_guard.network.SelectionPacket(sel.dimension() == null ? null : sel.dimension().location(), sel.pos1(), sel.pos2()));
    }

    public static void forget(UUID player) {
        SELECTIONS.remove(player);
    }

    public static void clearAll() {
        SELECTIONS.clear();
    }
}
