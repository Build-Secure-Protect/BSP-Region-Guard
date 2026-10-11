package com.mrgregles.bsp_region_guard.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Server to client: the player's wand selection, so the client can draw it. Either corner may be missing. */
public record SelectionPacket(@Nullable ResourceLocation dimension, @Nullable BlockPos pos1, @Nullable BlockPos pos2) {
    public static void encode(SelectionPacket msg, FriendlyByteBuf buf) {
        buf.writeNullable(msg.dimension, FriendlyByteBuf::writeResourceLocation);
        buf.writeNullable(msg.pos1, FriendlyByteBuf::writeBlockPos);
        buf.writeNullable(msg.pos2, FriendlyByteBuf::writeBlockPos);
    }

    public static SelectionPacket decode(FriendlyByteBuf buf) {
        return new SelectionPacket(buf.readNullable(FriendlyByteBuf::readResourceLocation), buf.readNullable(FriendlyByteBuf::readBlockPos), buf.readNullable(FriendlyByteBuf::readBlockPos));
    }

    public static void handle(SelectionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.RegionClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
