package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.region.Region;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * What plots cost in (D95 to D97): Shatter Pieces from the bank, or with {@code claims.currency = coins} Shatter Coins of
 * one tier from the player's inventory. A region's price and cost per block are the same numbers either way; the
 * currency decides what they count. A tier of {@code null} below means pieces.
 */
public final class Prices {
    /** The coin tier a plot or open area prices in (type data), when it does not use the config's. */
    public static final String COIN_TIER = "coinTier";

    private Prices() {
    }

    public static boolean coins() {
        return "coins".equals(RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_CURRENCY, "pieces")) && !BspCore.coinTiers().isEmpty();
    }

    /** The coin tier this region's prices are in, or null for pieces. */
    @Nullable
    public static String tier(@Nullable Region r) {
        if (!coins()) {
            return null;
        }
        List<String> tiers = BspCore.coinTiers();
        String own = r == null ? "" : r.typeData().getString(COIN_TIER);
        if (tiers.contains(own)) {
            return own;
        }
        String config = RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_COIN_TIER, "copper").toLowerCase(Locale.ROOT);
        return tiers.contains(config) ? config : tiers.get(0);
    }

    /** An amount as players read it: {@code 12,500 pieces} or {@code 5 gold coins}. */
    public static String text(@Nullable String tier, long amount) {
        if (tier == null) {
            return BspCore.money(amount);
        }
        return String.format(Locale.ROOT, "%,d %s coin%s", amount, tier, amount == 1 ? "" : "s");
    }

    /** {@code amount} coins of {@code tier} in copper-coin units, held at {@link Long#MAX_VALUE} rather than overflowing. */
    public static long worth(String tier, long amount) {
        return Claims.cost(amount, BspCore.coinValue(tier));
    }

    /** Coins worth {@code copper} copper-coin units, as players read it ({@code coins worth 12 copper}). */
    public static String worthText(long copper) {
        return String.format(Locale.ROOT, "coins worth %,d copper", copper);
    }

    /** Whether the player can pay {@code amount} in this tier (null: their bank balance). */
    public static boolean affordable(ServerPlayer player, @Nullable String tier, long amount) {
        return tier == null ? BspCore.balance(player) >= amount : BspCore.coinWorth(player) >= worth(tier, amount);
    }

    /** What the player has to pay with, as they read it. */
    public static String holding(ServerPlayer player, @Nullable String tier) {
        return tier == null ? "your bank holds " + BspCore.money(BspCore.balance(player)) : "you carry " + worthText(BspCore.coinWorth(player));
    }
}
