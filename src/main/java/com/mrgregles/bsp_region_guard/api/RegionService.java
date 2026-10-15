package com.mrgregles.bsp_region_guard.api;

import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Regions and flags, for other mods. Every call runs on the server thread and reads this server's regions only.
 *
 * <p>Flags are the constants in {@code com.mrgregles.bsp_region_guard.flag.Flags} (stable, as are {@code Flag},
 * {@code StateFlag} and {@code State} in that package); a flag of another mod is registered with
 * {@link #registerFlag}. How a flag is resolved: the regions at the position by priority, the first explicit value
 * wins, deny beats allow at equal priority, then parents, then the world default (the global region), then the
 * flag's default. Build-type flags let owners and members through; admins with bypass on pass player checks.
 */
public interface RegionService {
    /** Ids of the regions at a position, highest priority first, the world default ({@code __global__}) last if it exists. */
    List<String> regionIdsAt(ServerLevel level, BlockPos pos);

    /**
     * Whether a state flag allows the action there, for a player, or {@code null} for the world itself (fire, fluids,
     * mobs). Exemptions are included. For {@code teleport-in}, {@code teleport-out}, {@code set-home},
     * {@code totem-place} and {@code totem-drop} a BSP Core Anti Totem zone blocking the same rule also says no, and
     * {@code teleport-in} and {@code set-home} say no outside the world border.
     */
    boolean allows(ServerLevel level, BlockPos pos, StateFlag flag, @Nullable Player player);

    /** A flag's value there: the explicit value that wins, else the flag's default; {@code null} when neither exists. */
    @Nullable
    <T> T value(ServerLevel level, BlockPos pos, Flag<T> flag);

    /** A region's box, or {@code null} for an unknown id or the world default (which has no box). */
    @Nullable
    Cuboid bounds(ServerLevel level, String regionId);

    /** The world's border, or {@code null} when it has none. */
    @Nullable
    Border border(ServerLevel level);

    /** Registers a flag of another mod; it then appears in the admin screen. Call it before the server starts. Returns the flag. */
    <F extends Flag<?>> F registerFlag(F flag);

    /** Lets a mod lift region rules for a player in a spot, the way the Shatter Totem rule does. Register once, early. */
    void addExemption(Exemption exemption);
}
