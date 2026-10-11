package com.mrgregles.bsp_region_guard.compat;

import net.minecraftforge.fml.ModList;

/**
 * The only class that will touch BSP-Teleport.
 *
 * <p>BSP-Teleport is optional at runtime and has no API yet (request RG-T1 in the vault), so nothing is compiled
 * against it. Until the API exists this class only reports whether the mod is loaded.
 */
public final class BspTeleport {
    public static final String MODID = "bsp_teleport";

    private BspTeleport() {
    }

    public static boolean isPresent() {
        return ModList.get().isLoaded(MODID);
    }
}
