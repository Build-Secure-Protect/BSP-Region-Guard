package com.mrgregles.bsp_region_guard;

import net.minecraftforge.common.ForgeConfigSpec;

/** Settings for this player's own game (config/bsp_region_guard-client.toml), not the server's. */
public final class RegionGuardClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue UI_SCALE;
    public static final ForgeConfigSpec.BooleanValue BANNED_BUTTON;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("ui");
        UI_SCALE = b.comment("How big Region Guard's screens are drawn: 1 is the original size, 2 twice as big. A screen that would not fit the window",
                "is drawn as large as fits.").defineInRange("scale", 2.0, 1.0, 4.0);
        BANNED_BUTTON = b.comment("Show the BANNED button in the top left of the inventory while you stand where blocks or items are banned (D137).")
                .define("bannedButton", true);
        b.pop();
        SPEC = b.build();
    }

    /**
     * The scale a screen of {@code baseW} by {@code baseH} units is drawn at in a window of {@code w} by {@code h} GUI
     * units: {@code ui.scale} ({@code want}), or as large as fits with {@code margin} to spare, never below 1.
     */
    public static float screenScale(double want, int w, int h, int baseW, int baseH, float margin) {
        float fit = Math.min((w - margin) / baseW, (h - margin) / baseH);
        return Math.max(1, Math.min((float) want, fit));
    }

    private RegionGuardClientConfig() {
    }
}
