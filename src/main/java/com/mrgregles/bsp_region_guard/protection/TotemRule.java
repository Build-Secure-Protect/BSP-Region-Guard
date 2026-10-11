package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
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

    /** Claims are refused in PvP worlds unless the config says otherwise. */
    public static boolean claimsAllowedIn(ServerLevel level) {
        return !isPvpWorld(level) || RegionGuardConfig.getOr(RegionGuardConfig.CLAIMS_IN_PVP_WORLDS, false);
    }
}
