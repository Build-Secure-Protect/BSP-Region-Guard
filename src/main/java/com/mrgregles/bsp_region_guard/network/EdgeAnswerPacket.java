package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.protection.EdgeLinks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: the player's answer to the travel pop-up. Checked against the question the server asked. */
public record EdgeAnswerPacket(int token, boolean travel) {
    public static void encode(EdgeAnswerPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.token);
        buf.writeBoolean(msg.travel);
    }

    public static EdgeAnswerPacket decode(FriendlyByteBuf buf) {
        return new EdgeAnswerPacket(buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(EdgeAnswerPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            EdgeLinks.answer(player, msg.token, msg.travel);
        }
        ctx.get().setPacketHandled(true);
    }
}
