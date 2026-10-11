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
}
