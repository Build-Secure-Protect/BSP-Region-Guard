package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server to client: terrain colours for chunks of a world, 256 packed map colours each, or none for empty ground (D61). */
public record MapTilesPacket(String dimension, List<Long> chunks, List<byte[]> colours) {
    public static void encode(MapTilesPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.dimension, 128);
        buf.writeVarInt(msg.chunks.size());
        for (int i = 0; i < msg.chunks.size(); i++) {
            buf.writeLong(msg.chunks.get(i));
            buf.writeByteArray(msg.colours.get(i));
        }
    }

    public static MapTilesPacket decode(FriendlyByteBuf buf) {
        String dim = buf.readUtf(128);
        int n = buf.readVarInt();
        List<Long> chunks = new ArrayList<>(n);
        List<byte[]> colours = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            chunks.add(buf.readLong());
            colours.add(buf.readByteArray(256));
        }
        return new MapTilesPacket(dim, chunks, colours);
    }

    public static void handle(MapTilesPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.MapTileCache.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
