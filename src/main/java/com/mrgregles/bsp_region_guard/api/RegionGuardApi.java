package com.mrgregles.bsp_region_guard.api;

/**
 * Entry point of the BSP Region Guard API.
 *
 * <p>Obtain it with {@link #get()} after the mod has loaded (any time from {@code FMLCommonSetupEvent} on). Services
 * for regions, flags, claims, mines and teleport triggers are added here as they are built; each is documented in
 * {@code docs/API.md}.
 */
public interface RegionGuardApi {
    /** Version of this API contract, raised whenever a method is added or changed. */
    int API_VERSION = 1;

    /** The live API instance. */
    static RegionGuardApi get() {
        return RegionGuardApiHolder.INSTANCE;
    }

    /** The mod's version string, for example {@code 0.1.0}. */
    String modVersion();

    /** Land claims: listings, quotes, purchases, abandoning. */
    ClaimService claims();

    /** Regions and flags at positions. */
    RegionService regions();

    /** Mines. */
    MineService mines();

    /**
     * Publishes a message to the other servers through the shared storage (arrives there as
     * {@code RemoteMessageEvent}). Does nothing when storage is off; returns whether it was sent.
     */
    boolean publish(String kind, String dimension, String regionId, String payload);
}
