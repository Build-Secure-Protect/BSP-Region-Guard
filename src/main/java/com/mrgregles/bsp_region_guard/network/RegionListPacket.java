package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: every region of the player's world in one line each, for the list and the map. Sent when the
 * screen opens and after any change to a region's identity, bounds or people. {@code select} names the region the
 * screen should show; {@code open} asks the client to open the screen. {@code viewing} is the other server whose
 * regions these are (owner decision D44, read-only), or empty for this server; {@code servers} lists the servers the
 * admin may switch between, this one first (empty without shared storage). {@code admin}: whether this player runs
 * Region Guard (the region key starts on for admins and off for players, D105).
 */
public record RegionListPacket(ResourceLocation dimension, List<Summary> regions, @Nullable String select, boolean open, String viewing, List<String> servers,
                               boolean admin) {

    public RegionListPacket(ResourceLocation dimension, List<Summary> regions, @Nullable String select, boolean open) {
        this(dimension, regions, select, open, "", List.of(), true);
    }

    public boolean remote() {
        return !viewing.isEmpty();
    }

    /**
     * One region as the list shows it. {@code bounds} is null for the world default. {@code look} and {@code price}
     * (as players read it) are set for plots for sale and open areas, which every player sees in the world (D105).
     */
    public record Summary(String id, String name, String type, int priority, @Nullable String parent, @Nullable Cuboid bounds,
                          int owners, int members, int flagsSet, @Nullable com.mrgregles.bsp_region_guard.claim.PlotLook look, String price) {

        public Summary(String id, String name, String type, int priority, @Nullable String parent, @Nullable Cuboid bounds, int owners, int members, int flagsSet) {
            this(id, name, type, priority, parent, bounds, owners, members, flagsSet, null, "");
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUtf(id, 64);
            buf.writeUtf(name, 128);
            buf.writeUtf(type, 32);
            buf.writeVarInt(priority);
            buf.writeNullable(parent, (b, p) -> b.writeUtf(p, 64));
            buf.writeNullable(bounds, RegionListPacket::writeCuboid);
            buf.writeVarInt(owners);
            buf.writeVarInt(members);
            buf.writeVarInt(flagsSet);
            buf.writeNullable(look, (b, l) -> l.write(b));
            buf.writeUtf(price, 128);
        }

        static Summary read(FriendlyByteBuf buf) {
            return new Summary(buf.readUtf(64), buf.readUtf(128), buf.readUtf(32), buf.readVarInt(),
                    buf.readNullable(b -> b.readUtf(64)), buf.readNullable(RegionListPacket::readCuboid),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readNullable(com.mrgregles.bsp_region_guard.claim.PlotLook::read), buf.readUtf(128));
        }
    }

    static void writeCuboid(FriendlyByteBuf buf, Cuboid c) {
        buf.writeVarInt(c.minX());
        buf.writeVarInt(c.minY());
        buf.writeVarInt(c.minZ());
        buf.writeVarInt(c.maxX());
        buf.writeVarInt(c.maxY());
        buf.writeVarInt(c.maxZ());
    }

    static Cuboid readCuboid(FriendlyByteBuf buf) {
        return new Cuboid(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void encode(RegionListPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimension);
        buf.writeCollection(msg.regions, (b, s) -> s.write(b));
        buf.writeNullable(msg.select, (b, s) -> b.writeUtf(s, 64));
        buf.writeBoolean(msg.open);
        buf.writeUtf(msg.viewing, 64);
        buf.writeCollection(msg.servers, (b, s) -> b.writeUtf(s, 64));
        buf.writeBoolean(msg.admin);
    }

    public static RegionListPacket decode(FriendlyByteBuf buf) {
        return new RegionListPacket(buf.readResourceLocation(), buf.readList(Summary::read), buf.readNullable(b -> b.readUtf(64)), buf.readBoolean(),
                buf.readUtf(64), buf.readList(b -> b.readUtf(64)), buf.readBoolean());
    }

    public static void handle(RegionListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.RegionClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }

    @Nullable
    public Summary find(String id) {
        for (Summary s : regions) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }
}
