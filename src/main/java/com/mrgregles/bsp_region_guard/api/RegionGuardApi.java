package com.mrgregles.bsp_region_guard.api;

/**
 * Entry point of the BSP Region Guard API.
 *
 * <p>Obtain it with {@link #get()} once the mod has loaded (any time from {@code FMLCommonSetupEvent} on); call the
 * services on the server thread. A mod that treats Region Guard as optional checks
 * {@code ModList.get().isLoaded("bsp_region_guard")} first and keeps every call in one class. Everything is described
 * in {@code docs/API.md}; the Forge events are in the {@code event} subpackage.
 */
public interface RegionGuardApi {
    /**
     * Version of this API contract, raised whenever a method is added or changed. 2 (2026-10-14): claims paid from the
     * BSP Core bank in Shatter Pieces with purchases answering through a callback; {@code Cuboid}, {@code Exemption}
     * and {@code Border} in this package; {@code regions().bounds} and {@code regions().border}. 3 (2026-10-15):
     * {@code RegionTriggerEvent.Cause.EDGE} for travel across a linked world edge. 4 (2026-10-15): for BSP Core's Land
     * Office, {@code claims().selection}, {@code claims().claimsNear} and {@code bounds()} on the two claim events.
     * 5 (2026-10-15): {@link #serverId()}. 6 (2026-10-23): plots priced in Shatter Coins, {@code claims().paysInCoins}
     * and {@code claims().priceText}.
     */
    int API_VERSION = 6;

    /** The live API instance. */
    static RegionGuardApi get() {
        return RegionGuardApiHolder.INSTANCE;
    }

    /** The mod's version string, for example {@code 0.1.0}. */
    String modVersion();

    /**
     * This server's name in the shared storage ({@code storage.serverId}), which must equal its Velocity server name.
     * Given even when shared storage is off, so a mod can compare it with its own name for this server.
     */
    String serverId();

    /** Land claims: listings, quotes, purchases, enlarging, abandoning. */
    ClaimService claims();

    /** Regions and flags at positions, the world border, flag registration and exemptions. */
    RegionService regions();

    /** Automated mines: whether a region is one, resetting it, its countdown. */
    MineService mines();

    /**
     * Publishes a message to the other servers through the shared storage; it arrives there as
     * {@code RemoteMessageEvent}, through Redis within milliseconds when configured and always through the MySQL message
     * table (each message is handled once). Safe to call while the server is stopping: queued messages are sent before
     * the storage closes.
     *
     * @param kind      what the message is, prefixed with the mod's id (for example {@code bsp_teleport.landing})
     * @param dimension a world id, or empty
     * @param regionId  a region id, or empty
     * @param payload   any text
     * @return false when shared storage is off (nothing is sent)
     */
    boolean publish(String kind, String dimension, String regionId, String payload);
}
