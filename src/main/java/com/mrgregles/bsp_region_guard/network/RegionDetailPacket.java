package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.region.Cuboid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: everything the screen shows about one region. Flag values travel as the text the flag formats
 * them to (the same text the commands accept), so the client never needs the value types. {@code inherited} holds
 * values that come from the parent chain and are not set on the region itself. {@code data} is the region's type
 * data (price, cost per block, who bought it) as text.
 */
public record RegionDetailPacket(String id, String name, String type, int priority, @Nullable String parent, @Nullable Cuboid bounds,
                                 List<Person> owners, List<Person> members, List<String> ranks,
                                 Map<String, String> flags, Map<String, String> inherited, List<String> availableRanks,
                                 int version, long updatedAt, Map<String, String> data) {

    public record Person(UUID uuid, String name) {
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(uuid);
            buf.writeUtf(name, 64);
        }

        static Person read(FriendlyByteBuf buf) {
            return new Person(buf.readUUID(), buf.readUtf(64));
        }
    }

    public static void encode(RegionDetailPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.id, 64);
        buf.writeUtf(msg.name, 128);
        buf.writeUtf(msg.type, 32);
        buf.writeVarInt(msg.priority);
        buf.writeNullable(msg.parent, (b, p) -> b.writeUtf(p, 64));
        buf.writeNullable(msg.bounds, RegionListPacket::writeCuboid);
        buf.writeCollection(msg.owners, (b, p) -> p.write(b));
        buf.writeCollection(msg.members, (b, p) -> p.write(b));
        buf.writeCollection(msg.ranks, (b, r) -> b.writeUtf(r, 64));
        buf.writeMap(msg.flags, (b, k) -> b.writeUtf(k, 64), (b, v) -> b.writeUtf(v, 1024));
        buf.writeMap(msg.inherited, (b, k) -> b.writeUtf(k, 64), (b, v) -> b.writeUtf(v, 1024));
        buf.writeCollection(msg.availableRanks, (b, r) -> b.writeUtf(r, 64));
        buf.writeVarInt(msg.version);
        buf.writeLong(msg.updatedAt);
        buf.writeMap(msg.data, (b, k) -> b.writeUtf(k, 64), (b, v) -> b.writeUtf(v, 256));
    }

    public static RegionDetailPacket decode(FriendlyByteBuf buf) {
        return new RegionDetailPacket(buf.readUtf(64), buf.readUtf(128), buf.readUtf(32), buf.readVarInt(),
                buf.readNullable(b -> b.readUtf(64)), buf.readNullable(RegionListPacket::readCuboid),
                buf.readList(Person::read), buf.readList(Person::read), buf.readList(b -> b.readUtf(64)),
                buf.readMap(LinkedHashMap::new, b -> b.readUtf(64), b -> b.readUtf(1024)),
                buf.readMap(LinkedHashMap::new, b -> b.readUtf(64), b -> b.readUtf(1024)),
                buf.readList(b -> b.readUtf(64)), buf.readVarInt(), buf.readLong(),
                buf.readMap(LinkedHashMap::new, b -> b.readUtf(64), b -> b.readUtf(256)));
    }

    public static void handle(RegionDetailPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.RegionClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
