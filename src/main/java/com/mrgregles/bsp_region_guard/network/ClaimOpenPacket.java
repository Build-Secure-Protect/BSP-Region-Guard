package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/** Server to client: open the player's claim screen on {@code select}; {@code allowed} are the flags they may set there. */
public record ClaimOpenPacket(@Nullable String select, List<String> allowed, int limit) {
    public static void encode(ClaimOpenPacket msg, FriendlyByteBuf buf) {
        buf.writeNullable(msg.select, (b, s) -> b.writeUtf(s, 64));
        buf.writeCollection(msg.allowed, (b, s) -> b.writeUtf(s, 64));
        buf.writeVarInt(msg.limit);
    }

    public static ClaimOpenPacket decode(FriendlyByteBuf buf) {
        return new ClaimOpenPacket(buf.readNullable(b -> b.readUtf(64)), buf.readList(b -> b.readUtf(64)), buf.readVarInt());
    }

    public static void handle(ClaimOpenPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.RegionClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
