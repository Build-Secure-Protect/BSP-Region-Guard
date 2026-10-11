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
        }
    }

    public static void receive(RegionDetailPacket msg) {
        detail = msg;
        if (Minecraft.getInstance().screen instanceof RegionScreen screen) {
            screen.onDetail(msg);
        }
    }

    public static void clear() {
        list = null;
        detail = null;
    }
}
