package com.mrgregles.bsp_region_guard;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Server config ({@code <world>/serverconfig/bsp_region_guard-server.toml}, synced to clients).
 *
 * <p>Only the general section exists yet. Sections for protection, claims, mines, storage and the mixin switches are
 * added with the features that use them.
 */
public final class RegionGuardConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;

    public static final ForgeConfigSpec.IntValue TOTEM_EXEMPT_RADIUS;
    public static final ForgeConfigSpec.BooleanValue TOTEM_EXEMPT_BUILD;
    public static final ForgeConfigSpec.BooleanValue TOTEM_EXEMPT_PVP;
    public static final ForgeConfigSpec.BooleanValue CLAIMS_IN_PVP_WORLDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PVP_WORLDS;

    public static final ForgeConfigSpec.BooleanValue MIXIN_FIRE;
    public static final ForgeConfigSpec.BooleanValue MIXIN_FLUIDS;
    public static final ForgeConfigSpec.BooleanValue MIXIN_WEATHER;
    public static final ForgeConfigSpec.BooleanValue MIXIN_GROWTH;
    public static final ForgeConfigSpec.BooleanValue MIXIN_DISPENSERS;
    public static final ForgeConfigSpec.BooleanValue MIXIN_FALLING_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue MIXIN_EGGS;
    public static final ForgeConfigSpec.BooleanValue MIXIN_HUNGER;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("General settings").push("general");
        DEBUG_LOGGING = b.comment("Log every protection decision. Noisy; for testing only.")
                .define("debugLogging", false);
        b.pop();

        b.comment("Shatter Totems (BSP Core). A region must never make a totem impossible to steal: near another player's placed totem,",
                "protection does not stop a raider.").push("totem");
        TOTEM_EXEMPT_RADIUS = b.comment("Blocks around a placed totem where regions do not stop raiders. -1 uses BSP Core's steal radius.")
                .defineInRange("exemptRadius", -1, -1, 64);
        TOTEM_EXEMPT_BUILD = b.comment("Also let raiders break and place blocks near the totem (otherwise only entry, interaction and PvP are exempt).")
                .define("exemptBuild", true);
        TOTEM_EXEMPT_PVP = b.comment("Never let a region switch PvP off near a placed totem.").define("exemptPvp", true);
        b.pop();

        b.comment("Land claims.").push("claims");
        CLAIMS_IN_PVP_WORLDS = b.comment("Allow claims in PvP worlds (where totems can be placed). The owner's rule is no.")
                .define("allowInPvpWorlds", false);
        PVP_WORLDS = b.comment("Which worlds count as PvP worlds, e.g. [\"minecraft:overworld\"]. Empty uses BSP Core's allowed totem dimensions.")
                .defineListAllowEmpty("pvpWorlds", List.of(), o -> o instanceof String);
        b.pop();

        b.comment("The flags that need patches into Minecraft's own code (mixins), in groups.",
                "Turn a group off if another mod conflicts with it; its flags then do nothing.").push("mixins");
        MIXIN_FIRE = b.comment("fire-spread, fire-burn, lava-fire").define("fire", true);
        MIXIN_FLUIDS = b.comment("water-flow, lava-flow").define("fluids", true);
        MIXIN_WEATHER = b.comment("snow-fall, snow-melt, ice-form, ice-melt, frosted-ice-form, frosted-ice-melt").define("weather", true);
        MIXIN_GROWTH = b.comment("grass-growth, mycelium-spread, vine-growth, mushroom-growth, sculk-growth, leaf-decay, rock-growth, soil-dry, coral-fade, copper-fade").define("growth", true);
        MIXIN_DISPENSERS = b.comment("dispenser-use").define("dispensers", true);
        MIXIN_FALLING_BLOCKS = b.comment("falling-blocks").define("fallingBlocks", true);
        MIXIN_EGGS = b.comment("block-trampling for turtle eggs").define("eggs", true);
        MIXIN_HUNGER = b.comment("natural-hunger-drain, natural-health-regen").define("hunger", true);
        b.pop();

        SPEC = b.build();
    }

    private RegionGuardConfig() {
    }

    /** Reads a config value, or the fallback when the config is not loaded yet (for example during startup). */
    public static <T> T getOr(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            return value.get();
        } catch (IllegalStateException | NullPointerException e) {
            return fallback;
        }
    }
}
