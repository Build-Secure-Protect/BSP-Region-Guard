package com.mrgregles.bsp_region_guard.api.client;

import com.mrgregles.bsp_region_guard.client.BannedHereClient;
import net.minecraft.client.Minecraft;

import java.util.function.IntConsumer;

/**
 * Player-side calls for showing a region's banned blocks and items (API version 7, owner decisions D133 to D139), for
 * BSP Core's Land Office. <b>Client only:</b> call these on the client thread, from client code only (a screen, a key).
 *
 * <p>Both ask the server for the region's lists, read at the middle of its box, the same lists the server enforces
 * there (the region's own, its parents', anything overlapping, and the world default).
 */
public final class BannedListView {
    private BannedListView() {
    }

    /**
     * Opens the BANNED HERE screen for a region (a plot for sale, a claim, any region id in the player's world first,
     * then in any world). Esc or CLOSE returns to the screen that was open when this was called, so a container screen
     * such as the Land Office stays open underneath.
     */
    public static void open(String regionId) {
        BannedHereClient.openRegion(regionId, Minecraft.getInstance().screen);
    }

    /**
     * Asks how many items are banned in a region. {@code answer} runs on the client thread when the server replies,
     * with 0 when nothing is banned or the region does not exist. Each call sends one small request.
     */
    public static void count(String regionId, IntConsumer answer) {
        BannedHereClient.count(regionId, answer);
    }
}
