package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.claim.MapTiles;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client to server: the plot map asks for the terrain colours of some chunks of a world (D61). Admins only; any world
 * on this server may be looked at (only the admin's own is editable, D62). Answered by {@link MapTilesPacket}s.
 */
public record MapTilesRequestPacket(String dimension, boolean refresh, List<Long> chunks) {
    public static void encode(MapTilesRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.dimension, 128);
        buf.writeBoolean(msg.refresh);
        int n = Math.min(msg.chunks.size(), MapTiles.MAX_PER_REQUEST);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeLong(msg.chunks.get(i));
        }
    }

    public static MapTilesRequestPacket decode(FriendlyByteBuf buf) {
        String dim = buf.readUtf(128);
        boolean refresh = buf.readBoolean();
        int n = Math.min(buf.readVarInt(), MapTiles.MAX_PER_REQUEST);
        List<Long> chunks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            chunks.add(buf.readLong());
        }
        return new MapTilesRequestPacket(dim, refresh, chunks);
    }

    public static void handle(MapTilesRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null && BspCore.isAdmin(player)) {
            ResourceLocation id = ResourceLocation.tryParse(msg.dimension);
            ServerLevel level = id == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level != null) {
                MapTiles.request(level, msg.chunks, msg.refresh,
                        tiles -> RegionNetwork.sendTo(player, new MapTilesPacket(msg.dimension, new ArrayList<>(tiles.keySet()), new ArrayList<>(tiles.values()))));
            }
        }
        ctx.get().setPacketHandled(true);
    }
}
