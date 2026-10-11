package com.mrgregles.bsp_region_guard.region;

import org.jetbrains.annotations.Nullable;

/** What a region is for. The type decides which extra data it carries and which screens show it. */
public enum RegionType {
    /** An admin-drawn area with flags. The default. */
    PROTECTED,
    /** The per-world default region: no bounds, lowest priority, applies everywhere. One per world. */
    GLOBAL,
    /** A plot an admin has put up for sale at a fixed price. Becomes a {@link #CLAIM} when bought. */
    PLOT_FOR_SALE,
    /** An area where players may outline and buy their own plots at a cost per block. */
    OPEN_FOR_BUYING,
    /** A plot owned by a player. */
    CLAIM,
    /** An automated mine. */
    MINE;

    public String key() {
        return name().toLowerCase();
    }

    @Nullable
    public static RegionType byKey(String key) {
        for (RegionType t : values()) {
            if (t.key().equals(key)) {
                return t;
            }
        }
        return null;
    }
}
