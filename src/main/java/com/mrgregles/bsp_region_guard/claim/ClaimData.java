package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.region.Region;
import net.minecraft.nbt.CompoundTag;

/**
 * The claim-related values kept in a region's type data. Prices are in Shatter Pieces, BSP-Core's bank unit (D31), or
 * with coins on, numbers of coins of the region's tier ({@link Prices}). What a claim cost is kept per currency:
 * {@code boughtFor} in pieces, {@code boughtCoins} in copper-coin units.
 */
public final class ClaimData {
    public static final String PRICE = "price", COST_PER_BLOCK = "costPerBlock", MIN_SIZE = "minSize", MAX_SIZE = "maxSize",
            BOUGHT_FOR = "boughtFor", BOUGHT_AT = "boughtAt", ORIGIN = "origin", ORIGINAL_PRICE = "originalPrice", PLAYER_FLAGS = "playerFlags",
            PLOT_LIMIT = "plotLimit", BOUGHT_COINS = "boughtCoins";
    public static final String ORIGIN_PLOT = "plot", ORIGIN_OPEN = "open";

    private ClaimData() {
    }

    public static long price(Region r) {
        return r.typeData().getLong(PRICE);
    }

    public static long costPerBlock(Region r) {
        return r.typeData().getLong(COST_PER_BLOCK);
    }

    /** The open area's own size limits, or 0 when the config decides. */
    public static int minSize(Region r) {
        return r.typeData().getInt(MIN_SIZE);
    }

    public static int maxSize(Region r) {
        return r.typeData().getInt(MAX_SIZE);
    }

    /**
     * The most plots one player may own inside this region (D100), or -1 for no limit. On the world default it takes the
     * place of the config's {@code maxPerPlayer} (rank limits can still raise it).
     */
    public static int plotLimit(Region r) {
        return r.typeData().contains(PLOT_LIMIT) ? r.typeData().getInt(PLOT_LIMIT) : -1;
    }

    public static long boughtFor(Region r) {
        return r.typeData().getLong(BOUGHT_FOR);
    }

    /** What was paid in Shatter Coins (D98), in copper-coin units; refunds pay it back as coins. */
    public static long boughtCoins(Region r) {
        return r.typeData().getLong(BOUGHT_COINS);
    }

    public static long boughtAt(Region r) {
        return r.typeData().getLong(BOUGHT_AT);
    }

    public static String origin(Region r) {
        return r.typeData().getString(ORIGIN);
    }

    /** Sets a number (stored as a long; values saved earlier as ints read back the same); 0 or less removes it. */
    public static void set(Region r, String key, long value) {
        CompoundTag t = r.typeData();
        if (value <= 0) {
            t.remove(key);
        } else {
            t.putLong(key, value);
        }
    }
}
