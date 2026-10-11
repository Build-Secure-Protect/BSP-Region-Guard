package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code grass-growth} / {@code mycelium-spread}: grass and mycelium may not spread onto dirt inside. Turning back to
 * dirt in the dark (a change at the block's own position) is left alone.
 */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtBlockMixin {
    @Redirect(method = "randomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean bsp_region_guard$spread(ServerLevel level, BlockPos target, BlockState newState,
                                             BlockState state, ServerLevel self, BlockPos ownPos, RandomSource random) {
        if (!target.equals(ownPos)) {
            var flag = newState.is(Blocks.MYCELIUM) ? Flags.MYCELIUM_SPREAD : Flags.GRASS_GROWTH;
            if (WorldFlags.denies(level, target, flag, RegionGuardConfig.MIXIN_GROWTH)) {
                return false;
            }
        }
        return level.setBlockAndUpdate(target, newState);
    }
}
