package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Set;

/**
 * The Build Secure Protect rule that binds every region: <em>it must never be possible to make a Shatter Totem
 * impossible to steal</em> (owner decision D4).
 *
 * <p>Two parts. Near another player's placed totem, in a world where totems live, protection does not stop a raider:
 * entry and exit, interaction with blocks, PvP and (by default) breaking and placing resolve as allowed for anyone who
 * is not that totem's owner. And land claims cannot be created in those worlds at all.
 */
public final class TotemRule {
    private static final Set<StateFlag> RAID_FLAGS = Set.of(Flags.ENTRY, Flags.EXIT, Flags.INTERACT, Flags.USE, Flags.CHEST_ACCESS,
            Flags.ENDERPEARL, Flags.CHORUS_FRUIT_TELEPORT, Flags.TELEPORT_IN, Flags.TELEPORT_OUT);
    private static final Set<StateFlag> BUILD_FLAGS = Set.of(Flags.BUILD, Flags.BLOCK_BREAK, Flags.BLOCK_PLACE, Flags.LIGHTER, Flags.TNT,
            Flags.OTHER_EXPLOSION, Flags.CREEPER_EXPLOSION, Flags.VEHICLE_PLACE, Flags.VEHICLE_DESTROY);

    private TotemRule() {
    }

    public static void init() {
        RegionQuery.addExemption(TotemRule::exempts);
    }

    /** True when the flag must not stop this player here because someone else's totem is within reach. */
    public static boolean exempts(ServerLevel level, BlockPos pos, StateFlag flag, Player player) {
        if (!isPvpWorld(level)) {
            return false;
        }
        boolean raid = RAID_FLAGS.contains(flag);
        boolean build = BUILD_FLAGS.contains(flag) && RegionGuardConfig.getOr(RegionGuardConfig.TOTEM_EXEMPT_BUILD, true);
        boolean pvp = (flag == Flags.PVP || flag == Flags.INVINCIBLE) && RegionGuardConfig.getOr(RegionGuardConfig.TOTEM_EXEMPT_PVP, true);
        if (!raid && !build && !pvp) {
            return false;
        }
        int radius = RegionGuardConfig.getOr(RegionGuardConfig.TOTEM_EXEMPT_RADIUS, -1);
        if (radius < 0) {
            radius = BspCore.stealRadius();
        }
        List<BspCore.PlacedTotem> near = BspCore.placedTotemsNear(level, pos, radius);
        for (BspCore.PlacedTotem totem : near) {
            if (!totem.owner().equals(player.getUUID())) {
                return true; // a raider near someone else's totem
            }
        }
        return false;
    }

    /** Whether this world is a PvP world: the config list, or BSP Core's allowed totem dimensions when the list is empty. */
    public static boolean isPvpWorld(ServerLevel level) {
        List<? extends String> configured = RegionGuardConfig.getOr(RegionGuardConfig.PVP_WORLDS, List.of());
        if (configured != null && !configured.isEmpty()) {
            ResourceLocation id = level.dimension().location();
            return configured.contains(id.toString()) || configured.contains(id.getPath());
        }
        return BspCore.isTotemWorld(level.dimension());
    }

    /** The world default's own answer to plots in a PvP world (D92): "allow" or "deny"; absent follows the config. */
    public static final String PLOTS_IN_PVP = "plotsInPvp";

    /** Why plots are allowed or refused in a world, as the BORDER tab shows it. */
    public enum PlotRule {
        NOT_PVP(true), PVP_DENIED(true), WORLD_ALLOWS(true), CONFIG_ALLOWS(true), WORLD_REFUSES(false), CONFIG_REFUSES(false);

        public final boolean allowed;

        PlotRule(boolean allowed) {
            this.allowed = allowed;
        }
    }

    /**
     * Plots in this world (D92, D93): always in a world that is not a PvP world, and in one whose world default denies
     * {@code pvp}; otherwise the world default's own setting, else the config's {@code claims.allowInPvpWorlds}.
     * Only plot placement reads the {@code pvp} flag here: the totem exemption above still uses {@link #isPvpWorld} (D94).
     */
    public static PlotRule plotRule(ServerLevel level) {
        if (!isPvpWorld(level)) {
            return PlotRule.NOT_PVP;
        }
        var global = RegionStore.get(level).global();
        if (global.flag(Flags.PVP) == State.DENY) {
            return PlotRule.PVP_DENIED;
        }
        String own = global.typeData().getString(PLOTS_IN_PVP);
        if (own.equals("allow") || own.equals("deny")) {
            return own.equals("allow") ? PlotRule.WORLD_ALLOWS : PlotRule.WORLD_REFUSES;
        }
        return RegionGuardConfig.getOr(RegionGuardConfig.CLAIMS_IN_PVP_WORLDS, false) ? PlotRule.CONFIG_ALLOWS : PlotRule.CONFIG_REFUSES;
    }

    /** Claims are refused in PvP worlds unless PvP is denied in the world or a setting allows them ({@link #plotRule}). */
    public static boolean claimsAllowedIn(ServerLevel level) {
        return plotRule(level).allowed;
    }

    /** The admin's answer when plots are refused here: what makes it a PvP world and how to allow plots. */
    public static String plotsRefused(ServerLevel level) {
        return "Plots are not allowed in " + level.dimension().location() + ": Shatter Totems can be placed here, so it counts as a PvP world. "
                + "Deny pvp on the world default, or allow plots on its BORDER tab (/rg claims pvp allow).";
    }
}
