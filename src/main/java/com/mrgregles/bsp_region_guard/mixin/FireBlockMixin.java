package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code fire-spread}: fire may not appear on a new block inside; {@code fire-burn}: fire may not eat a block inside. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    /** The fire tick sets blocks twice: its own age at its own position (kept) and new fire on a neighbour (the spread). */
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean bsp_region_guard$spread(ServerLevel level, BlockPos target, BlockState newState, int flags,
                                             BlockState state, ServerLevel self, BlockPos firePos, RandomSource random) {
        if (!target.equals(firePos) && WorldFlags.denies(level, target, Flags.FIRE_SPREAD, RegionGuardConfig.MIXIN_FIRE)) {
            return false;
        }
        return level.setBlock(target, newState, flags);
    }

    @Redirect(method = "tryCatchFire", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean bsp_region_guard$burnToFire(Level level, BlockPos pos, BlockState newState, int flags) {
        if (WorldFlags.denies(level, pos, Flags.FIRE_BURN, RegionGuardConfig.MIXIN_FIRE)) {
            return false;
        }
        return level.setBlock(pos, newState, flags);
    }

    @Redirect(method = "tryCatchFire", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean bsp_region_guard$burnAway(Level level, BlockPos pos, boolean moving) {
        if (WorldFlags.denies(level, pos, Flags.FIRE_BURN, RegionGuardConfig.MIXIN_FIRE)) {
            return false;
        }
        return level.removeBlock(pos, moving);
    }
}
