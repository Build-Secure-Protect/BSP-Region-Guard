package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plot owner flags (owner decisions D159 to D168). Plots for sale, claims and open areas carry three lists of the
 * player flags:
 * <ul>
 *   <li><b>VISITORS</b>: the region's ordinary flags, for everyone who is not an owner or member. Inherit goes to the
 *       parent, then the world default, as for any region.</li>
 *   <li><b>OWNERS</b>: set by the admin. Inherit means <em>owners may</em> for the build flags, so the world default
 *       never reaches owners; for the other player flags it means "same as visitors".</li>
 *   <li><b>MEMBERS</b>: starting values set by the admin, changed by the owner. Inherit means "same as owners". A flag
 *       the OWNERS list denies is denied for members too, and members never get more than owners.</li>
 * </ul>
 * The OWNERS list and both member lists live in the region's type data under {@link #KEY}, so saving, the shared
 * storage and copying a plot carry them without extra code. A claim outlined in an open area takes the area's lists
 * where it has none of its own (it is the area's child).
 */
public final class OwnerFlags {
    /** The type data compound holding the lists. */
    public static final String KEY = "ownerFlags";
    /** List names, as the commands and the screen spell them. */
    public static final String VISITORS = "visitors", OWNERS = "owners", MEMBERS = "members";
    /** Inside {@link #KEY}: the admin's member values, and the owner's own choices for members. */
    public static final String MEMBER_START = "memberStart", MEMBER_SET = "memberSet";

    /**
     * Player flags outside the build family that also get owner and member values (D164). PvP is left out: a fight has
     * two players and is checked for the spot, not for one of them, so it keeps one value.
     */
    private static final Set<StateFlag> EXTRA = Set.of(Flags.ITEM_DROP, Flags.ITEM_PICKUP, Flags.ENDERPEARL, Flags.CHORUS_FRUIT_TELEPORT);

    private OwnerFlags() {
    }

    /** True for a flag that has owner and member values: the build family and {@link #EXTRA}. */
    public static boolean isPlayerFlag(Flag<?> flag) {
        return flag instanceof StateFlag sf && (sf.memberBypass() || EXTRA.contains(sf));
    }

    /** Every player flag, in registry order. */
    public static List<StateFlag> playerFlags() {
        List<StateFlag> out = new ArrayList<>();
        for (Flag<?> f : FlagRegistry.all()) {
            if (isPlayerFlag(f)) {
                out.add((StateFlag) f);
            }
        }
        return out;
    }

    /** Plots for sale, claims and open areas have OWNERS and MEMBERS lists; other regions keep one list (D160). */
    public static boolean hasLists(Region r) {
        return r.type() == RegionType.PLOT_FOR_SALE || r.type() == RegionType.CLAIM || r.type() == RegionType.OPEN_FOR_BUYING;
    }

    // ------------------------------------------------------------------ stored values

    /** {@code list}: {@link #OWNERS}, {@link #MEMBERS} (the admin's starting values) or {@code memberSet} (the owner's). */
    @Nullable
    public static State own(Region r, String list, StateFlag flag) {
        CompoundTag lists = r.typeData().getCompound(KEY);
        String v = lists.getCompound(storeKey(list)).getString(flag.name());
        return v.equals("allow") ? State.ALLOW : v.equals("deny") ? State.DENY : null;
    }

    /** Sets or clears ({@code null}) one value. The caller touches the store. */
    public static void set(Region r, String list, StateFlag flag, @Nullable State value) {
        CompoundTag lists = r.typeData().getCompound(KEY);
        CompoundTag one = lists.getCompound(storeKey(list));
        if (value == null) {
            one.remove(flag.name());
        } else {
            one.putString(flag.name(), value == State.ALLOW ? "allow" : "deny");
        }
        if (one.isEmpty()) {
            lists.remove(storeKey(list));
        } else {
            lists.put(storeKey(list), one);
        }
        if (lists.isEmpty()) {
            r.typeData().remove(KEY);
        } else {
            r.typeData().put(KEY, lists);
        }
    }

    /** The values one list sets on this region itself, flag name to "allow" or "deny". */
    public static Map<String, String> values(Region r, String list) {
        Map<String, String> out = new LinkedHashMap<>();
        CompoundTag one = r.typeData().getCompound(KEY).getCompound(storeKey(list));
        for (String k : one.getAllKeys()) {
            out.put(k, one.getString(k));
        }
        return out;
    }

    /** The owner's own choices for members go (selling back, a copy): members get the admin's starting values (D165, D168). */
    public static void resetMembers(Region r) {
        CompoundTag lists = r.typeData().getCompound(KEY);
        lists.remove(MEMBER_SET);
        if (lists.isEmpty()) {
            r.typeData().remove(KEY);
        } else {
            r.typeData().put(KEY, lists);
        }
    }

    /** The region's value in a list, or its parent's (an open area's lists reach the claims inside it). */
    @Nullable
    public static State inherited(RegionStore store, Region r, String list, StateFlag flag) {
        Region at = r;
        for (int i = 0; i < 32 && at != null; i++) {
            State v = own(at, list, flag);
            if (v != null) {
                return v;
            }
            at = store.parentOf(at);
        }
        return null;
    }

    private static String storeKey(String list) {
        return switch (list) {
            case OWNERS -> OWNERS;
            case MEMBERS -> MEMBER_START;
            case MEMBER_SET -> MEMBER_SET;
            default -> throw new IllegalArgumentException("Lists: owners, members.");
        };
    }

    /** "visitors", "owners" or "members" from what a player typed; null for anything else. */
    @Nullable
    public static String listName(String typed) {
        String t = typed.trim().toLowerCase(Locale.ROOT);
        return t.equals(VISITORS) || t.equals(OWNERS) || t.equals(MEMBERS) ? t : null;
    }

    // ------------------------------------------------------------------ resolution

    /** Where a player belongs at a spot: the plot that decides for them there, and whether they own it. */
    public record Home(Region region, boolean owner) {
    }

    /**
     * The plot at the top protecting priority where this player is an owner (preferred) or a member, or {@code null}
     * when they are a visitor everywhere here or the flag has no owner values.
     */
    @Nullable
    public static Home home(List<Region> applicable, StateFlag flag, @Nullable Player player) {
        if (player == null || !isPlayerFlag(flag)) {
            return null;
        }
        UUID id = player.getUUID();
        Integer top = null;
        Region member = null;
        for (Region r : applicable) {
            if (r.isGlobal() || r.flag(Flags.PASSTHROUGH) == State.ALLOW) {
                continue;
            }
            if (top == null) {
                top = r.priority();
            } else if (r.priority() != top) {
                break;
            }
            if (!hasLists(r)) {
                continue;
            }
            if (r.isOwner(id)) {
                return new Home(r, true);
            }
            if (member == null && r.isMember(id)) {
                member = r;
            }
        }
        return member == null ? null : new Home(member, false);
    }

    /**
     * The OWNERS list's answer for a flag: its own value or its fallback's (block-break falls back to build), else
     * {@code ALLOW} for the build family (owners may); {@code null} means "same as visitors".
     */
    @Nullable
    public static State ownersValue(RegionStore store, Region r, StateFlag flag) {
        for (StateFlag f = flag; f != null; f = f.fallback()) {
            State v = inherited(store, r, OWNERS, f);
            if (v != null) {
                return v;
            }
        }
        return flag.memberBypass() ? State.ALLOW : null;
    }

    /**
     * The MEMBERS answer before the owners cap; {@code null} = same as owners. The owner's choices are read along the
     * whole fallback chain first, so switching "members may build" off also beats an admin's starting value on
     * block-break; only then the admin's starting values.
     */
    @Nullable
    public static State membersValue(RegionStore store, Region r, StateFlag flag) {
        for (StateFlag f = flag; f != null; f = f.fallback()) {
            State v = own(r, MEMBER_SET, f);
            if (v != null) {
                return v;
            }
        }
        for (StateFlag f = flag; f != null; f = f.fallback()) {
            State v = inherited(store, r, MEMBERS, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** True when the owner may change this flag for members: a player flag the OWNERS list doesn't deny (D162). */
    public static boolean ownerMaySetForMembers(RegionStore store, Region r, StateFlag flag) {
        return isPlayerFlag(flag) && ownersValue(store, r, flag) != State.DENY;
    }

    // ------------------------------------------------------------------ plots made before these lists (D166)

    /**
     * Keeps a plot saved before these lists as it was: a build-flag deny that stopped its owners (its own, or one from
     * a parent that has no lists) is also put in its OWNERS list. On a bought claim the claim's own denies the buyer
     * could set are left out (owner, 2026-11-05): the buyer meant them for strangers. A parent with lists (an open
     * area) is kept the same way itself, and the claim inherits its OWNERS list. Runs once per region: from the save
     * file, from shared storage or from a wipe backup. Returns true when the region changed.
     */
    public static boolean migrate(RegionStore store, Region r) {
        if (!r.beforeOwnerFlags()) {
            return false;
        }
        r.ownerFlagsChecked();
        if (!hasLists(r)) {
            return false;
        }
        List<String> buyerMay = r.type() == RegionType.CLAIM ? ClaimFlags.allowedFor(store, r) : List.of();
        boolean any = false;
        for (StateFlag f : playerFlags()) {
            if (!f.memberBypass() || own(r, OWNERS, f) != null) {
                continue;
            }
            State v = r.flag(f);
            boolean deny = v == State.DENY && !buyerMay.contains(f.name());
            if (v == null) {
                for (Region p = store.parentOf(r); p != null && !hasLists(p); p = store.parentOf(p)) {
                    State up = p.flag(f);
                    if (up != null) {
                        deny = up == State.DENY;
                        break;
                    }
                }
            }
            if (deny) {
                set(r, OWNERS, f, State.DENY);
                any = true;
            }
        }
        return any;
    }

    /** {@link #migrate(RegionStore, Region)} for every region of a store; returns how many changed. */
    public static int migrate(RegionStore store) {
        int changed = 0;
        for (Region r : new ArrayList<>(store.all())) {
            if (migrate(store, r)) {
                changed++;
            }
        }
        return changed;
    }
}
