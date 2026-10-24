package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the answer to something an admin did in the region screen (bug B14). An open screen shows it in
 * its own bottom line, since the action bar is hidden behind a screen; with no screen open it goes to the action bar.
 */
public record ActionAnswerPacket(String text, boolean ok) {
    public static void encode(ActionAnswerPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.text, 1024);
        buf.writeBoolean(msg.ok);
    }

    public static ActionAnswerPacket decode(FriendlyByteBuf buf) {
        return new ActionAnswerPacket(buf.readUtf(1024), buf.readBoolean());
    }

    public static void handle(ActionAnswerPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.RegionClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
