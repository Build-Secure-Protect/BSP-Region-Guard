package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.WeatheringCopperStairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code copper-fade}: copper stairs inside do not oxidise. */
@Mixin(WeatheringCopperStairBlock.class)
public abstract class WeatheringCopperStairBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$guard(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (WorldFlags.denies(level, pos, Flags.COPPER_FADE, RegionGuardConfig.MIXIN_GROWTH)) {
            ci.cancel();
        }
    }
}
