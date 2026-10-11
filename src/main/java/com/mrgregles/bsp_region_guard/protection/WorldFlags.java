package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The one call every mixin makes. Cheap when it has to be: a world with no regions in this column and no global
 * region answers from one hash lookup, which is what random ticks all over the server will hit.
 */
public final class WorldFlags {
    private WorldFlags() {
    }

    /** True when the world may do this here, or the mixin group is switched off, or this is not a server world. */
    public static boolean allows(LevelAccessor level, BlockPos pos, StateFlag flag, ForgeConfigSpec.BooleanValue group) {
        if (!(level instanceof ServerLevel server)) {
            return true;
        }
        if (!RegionGuardConfig.getOr(group, true)) {
            return true;
        }
        RegionStore store = RegionStore.get(server);
        if (!store.hasGlobal() && store.nothingAt(pos.getX(), pos.getZ())) {
            return true;
        }
        return RegionQuery.allows(server, pos, flag, null);
    }

    public static boolean denies(LevelAccessor level, BlockPos pos, StateFlag flag, ForgeConfigSpec.BooleanValue group) {
        return !allows(level, pos, flag, group);
    }
}
