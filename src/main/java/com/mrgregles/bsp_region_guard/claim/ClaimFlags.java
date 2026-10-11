package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Which flags a claim's owner may set (owner decision D27) and the Shatter Totem restriction on claims (D25).
 *
 * <p>The list comes from the plot itself, else from the open area it was outlined in, else from the config.
 */
public final class ClaimFlags {
    /** Flags whose deny (or, for invincible, allow) would get in the way of stealing a totem placed inside. */
    static final Set<Flag<?>> HINDER_WHEN_DENIED = Set.of(Flags.ENTRY, Flags.EXIT, Flags.BUILD, Flags.BLOCK_BREAK, Flags.BLOCK_PLACE,
            Flags.INTERACT, Flags.USE, Flags.CHEST_ACCESS, Flags.PVP, Flags.ENDERPEARL, Flags.CHORUS_FRUIT_TELEPORT, Flags.TELEPORT_IN, Flags.TELEPORT_OUT);

    private ClaimFlags() {
    }

    /** The flag names the owner of this claim may set. */
    public static List<String> allowedFor(RegionStore store, Region claim) {
        Region r = claim;
        for (int i = 0; i < 8 && r != null; i++) {
            String own = r.typeData().getString(ClaimData.PLAYER_FLAGS);
            if (!own.isEmpty()) {
                return split(own);
            }
            r = store.parentOf(r);
        }
        List<? extends String> cfg = RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_PLAYER_FLAGS, List.of());
        return cfg == null ? List.of() : new ArrayList<>(cfg);
    }

    public static boolean mayOwnerSet(RegionStore store, Region claim, String flagName) {
        return allowedFor(store, claim).contains(flagName);
    }

    static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split("[,\\s]+")) {
            if (!s.isEmpty() && FlagRegistry.get(s) != null) {
                out.add(s.toLowerCase());
            }
        }
        return out;
    }

    /** Stores an admin's list for a plot or area; {@code "default"} or empty clears it. Returns the names that were not flags. */
    public static List<String> setAllowed(Region plot, String csv) {
        List<String> unknown = new ArrayList<>();
        if (csv.isBlank() || csv.trim().equalsIgnoreCase("default")) {
            plot.typeData().remove(ClaimData.PLAYER_FLAGS);
            return unknown;
        }
        List<String> good = new ArrayList<>();
        for (String s : csv.split("[,\\s]+")) {
            if (s.isEmpty()) {
                continue;
            }
            if (FlagRegistry.get(s) == null) {
                unknown.add(s);
            } else {
                good.add(s.toLowerCase());
            }
        }
        plot.typeData().putString(ClaimData.PLAYER_FLAGS, String.join(",", good));
        return unknown;
    }

    // ------------------------------------------------------------------ totems in claims (D25)

    /** True when the claim's effective flags would get in the way of stealing a totem inside it. */
    public static boolean hindersSteal(RegionStore store, Region claim) {
        for (Flag<?> f : HINDER_WHEN_DENIED) {
            if (RegionQuery.effective(store, claim, f) == State.DENY) {
                return true;
            }
        }
        return RegionQuery.effective(store, claim, Flags.INVINCIBLE) == State.ALLOW;
    }

    /** Would this value on this flag make the claim hinder a steal? */
    public static boolean wouldHinder(Flag<?> flag, @Nullable Object value) {
        return HINDER_WHEN_DENIED.contains(flag) && value == State.DENY || flag == Flags.INVINCIBLE && value == State.ALLOW;
    }

    /** The claim at a position that would hinder a steal, or null: used when a totem is placed. */
    @Nullable
    public static Region hinderingClaimAt(ServerLevel level, BlockPos pos) {
        RegionStore store = RegionStore.get(level);
        for (Region r : store.at(pos)) {
            if (r.type() == RegionType.CLAIM && hindersSteal(store, r)) {
                return r;
            }
        }
        return null;
    }

    /** True when a placed Shatter Totem stands inside the claim. */
    public static boolean holdsTotem(ServerLevel level, Region claim) {
        Cuboid b = claim.bounds();
        if (b == null) {
            return false;
        }
        BlockPos centre = new BlockPos((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, (b.minZ() + b.maxZ()) / 2);
        int radius = Math.max(b.sizeX(), Math.max(b.sizeY(), b.sizeZ()));
        for (BspCore.PlacedTotem t : BspCore.placedTotemsNear(level, centre, radius)) {
            if (b.contains(t.pos())) {
                return true;
            }
        }
        return false;
    }
}
