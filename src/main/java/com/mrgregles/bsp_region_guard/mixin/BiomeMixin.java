package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@code ice-form} and {@code snow-fall}: the weather tick asks the biome whether water freezes or snow settles; inside, the answer is no. */
@Mixin(Biome.class)
public abstract class BiomeMixin {
    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$freeze(LevelReader reader, BlockPos pos, boolean checkNeighbours, CallbackInfoReturnable<Boolean> cir) {
        if (reader instanceof Level level && WorldFlags.denies(level, pos, Flags.ICE_FORM, RegionGuardConfig.MIXIN_WEATHER)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldSnow", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$snow(LevelReader reader, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (reader instanceof Level level && WorldFlags.denies(level, pos, Flags.SNOW_FALL, RegionGuardConfig.MIXIN_WEATHER)) {
            cir.setReturnValue(false);
        }
    }
}
