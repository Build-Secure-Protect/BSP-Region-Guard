package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: what a player does on their claim screen. Checked against ownership on the server. */
public record ClaimActionPacket(String action, String claimId, String a, String b) {
    public static final String SELECT = "select", FLAG = "flag", ADD_MEMBER = "add-member", REMOVE_MEMBER = "remove-member",
            ABANDON = "abandon", ENLARGE = "enlarge", TELEPORT = "teleport", TOOL = "tool";

    public static ClaimActionPacket of(String action, String claimId) {
        return new ClaimActionPacket(action, claimId, "", "");
    }

    public static void encode(ClaimActionPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.action, 32);
        buf.writeUtf(msg.claimId, 64);
        buf.writeUtf(msg.a, 256);
        buf.writeUtf(msg.b, 1024);
    }

    public static ClaimActionPacket decode(FriendlyByteBuf buf) {
        return new ClaimActionPacket(buf.readUtf(32), buf.readUtf(64), buf.readUtf(256), buf.readUtf(1024));
    }

    public static void handle(ClaimActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            ClaimSync.handle(player, msg);
        }
        ctx.get().setPacketHandled(true);
    }
}
