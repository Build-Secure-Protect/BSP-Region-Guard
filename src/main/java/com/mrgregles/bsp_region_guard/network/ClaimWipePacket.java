package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the claim wipe as the admin screen shows it (owner decision D45): how many claims and owners
 * this world has, the one-time code waiting to be typed back (empty when none) and its seconds left, the backup files,
 * and the last answer from the server.
 */
public record ClaimWipePacket(int claims, int owners, String code, int secondsLeft, List<String> backups, String message) {
    public static void encode(ClaimWipePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.claims);
        buf.writeVarInt(msg.owners);
        buf.writeUtf(msg.code, 8);
        buf.writeVarInt(msg.secondsLeft);
        buf.writeCollection(msg.backups.size() > 20 ? msg.backups.subList(0, 20) : msg.backups, (b, s) -> b.writeUtf(s, 128));
        buf.writeUtf(msg.message, 512);
    }

    public static ClaimWipePacket decode(FriendlyByteBuf buf) {
        return new ClaimWipePacket(buf.readVarInt(), buf.readVarInt(), buf.readUtf(8), buf.readVarInt(), buf.readList(b -> b.readUtf(128)), buf.readUtf(512));
    }

    public static void handle(ClaimWipePacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.RegionClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
