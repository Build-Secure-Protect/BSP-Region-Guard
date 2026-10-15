package com.mrgregles.bsp_region_guard.compat;

import net.minecraftforge.fml.ModList;

/**
 * The only class that touches BSP-Teleport.
 *
 * <p>BSP-Teleport is optional at runtime and carries out cross-server moves by listening for
 * {@code RegionTriggerEvent} (owner decision D33), so nothing is compiled against it; the one call made here goes
 * through reflection.
 */
public final class BspTeleport {
    public static final String MODID = "bsp_teleport";

    private BspTeleport() {
    }

    public static boolean isPresent() {
        return ModList.get().isLoaded(MODID);
    }

    /** BSP-Teleport's name for this server in the proxy ({@code BspTeleportApi.serverName()}), or null when absent or unreadable. */
    @org.jetbrains.annotations.Nullable
    public static String serverName() {
        if (!isPresent()) {
            return null;
        }
        try {
            Class<?> api = Class.forName("com.mrgregles.bsp_teleport.api.BspTeleportApi");
            Object instance = api.getMethod("get").invoke(null);
            return (String) api.getMethod("serverName").invoke(instance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
