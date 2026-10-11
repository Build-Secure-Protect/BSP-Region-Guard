package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.region.Region;
import net.minecraft.nbt.CompoundTag;

/** The claim-related values kept in a region's type data. Prices are in coin value (copper-coin units). */
public final class ClaimData {
    public static final String PRICE = "price", COST_PER_BLOCK = "costPerBlock", MIN_SIZE = "minSize", MAX_SIZE = "maxSize",
            BOUGHT_FOR = "boughtFor", BOUGHT_AT = "boughtAt", ORIGIN = "origin", ORIGINAL_PRICE = "originalPrice", PLAYER_FLAGS = "playerFlags";
    public static final String ORIGIN_PLOT = "plot", ORIGIN_OPEN = "open";

    private ClaimData() {
    }

    public static int price(Region r) {
        return r.typeData().getInt(PRICE);
    }

    public static int costPerBlock(Region r) {
        return r.typeData().getInt(COST_PER_BLOCK);
    }

    /** The open area's own size limits, or 0 when the config decides. */
    public static int minSize(Region r) {
        return r.typeData().getInt(MIN_SIZE);
    }

    public static int maxSize(Region r) {
        return r.typeData().getInt(MAX_SIZE);
    }

    public static int boughtFor(Region r) {
        return r.typeData().getInt(BOUGHT_FOR);
    }

    public static long boughtAt(Region r) {
        return r.typeData().getLong(BOUGHT_AT);
    }

    public static String origin(Region r) {
        return r.typeData().getString(ORIGIN);
    }

    public static void set(Region r, String key, int value) {
        CompoundTag t = r.typeData();
        if (value <= 0) {
            t.remove(key);
        } else {
            t.putInt(key, value);
        }
    }
}
