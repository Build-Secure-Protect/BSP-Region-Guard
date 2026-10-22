package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server: one plot map action (D57 to D65). {@code VIEW} asks for a world's plots ({@code a} = world id,
 * {@code open} to open the screen); {@code LOCK}/{@code UNLOCK} take {@code a} = plot; {@code PASTE} takes {@code a} =
 * source, {@code b} = new id, {@code c} = display name; {@code MOVE} takes {@code a} = plot and {@code confirm} for a
 * claim someone owns; {@code CORNERS} takes {@code a} = region and the box clicked for it (D71); {@code UNDO},
 * {@code REDO}. Boxes and priority as numbers.
 */
public record PlotMapActionPacket(String action, String a, String b, String c, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  int priority, boolean flag, boolean blocks, int rotation) {
    public static final String VIEW = "view", LOCK = "lock", UNLOCK = "unlock", PASTE = "paste", MOVE = "move", UNDO = "undo", REDO = "redo",
            BACKUP = "backup", BACKUPS = "backups", RESTORE = "restore", CORNERS = "corners";

    public static PlotMapActionPacket simple(String action, String a, boolean flag) {
        return new PlotMapActionPacket(action, a, "", "", 0, 0, 0, 0, 0, 0, 0, flag, false, 0);
    }

    public static void encode(PlotMapActionPacket m, FriendlyByteBuf buf) {
        buf.writeUtf(m.action, 16);
        buf.writeUtf(m.a, 128);
        buf.writeUtf(m.b, 64);
        buf.writeUtf(m.c, 128);
        for (int v : new int[]{m.minX, m.minY, m.minZ, m.maxX, m.maxY, m.maxZ, m.priority}) {
            buf.writeInt(v);
        }
        buf.writeBoolean(m.flag);
        buf.writeBoolean(m.blocks);
        buf.writeVarInt(m.rotation);
    }

    public static PlotMapActionPacket decode(FriendlyByteBuf buf) {
        return new PlotMapActionPacket(buf.readUtf(16), buf.readUtf(128), buf.readUtf(64), buf.readUtf(128), buf.readInt(), buf.readInt(),
                buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(PlotMapActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null) {
            RegionNetwork.sendTo(player, PlotMapSync.apply(player, msg));
        }
        ctx.get().setPacketHandled(true);
    }
}
