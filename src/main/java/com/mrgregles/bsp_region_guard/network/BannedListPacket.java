package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.protection.BannedBlocks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server to client: the banned lists for a spot or a region (D133 to D139), entries as written, one list per rule.
 * {@code purpose} says what the player's game does with it: {@link #STATUS} keeps it for the inventory's BANNED button,
 * {@link #OPEN} opens the BANNED HERE screen, {@link #INFO} answers a count (bought pop-up, Land Office).
 */
public record BannedListPacket(int purpose, String regionId, String regionName, Map<BannedBlocks.Rule, List<String>> lists) {
    public static final int STATUS = 0, OPEN = 1, INFO = 2;
    /** Most entries sent per rule; a list is a handful of tags and ids in practice. */
    static final int MAX_ENTRIES = 4096;

    public static void encode(BannedListPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.purpose);
        buf.writeUtf(msg.regionId, 128);
        buf.writeUtf(msg.regionName, 128);
        for (BannedBlocks.Rule rule : BannedBlocks.Rule.values()) {
            List<String> l = msg.lists.getOrDefault(rule, List.of());
            int n = Math.min(l.size(), MAX_ENTRIES);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                buf.writeUtf(l.get(i), 256);
            }
        }
    }

    public static BannedListPacket decode(FriendlyByteBuf buf) {
        int purpose = buf.readByte();
        String id = buf.readUtf(128), name = buf.readUtf(128);
        Map<BannedBlocks.Rule, List<String>> lists = new EnumMap<>(BannedBlocks.Rule.class);
        for (BannedBlocks.Rule rule : BannedBlocks.Rule.values()) {
            int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
            List<String> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                l.add(buf.readUtf(256));
            }
            lists.put(rule, l);
        }
        return new BannedListPacket(purpose, id, name, lists);
    }

    /** Whether anything is banned. */
    public boolean any() {
        return lists.values().stream().anyMatch(l -> !l.isEmpty());
    }

    public static void handle(BannedListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.BannedHereClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
