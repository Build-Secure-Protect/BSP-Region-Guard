package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.api.event.ClaimPurchasedEvent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the player just bought a plot (at a Land Office, or an outlined one; not an enlargement); open
 * the pop-up that offers to teleport there (owner decision D113). TELEPORT answers with the claim screen's own
 * {@code ClaimActionPacket} teleport, which checks that the plot is theirs.
 */
public record PlotBoughtPacket(String claimId, String name) {
    public static void encode(PlotBoughtPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.claimId, 64);
        buf.writeUtf(msg.name, 128);
    }

    public static PlotBoughtPacket decode(FriendlyByteBuf buf) {
        return new PlotBoughtPacket(buf.readUtf(64), buf.readUtf(128));
    }

    public static void handle(PlotBoughtPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_region_guard.client.RegionClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }

    /** After every purchase of a new plot, the buyer gets the pop-up. */
    public static void onPurchased(ClaimPurchasedEvent event) {
        if (event.enlarged() || !(event.getEntity() instanceof ServerPlayer player) || player.hasDisconnected()) {
            return;
        }
        var level = player.server.getLevel(event.dimension());
        var claim = level == null ? null : com.mrgregles.bsp_region_guard.region.RegionStore.get(level).get(event.claimId());
        RegionNetwork.sendTo(player, new PlotBoughtPacket(event.claimId(), claim == null ? event.claimId() : claim.name()));
    }
}
