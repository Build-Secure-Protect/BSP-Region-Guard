package com.mrgregles.bsp_region_guard.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: one Land Office's plot list for the OFFICE PLOTS screen (D140 to D148): its mode, the plots and
 * groups ticked for it, every plot for sale and open area in its world with its ground and sale group, and every group
 * name known there. Sent on opening and after each change, with the server's answer in {@code message}.
 */
public record OfficeStatePacket(BlockPos office, String world, boolean chosenOnly, List<String> tickedPlots, List<String> tickedGroups,
                                List<Plot> plots, List<String> groups, String message) {
    /** A plot for sale or open area: id, name, ground (x and z), whether it is an open area, and its sale group. */
    public record Plot(String id, String name, int minX, int minZ, int maxX, int maxZ, boolean open, String group) {
    }

    static final int MAX = 4096;

    public static void encode(OfficeStatePacket m, FriendlyByteBuf buf) {
        buf.writeBlockPos(m.office);
        buf.writeUtf(m.world, 256);
        buf.writeBoolean(m.chosenOnly);
        strings(buf, m.tickedPlots);
        strings(buf, m.tickedGroups);
        int n = Math.min(m.plots.size(), MAX);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Plot p = m.plots.get(i);
            buf.writeUtf(p.id, 128);
            buf.writeUtf(p.name, 128);
            buf.writeVarInt(p.minX).writeVarInt(p.minZ).writeVarInt(p.maxX).writeVarInt(p.maxZ);
            buf.writeBoolean(p.open);
            buf.writeUtf(p.group, 64);
        }
        strings(buf, m.groups);
        buf.writeUtf(m.message, 512);
    }

    public static OfficeStatePacket decode(FriendlyByteBuf buf) {
        BlockPos office = buf.readBlockPos();
        String world = buf.readUtf(256);
        boolean chosen = buf.readBoolean();
        List<String> tp = strings(buf), tg = strings(buf);
        int n = Math.min(buf.readVarInt(), MAX);
        List<Plot> plots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            plots.add(new Plot(buf.readUtf(128), buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean(), buf.readUtf(64)));
        }
        List<String> groups = strings(buf);
        return new OfficeStatePacket(office, world, chosen, tp, tg, plots, groups, buf.readUtf(512));
    }

    private static void strings(FriendlyByteBuf buf, List<String> l) {
        int n = Math.min(l.size(), MAX);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(l.get(i), 128);
        }
    }

    private static List<String> strings(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(buf.readUtf(128));
        }
        return out;
    }

    public static void handle(OfficeStatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.OfficePlotsScreen.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
