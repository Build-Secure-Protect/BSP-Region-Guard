package com.mrgregles.bsp_region_guard.api;

import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** What other mods ask about regions. Flags are looked up by name in {@code flag.FlagRegistry}. */
public interface RegionService {
    /** Ids of the regions at a position, highest priority first, the world default last if it exists. */
    List<String> regionIdsAt(ServerLevel level, BlockPos pos);

    /** Whether a state flag allows the action there, for a player ({@code null} for the world itself). Includes exemptions. */
    boolean allows(ServerLevel level, BlockPos pos, StateFlag flag, @Nullable Player player);

    /** A flag's value there (explicit or default), or {@code null}. */
    @Nullable
    <T> T value(ServerLevel level, BlockPos pos, Flag<T> flag);

    /** Registers a flag of another mod. Do it before the server starts. */
    <F extends Flag<?>> F registerFlag(F flag);

    /** Lets a mod lift region rules for a player in a spot, the way the Shatter Totem rule does. */
    void addExemption(RegionQuery.Exemption exemption);
}
