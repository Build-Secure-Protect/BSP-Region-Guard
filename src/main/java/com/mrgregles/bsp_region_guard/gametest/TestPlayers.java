package com.mrgregles.bsp_region_guard.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.UUID;

/**
 * Server players for tests. {@link GameTestHelper#makeMockServerPlayerInLevel()} gives its connection no channel,
 * and Forge's packet listener touches the channel in its constructor, so this builds the player on an in-memory
 * netty channel instead. Packets sent to the player go nowhere.
 */
final class TestPlayers {
    private TestPlayers() {
    }

    static ServerPlayer mockServerPlayer(GameTestHelper h) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection); // registers and activates the handler, which gives the connection its channel
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), new GameProfile(UUID.randomUUID(), "rg-test-player"));
        player.connection = new ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, player);
        return player;
    }
}
