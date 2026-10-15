package com.mrgregles.bsp_region_guard.region;

import com.mrgregles.bsp_region_guard.api.Exemption;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Answers "what applies here?". The one place the resolution rules live.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>Take the regions containing the position, highest priority first; parents of an applicable region are left
 *       out (the child speaks for them); the global region comes last.</li>
 *   <li>A region's value for a flag is its own value, or its parent's (recursively) when it has none.</li>
 *   <li>Walk the regions by priority. At the first priority level where any region has a value, that level decides:
 *       for state flags {@code deny} beats {@code allow} within the level; for other flags the first region in id
 *       order wins.</li>
 *   <li>A state flag with a {@linkplain StateFlag#fallback() fallback} and no value of its own is resolved as the
 *       fallback flag.</li>
 *   <li>Still nothing: a {@linkplain StateFlag#memberBypass() member-bypass} flag allows owners and members of the
 *       top protecting region(s) and denies everyone else, or allows everyone when no region protects the spot;
 *       any other flag gives its default.</li>
 * </ol>
 * Regions with {@code passthrough} set to allow never count as protecting, so they can carry flags without claiming
 * the ground.
 */
public final class RegionQuery {
    private RegionQuery() {
    }

    // ------------------------------------------------------------------ applicable regions

    public static List<Region> at(ServerLevel level, BlockPos pos) {
        return RegionStore.get(level).at(pos);
    }

    /** True when no bounded region covers the column; cheap enough for random ticks. */
    public static boolean nothingAt(ServerLevel level, int x, int z) {
        return RegionStore.get(level).nothingAt(x, z);
    }

    // ------------------------------------------------------------------ values

    /** The explicit value set by the applicable regions (own or inherited), or empty when none sets it. */
    public static <T> Optional<T> explicit(ServerLevel level, BlockPos pos, Flag<T> flag) {
        RegionStore store = RegionStore.get(level);
        return explicit(store, store.at(pos), flag);
    }

    static <T> Optional<T> explicit(RegionStore store, List<Region> applicable, Flag<T> flag) {
        T found = null;
        Integer level = null;
        for (Region r : applicable) {
            if (level != null && r.priority() != level) {
                break; // a lower priority level: the level above has already decided
            }
            T v = effective(store, r, flag);
            if (v == null) {
                continue;
            }
            if (level == null) {
                level = r.priority();
                found = v;
            } else if (v == State.DENY) {
                found = v; // deny beats allow within one level
            }
            if (!(flag instanceof StateFlag)) {
                return Optional.of(found); // non-state flags: the first region in order wins
            }
        }
        return Optional.ofNullable(found);
    }

    /** A region's own value, or the nearest ancestor's. */
    @Nullable
    public static <T> T effective(RegionStore store, Region region, Flag<T> flag) {
        Region r = region;
        for (int i = 0; i < 32 && r != null; i++) {
            T v = r.flag(flag);
            if (v != null) {
                return v;
            }
            r = store.parentOf(r);
        }
        return null;
    }

    /** The value of a non-state flag at a position: explicit value, else the flag's default (may be null). */
    @Nullable
    public static <T> T value(ServerLevel level, BlockPos pos, Flag<T> flag) {
        return explicit(level, pos, flag).orElse(flag.defaultValue());
    }

    // ------------------------------------------------------------------ state flags

    private static final List<Exemption> EXEMPTIONS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addExemption(Exemption exemption) {
        EXEMPTIONS.add(exemption);
    }

    /** True when some exemption lifts the flag for this player here. Never applies to the world itself. */
    public static boolean isExempt(ServerLevel level, BlockPos pos, StateFlag flag, @Nullable Player player) {
        if (player == null) {
            return false;
        }
        for (Exemption e : EXEMPTIONS) {
            if (e.exempts(level, pos, flag, player)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Region flags that BSP-Core's Anti Totem zones also set, with BSP-Core's rule key. Until the two zone systems
     * merge (request RG-C2), a spot is denied when either one blocks it (owner decision D32).
     */
    private static final java.util.Map<StateFlag, String> BSP_CORE_ZONE_RULES = java.util.Map.of(
            Flags.TOTEM_PLACE, BspCore.ZONE_TOTEM_PLACE, Flags.TOTEM_DROP, BspCore.ZONE_TOTEM_DROP,
            Flags.TELEPORT_IN, BspCore.ZONE_TELEPORT_IN, Flags.TELEPORT_OUT, BspCore.ZONE_TELEPORT_OUT,
            Flags.SET_HOME, BspCore.ZONE_SET_HOME);

    /** Whether the flag allows the action at the position, for a player (or {@code null} for the world itself). */
    public static boolean allows(ServerLevel level, BlockPos pos, StateFlag flag, @Nullable Player player) {
        RegionStore store = RegionStore.get(level);
        List<Region> applicable = store.at(pos);
        if (resolve(store, applicable, flag, player) == State.ALLOW && !blockedByBspCoreZone(level, pos, flag)
                && !((flag == Flags.TELEPORT_IN || flag == Flags.SET_HOME) && com.mrgregles.bsp_region_guard.protection.WorldBorders.keepsOut(level, pos, player))) {
            return true;
        }
        return isExempt(level, pos, flag, player);
    }

    /** True when one of BSP-Core's Anti Totem zones blocks this flag's action here (D32). */
    public static boolean blockedByBspCoreZone(ServerLevel level, BlockPos pos, StateFlag flag) {
        String key = BSP_CORE_ZONE_RULES.get(flag);
        return key != null && BspCore.zoneBlocks(level, pos, key);
    }

    /** Same as {@link #allows} for something that is not a player (fire, fluid, a mob). */
    public static boolean allows(ServerLevel level, BlockPos pos, StateFlag flag) {
        return allows(level, pos, flag, null);
    }

    static State resolve(RegionStore store, List<Region> applicable, StateFlag flag, @Nullable Player player) {
        StateFlag f = flag;
        for (int i = 0; i < 8 && f != null; i++) {
            Optional<State> v = explicit(store, applicable, f);
            if (v.isPresent()) {
                return v.get();
            }
            f = f.fallback();
        }
        if (flag.memberBypass()) {
            return membership(applicable, player);
        }
        State def = flag.defaultValue();
        return def == null ? State.ALLOW : def;
    }

    /**
     * The build-family rule: nobody protecting the spot means anyone may act; otherwise only owners and members of
     * a region at the top protecting priority may, and admins always may.
     */
    static State membership(List<Region> applicable, @Nullable Player player) {
        Integer top = null;
        boolean member = false;
        for (Region r : applicable) {
            if (r.isGlobal() || r.flag(Flags.PASSTHROUGH) == State.ALLOW) {
                continue;
            }
            if (top == null) {
                top = r.priority();
            } else if (r.priority() != top) {
                break;
            }
            if (player != null && r.isMember(player.getUUID())) {
                member = true;
            }
        }
        if (top == null) {
            return State.ALLOW; // nothing protects this spot
        }
        if (member || (player != null && bypasses(player))) {
            return State.ALLOW;
        }
        return State.DENY;
    }

    private static final java.util.Set<java.util.UUID> BYPASS_OFF = new java.util.HashSet<>();

    /** Admins act as members everywhere and skip every player-action check, unless they switched it off with {@code /rg bypass}. */
    public static boolean bypasses(Player player) {
        return player instanceof ServerPlayer sp && BspCore.isAdmin(sp) && !BYPASS_OFF.contains(sp.getUUID());
    }

    /** Switches an admin's bypass on or off. Not saved: a restart turns it back on. */
    public static void setBypass(java.util.UUID player, boolean on) {
        if (on) {
            BYPASS_OFF.remove(player);
        } else {
            BYPASS_OFF.add(player);
        }
    }

    public static boolean bypassSetting(java.util.UUID player) {
        return !BYPASS_OFF.contains(player);
    }

    /** The message shown when an action is refused: the region's {@code deny-message}, else the default. */
    public static String denyMessage(ServerLevel level, BlockPos pos) {
        String m = value(level, pos, Flags.DENY_MESSAGE);
        return m == null ? "You can't do that here." : m;
    }
}
