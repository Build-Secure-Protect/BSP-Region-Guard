package com.mrgregles.bsp_region_guard.network;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** The mod's channel: {@code bsp_region_guard:main}. The server decides, the client displays. */
public final class RegionNetwork {
    private static final String PROTOCOL = "6"; // 2: edge-link travel pop-up; 3: server switcher; 4: claim wipe in the screen; 5: banned items pop-up; 6: plot map tiles and screen
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(BSPRegionGuard.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private RegionNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(RegionListPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RegionListPacket::encode).decoder(RegionListPacket::decode)
                .consumerMainThread(RegionListPacket::handle).add();
        CHANNEL.messageBuilder(RegionDetailPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RegionDetailPacket::encode).decoder(RegionDetailPacket::decode)
                .consumerMainThread(RegionDetailPacket::handle).add();
        CHANNEL.messageBuilder(SelectionPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SelectionPacket::encode).decoder(SelectionPacket::decode)
                .consumerMainThread(SelectionPacket::handle).add();
        CHANNEL.messageBuilder(ClaimOpenPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ClaimOpenPacket::encode).decoder(ClaimOpenPacket::decode)
                .consumerMainThread(ClaimOpenPacket::handle).add();
        CHANNEL.messageBuilder(ClaimActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ClaimActionPacket::encode).decoder(ClaimActionPacket::decode)
                .consumerMainThread(ClaimActionPacket::handle).add();
        CHANNEL.messageBuilder(RegionActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RegionActionPacket::encode).decoder(RegionActionPacket::decode)
                .consumerMainThread(RegionActionPacket::handle).add();
        CHANNEL.messageBuilder(EdgePromptPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(EdgePromptPacket::encode).decoder(EdgePromptPacket::decode)
                .consumerMainThread(EdgePromptPacket::handle).add();
        CHANNEL.messageBuilder(EdgeAnswerPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(EdgeAnswerPacket::encode).decoder(EdgeAnswerPacket::decode)
                .consumerMainThread(EdgeAnswerPacket::handle).add();
        CHANNEL.messageBuilder(ClaimWipePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ClaimWipePacket::encode).decoder(ClaimWipePacket::decode)
                .consumerMainThread(ClaimWipePacket::handle).add();
        CHANNEL.messageBuilder(BannedPopupPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BannedPopupPacket::encode).decoder(BannedPopupPacket::decode)
                .consumerMainThread(BannedPopupPacket::handle).add();
        CHANNEL.messageBuilder(MapTilesRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MapTilesRequestPacket::encode).decoder(MapTilesRequestPacket::decode)
                .consumerMainThread(MapTilesRequestPacket::handle).add();
        CHANNEL.messageBuilder(MapTilesPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MapTilesPacket::encode).decoder(MapTilesPacket::decode)
                .consumerMainThread(MapTilesPacket::handle).add();
        CHANNEL.messageBuilder(PlotMapActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PlotMapActionPacket::encode).decoder(PlotMapActionPacket::decode)
                .consumerMainThread(PlotMapActionPacket::handle).add();
        CHANNEL.messageBuilder(PlotMapStatePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlotMapStatePacket::encode).decoder(PlotMapStatePacket::decode)
                .consumerMainThread(PlotMapStatePacket::handle).add();
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
