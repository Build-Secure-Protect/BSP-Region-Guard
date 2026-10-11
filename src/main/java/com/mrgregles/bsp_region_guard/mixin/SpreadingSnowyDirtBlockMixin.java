package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code grass-growth} / {@code mycelium-spread}: the spreading block asks whether a dirt block can take it; inside,
 * the answer is no. Turning back to dirt in the dark is left alone.
 */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtBlockMixin {
    @Inject(method = "canPropagate", at = @At("HEAD"), cancellable = true)
    private static void bsp_region_guard$spread(BlockState state, LevelReader reader, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (reader instanceof Level level) {
            var flag = state.is(Blocks.MYCELIUM) ? Flags.MYCELIUM_SPREAD : Flags.GRASS_GROWTH;
            if (WorldFlags.denies(level, pos, flag, RegionGuardConfig.MIXIN_GROWTH)) {
                cir.setReturnValue(false);
            }
        }
    }
}
