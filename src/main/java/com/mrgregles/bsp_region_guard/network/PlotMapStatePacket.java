package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: what the plot map shows for one world (D57 to D65): its plots with their boxes, priorities, owners
 * and locks; whether the admin may edit it (only the world they stand in, D62); their undo and redo counts; the answer
 * to their last action. {@code open} opens the screen.
 */
public record PlotMapStatePacket(boolean open, String dimension, boolean editable, List<Plot> plots, int undo, int redo,
                                 boolean ok, String message, boolean askOwner, int playerX, int playerZ, String backupsOf, List<String> backups) {
    public record Plot(String id, String name, String type, int priority, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                       String owner, boolean locked) {
        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }
    }

    public static void encode(PlotMapStatePacket m, FriendlyByteBuf buf) {
        buf.writeBoolean(m.open);
        buf.writeUtf(m.dimension, 128);
        buf.writeBoolean(m.editable);
        buf.writeVarInt(m.plots.size());
        for (Plot p : m.plots) {
            buf.writeUtf(p.id, 64);
            buf.writeUtf(p.name, 128);
            buf.writeUtf(p.type, 32);
            buf.writeInt(p.priority);
            buf.writeInt(p.minX);
            buf.writeInt(p.minY);
            buf.writeInt(p.minZ);
            buf.writeInt(p.maxX);
            buf.writeInt(p.maxY);
            buf.writeInt(p.maxZ);
            buf.writeUtf(p.owner, 64);
            buf.writeBoolean(p.locked);
        }
        buf.writeVarInt(m.undo);
        buf.writeVarInt(m.redo);
        buf.writeBoolean(m.ok);
        buf.writeUtf(m.message, 512);
        buf.writeBoolean(m.askOwner);
        buf.writeInt(m.playerX);
        buf.writeInt(m.playerZ);
        buf.writeUtf(m.backupsOf, 64);
        buf.writeVarInt(Math.min(m.backups.size(), 50));
        m.backups.stream().limit(50).forEach(b -> buf.writeUtf(b, 160));
    }

    public static PlotMapStatePacket decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        String dim = buf.readUtf(128);
        boolean editable = buf.readBoolean();
        int n = buf.readVarInt();
        List<Plot> plots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            plots.add(new Plot(buf.readUtf(64), buf.readUtf(128), buf.readUtf(32), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readUtf(64), buf.readBoolean()));
        }
        int undo = buf.readVarInt(), redo = buf.readVarInt();
        boolean ok = buf.readBoolean();
        String message = buf.readUtf(512);
        boolean ask = buf.readBoolean();
        int px = buf.readInt(), pz = buf.readInt();
        String of = buf.readUtf(64);
        int nb = buf.readVarInt();
        List<String> backups = new ArrayList<>(nb);
        for (int i = 0; i < nb; i++) {
            backups.add(buf.readUtf(160));
        }
        return new PlotMapStatePacket(open, dim, editable, plots, undo, redo, ok, message, ask, px, pz, of, backups);
    }

    public static void handle(PlotMapStatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.PlotMapScreen.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
