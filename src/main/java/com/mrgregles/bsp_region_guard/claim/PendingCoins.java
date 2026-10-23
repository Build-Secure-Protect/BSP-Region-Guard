package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Coin refunds for players who were offline (D98): kept with the Overworld's data, in copper-coin units, and given
 * into the inventory at the player's next login on this server.
 */
public final class PendingCoins extends SavedData {
    private static final String NAME = BSPRegionGuard.MODID + "_pending_coins";
    private final Map<UUID, Long> owed = new HashMap<>();

    public static PendingCoins get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PendingCoins::load, PendingCoins::new, NAME);
    }

    /** Pays coins worth {@code copper} to the player now if they are online, else at their next login. */
    public static void refund(MinecraftServer server, UUID player, long copper) {
        if (copper <= 0) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null && BspCore.giveCoins(online, copper)) {
            return;
        }
        PendingCoins data = get(server);
        data.owed.merge(player, copper, Claims::saturatedAdd);
        data.setDirty();
    }

    /** What is waiting for this player, in copper-coin units. */
    public static long owed(MinecraftServer server, UUID player) {
        return get(server).owed.getOrDefault(player, 0L);
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PendingCoins data = get(player.server);
        Long copper = data.owed.get(player.getUUID());
        if (copper != null && BspCore.giveCoins(player, copper)) {
            data.owed.remove(player.getUUID());
            data.setDirty();
            player.sendSystemMessage(Component.literal("Region Guard paid you " + Prices.worthText(copper) + " back for a plot you gave up or lost."));
        }
    }

    private static PendingCoins load(CompoundTag tag) {
        PendingCoins data = new PendingCoins();
        CompoundTag owed = tag.getCompound("owed");
        for (String key : owed.getAllKeys()) {
            try {
                data.owed.put(UUID.fromString(key), owed.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // a broken entry; skipped
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag owed = new CompoundTag();
        this.owed.forEach((uuid, copper) -> owed.putLong(uuid.toString(), copper));
        tag.put("owed", owed);
        return tag;
    }
}
