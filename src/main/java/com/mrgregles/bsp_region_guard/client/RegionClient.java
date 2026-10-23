package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.RegionDetailPacket;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/** Client-side state for the region screen: the latest list and detail the server sent. Never referenced from common code. */
public final class RegionClient {
    @Nullable
    private static RegionListPacket list;
    /** The last list of this server's own regions, kept while the admin looks at another server (its "+ THIS SERVER" overlay). */
    @Nullable
    private static RegionListPacket ownList;
    @Nullable
    private static RegionDetailPacket detail;
    @Nullable
    private static com.mrgregles.bsp_region_guard.network.SelectionPacket selection;
    private static boolean showRegions = true;
    /** Whether the region key's first state was set on this connection: on for admins, off for players (D105). */
    private static boolean showDecided;

    private RegionClient() {
    }

    @Nullable
    public static RegionListPacket list() {
        return list;
    }

    @Nullable
    public static RegionDetailPacket detail() {
        return detail;
    }

    @Nullable
    public static RegionListPacket ownList() {
        return ownList;
    }

    public static void receive(RegionListPacket msg) {
        if (!showDecided) {
            showDecided = true;
            showRegions = msg.admin();
        }
        list = msg;
        if (!msg.remote()) {
            ownList = msg;
        }
        Minecraft mc = Minecraft.getInstance();
        if (msg.open()) {
            mc.setScreen(new RegionScreen(msg.select()));
        } else if (mc.screen instanceof RegionScreen screen) {
            screen.onList(msg);
        } else if (mc.screen instanceof ClaimScreen screen) {
            screen.onList(msg);
        }
    }

    public static void receive(RegionDetailPacket msg) {
        detail = msg;
        if (Minecraft.getInstance().screen instanceof RegionScreen screen) {
            screen.onDetail(msg);
        } else if (Minecraft.getInstance().screen instanceof ClaimScreen screen) {
            screen.onDetail(msg);
        }
    }

    public static void receive(com.mrgregles.bsp_region_guard.network.ClaimOpenPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ClaimScreen screen) {
            screen.onOpen(msg);
        } else {
            mc.setScreen(new ClaimScreen(msg));
        }
    }

    /** The travel pop-up at a linked world edge; it replaces any screen but the player's own typing. */
    public static void receive(com.mrgregles.bsp_region_guard.network.EdgePromptPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
            mc.setScreen(new EdgePromptScreen(msg));
        }
    }

    /** Just bought a plot: offer to teleport there (D113). It replaces the Land Office, not the player's own typing. */
    public static void receive(com.mrgregles.bsp_region_guard.network.PlotBoughtPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
            if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                mc.player.closeContainer(); // the Land Office is a container: tell the server it closed
            }
            mc.setScreen(new PlotBoughtScreen(msg));
        }
    }

    /** The banned items pop-up (D54): turned away from a region for carrying banned things. */
    public static void receive(com.mrgregles.bsp_region_guard.network.BannedPopupPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) && !(mc.screen instanceof BannedPopupScreen)) {
            mc.setScreen(new BannedPopupScreen(msg));
        }
    }

    /** The claim wipe's state and the server's answer, for the admin screen (D45). */
    public static void receive(com.mrgregles.bsp_region_guard.network.ClaimWipePacket msg) {
        if (Minecraft.getInstance().screen instanceof RegionScreen screen) {
            screen.onWipe(msg);
        }
    }

    public static void receive(com.mrgregles.bsp_region_guard.network.SelectionPacket msg) {
        selection = msg;
    }

    @Nullable
    public static com.mrgregles.bsp_region_guard.network.SelectionPacket selection() {
        return selection;
    }

    public static boolean showRegions() {
        return showRegions;
    }

    public static boolean toggleShowRegions() {
        showRegions = !showRegions;
        return showRegions;
    }

    public static void clear() {
        showDecided = false;
        list = null;
        ownList = null;
        detail = null;
        selection = null;
    }
}
