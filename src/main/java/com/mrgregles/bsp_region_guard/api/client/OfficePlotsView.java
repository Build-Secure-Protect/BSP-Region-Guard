package com.mrgregles.bsp_region_guard.api.client;

import com.mrgregles.bsp_region_guard.client.OfficePlotsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Player-side call for BSP Core's Land Office ADMIN key (API version 8, owner decisions D140 to D148). <b>Client only.</b>
 *
 * <p>{@link #open(BlockPos)} asks the server for OFFICE PLOTS, where an admin chooses which plots and sale groups that
 * office lists. The server checks the player is an admin (operator or BSP Core admin) and that a Land Office stands at
 * {@code office}; anyone else gets a refusal on the action bar and no screen. Esc returns to the screen that was open,
 * so the Land Office stays open underneath. Show the key only to admins and operators.
 */
public final class OfficePlotsView {
    private OfficePlotsView() {
    }

    /** Opens OFFICE PLOTS for the Land Office at {@code office} in the player's world. */
    public static void open(BlockPos office) {
        OfficePlotsScreen.request(office, Minecraft.getInstance().screen);
    }
}
