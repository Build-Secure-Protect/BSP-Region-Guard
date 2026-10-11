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
    public static final ForgeConfigSpec.IntValue CLAIM_MIN_SIZE, CLAIM_MAX_SIZE, CLAIM_MAX_PER_PLAYER, CLAIM_REFUND_PERCENT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CLAIM_LIMITS_BY_RANK, CLAIM_PLAYER_FLAGS;

    public static final ForgeConfigSpec.IntValue MINE_BLOCKS_PER_TICK;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> MINE_WARN_SECONDS;

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
        CLAIM_MIN_SIZE = b.comment("Smallest side of a plot a player outlines in an open area, in blocks.").defineInRange("minPlotSize", 5, 1, 512);
        CLAIM_MAX_SIZE = b.comment("Largest side of a plot a player outlines in an open area, in blocks.").defineInRange("maxPlotSize", 64, 1, 512);
        CLAIM_MAX_PER_PLAYER = b.comment("Claims a player may own per world, unless a rank below says more.").defineInRange("maxPerPlayer", 3, 0, 1000);
        CLAIM_LIMITS_BY_RANK = b.comment("Claims per world by LuckPerms group, as \"group:count\". The highest matching count wins.")
                .defineListAllowEmpty("limitsByRank", List.of("vip:5", "mvp:8"), o -> o instanceof String s && s.contains(":"));
        CLAIM_REFUND_PERCENT = b.comment("Share of the price paid back when a claim is abandoned.").defineInRange("abandonRefundPercent", 50, 0, 100);
        CLAIM_PLAYER_FLAGS = b.comment("Flags a claim's owner may set on their own claim.")
                .defineListAllowEmpty("playerFlags", List.of("entry", "chest-access", "use", "interact", "ride", "sleep", "greeting", "farewell",
                        "entry-deny-message", "mob-spawning", "pvp"), o -> o instanceof String);
        b.pop();

        b.comment("Automated mines.").push("mines");
        MINE_BLOCKS_PER_TICK = b.comment("Blocks written per tick while a mine refills. Lower it if resets cause lag.").defineInRange("blocksPerTick", 20000, 100, 1000000);
        MINE_WARN_SECONDS = b.comment("Seconds before a reset at which players near the mine are warned.")
                .defineListAllowEmpty("warnSeconds", List.of(60, 10), o -> o instanceof Integer i && i > 0);
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
