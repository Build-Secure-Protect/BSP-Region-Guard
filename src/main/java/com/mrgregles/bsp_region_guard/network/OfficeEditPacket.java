package com.mrgregles.bsp_region_guard.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server: open or change one Land Office's plot list (D140 to D148). Admins only; the server answers with an
 * {@link OfficeStatePacket}. {@code a} and {@code b} depend on the action: a plot id, a group name, the search text whose
 * matching plots to tick, or "1"/"0" for the mode.
 */
public record OfficeEditPacket(BlockPos office, String action, String a, String b) {
    public static final String OPEN = "open", MODE = "mode", TICK = "tick", UNTICK = "untick", TICK_GROUP = "tick_group",
            UNTICK_GROUP = "untick_group", TICK_MATCHING = "tick_matching", CLEAR = "clear", NEW_GROUP = "new_group", SET_GROUP = "set_group";

    public static OfficeEditPacket open(BlockPos office) {
        return new OfficeEditPacket(office, OPEN, "", "");
    }

    public static void encode(OfficeEditPacket m, FriendlyByteBuf buf) {
        buf.writeBlockPos(m.office);
        buf.writeUtf(m.action, 32);
        buf.writeUtf(m.a, 128);
        buf.writeUtf(m.b, 128);
    }

    public static OfficeEditPacket decode(FriendlyByteBuf buf) {
        return new OfficeEditPacket(buf.readBlockPos(), buf.readUtf(32), buf.readUtf(128), buf.readUtf(128));
    }

    public static void handle(OfficeEditPacket msg, Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null) {
            OfficeSync.apply(player, msg);
        }
        ctx.get().setPacketHandled(true);
    }
}
