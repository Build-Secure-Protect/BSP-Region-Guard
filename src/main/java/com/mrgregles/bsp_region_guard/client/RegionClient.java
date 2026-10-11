package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.RegionDetailPacket;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/** Client-side state for the region screen: the latest list and detail the server sent. Never referenced from common code. */
public final class RegionClient {
    @Nullable
    private static RegionListPacket list;
    @Nullable
    private static RegionDetailPacket detail;
    @Nullable
    private static com.mrgregles.bsp_region_guard.network.SelectionPacket selection;
    private static boolean showRegions = true;

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

    public static void receive(RegionListPacket msg) {
        list = msg;
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
        list = null;
        detail = null;
        selection = null;
    }
}
