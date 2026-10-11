package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code soil-dry}: farmland inside keeps its state (it neither dries out nor turns back to dirt). */
@Mixin(FarmBlock.class)
public abstract class FarmBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$soilDry(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (WorldFlags.denies(level, pos, Flags.SOIL_DRY, RegionGuardConfig.MIXIN_GROWTH)) {
            ci.cancel();
        }
    }
}
