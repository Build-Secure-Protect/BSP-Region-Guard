package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code lava-fire}: lava inside does not start fires (its random tick does nothing else). */
@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$lavaFire(Level level, BlockPos pos, FluidState state, RandomSource random, CallbackInfo ci) {
        if (WorldFlags.denies(level, pos, Flags.LAVA_FIRE, RegionGuardConfig.MIXIN_FIRE)) {
            ci.cancel();
        }
    }
}
