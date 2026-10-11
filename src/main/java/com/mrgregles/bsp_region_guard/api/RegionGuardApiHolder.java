package com.mrgregles.bsp_region_guard.api;

import net.minecraftforge.fml.ModList;

/** Holds the API instance. Package-private so callers only ever see {@link RegionGuardApi}. */
final class RegionGuardApiHolder {
    static final RegionGuardApi INSTANCE = new RegionGuardApi() {
        @Override
        public String modVersion() {
            return ModList.get().getModContainerById("bsp_region_guard")
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
        }

        @Override
        public ClaimService claims() {
            return com.mrgregles.bsp_region_guard.claim.Claims.INSTANCE;
        }
    };

    private RegionGuardApiHolder() {
    }
}
