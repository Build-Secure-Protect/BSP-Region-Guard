package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.protection.BannedList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server: send me the banned lists (D133 to D139). An empty {@code regionId} means where I stand; otherwise
 * that region, read at its middle. {@code purpose} is echoed in the answer ({@link BannedListPacket#STATUS},
 * {@link BannedListPacket#OPEN} or {@link BannedListPacket#INFO}). Banned lists are not secret: any player may ask
 * for any region, as anyone can find them out by trying to place things there.
 */
public record BannedListRequestPacket(int purpose, String regionId) {
    public static BannedListRequestPacket here(int purpose) {
        return new BannedListRequestPacket(purpose, "");
    }

    public static void encode(BannedListRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.purpose);
        buf.writeUtf(msg.regionId, 128);
    }

    public static BannedListRequestPacket decode(FriendlyByteBuf buf) {
        return new BannedListRequestPacket(buf.readByte(), buf.readUtf(128));
    }

    public static void handle(BannedListRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            answer(player, msg);
        }
        ctx.get().setPacketHandled(true);
    }

    /** Works out and sends the answer; a region that does not exist answers with empty lists under its id. */
    static void answer(ServerPlayer player, BannedListRequestPacket msg) {
        int purpose = Math.max(BannedListPacket.STATUS, Math.min(BannedListPacket.INFO, msg.purpose));
        BannedListPacket out = msg.regionId.isEmpty() ? BannedList.here(player, purpose) : BannedList.region(player, msg.regionId, purpose);
        if (out == null) {
            out = new BannedListPacket(purpose, msg.regionId, msg.regionId, new java.util.EnumMap<>(com.mrgregles.bsp_region_guard.protection.BannedBlocks.Rule.class));
        }
        RegionNetwork.sendTo(player, out);
    }
}
