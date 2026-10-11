package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SculkSpreader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code sculk-growth}: a sculk catalyst inside does not spread. */
@Mixin(SculkSpreader.class)
public abstract class SculkSpreaderMixin {
    @Inject(method = "updateCursors", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$sculk(LevelAccessor level, BlockPos pos, RandomSource random, boolean spreadVeins, CallbackInfo ci) {
        if (WorldFlags.denies(level, pos, Flags.SCULK_GROWTH, RegionGuardConfig.MIXIN_GROWTH)) {
            ci.cancel();
        }
    }
}
