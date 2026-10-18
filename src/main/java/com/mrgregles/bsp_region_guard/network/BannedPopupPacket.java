package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the player was turned away from a region for carrying banned things (D54); open the pop-up that
 * shows them. {@code where} says where each one was found ("x 12", "in Shulker Box"), in the server's words.
 */
public record BannedPopupPacket(String regionId, List<ItemStack> items, List<String> where) {
    /** The most things the pop-up shows; the rest are counted. */
    public static final int MAX_SHOWN = 6;

    public static void encode(BannedPopupPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.regionId, 128);
        int n = Math.min(msg.items.size(), MAX_SHOWN);
        buf.writeVarInt(msg.items.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeItem(msg.items.get(i));
            buf.writeUtf(msg.where.get(i), 128);
        }
    }

    public static BannedPopupPacket decode(FriendlyByteBuf buf) {
        String region = buf.readUtf(128);
        int total = buf.readVarInt(), n = buf.readVarInt();
        List<ItemStack> items = new ArrayList<>();
        List<String> where = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(buf.readItem());
            where.add(buf.readUtf(128));
        }
        for (int i = n; i < total; i++) {
            items.add(ItemStack.EMPTY); // counted, not shown
            where.add("");
        }
        return new BannedPopupPacket(region, items, where);
    }

    public static void handle(BannedPopupPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.RegionClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
