package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code ice-melt}: ice inside does not melt. */
@Mixin(IceBlock.class)
public abstract class IceBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$guard(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (WorldFlags.denies(level, pos, Flags.ICE_MELT, RegionGuardConfig.MIXIN_WEATHER)) {
            ci.cancel();
        }
    }
}
