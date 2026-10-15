package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the player walked into a world edge linked to another server; open the travel pop-up (D42).
 * The answer goes back as {@link EdgeAnswerPacket} with the same {@code token}.
 */
public record EdgePromptPacket(int token, String server, String world, double x, double z) {
    public static void encode(EdgePromptPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.token);
        buf.writeUtf(msg.server, 64);
        buf.writeUtf(msg.world, 128);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.z);
    }

    public static EdgePromptPacket decode(FriendlyByteBuf buf) {
        return new EdgePromptPacket(buf.readVarInt(), buf.readUtf(64), buf.readUtf(128), buf.readDouble(), buf.readDouble());
    }

    public static void handle(EdgePromptPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.RegionClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
