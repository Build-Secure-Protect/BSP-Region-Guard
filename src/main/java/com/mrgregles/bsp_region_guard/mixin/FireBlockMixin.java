package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code fire-spread}: a spot inside gets ignite odds of zero, so fire never picks it; {@code fire-burn}: a block
 * inside is never set alight or consumed. Both patch methods that exist unchanged on Forge and on Mohist (which
 * rewrites the block-setting calls to fire Bukkit events, so redirects on those would not match).
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Inject(method = "getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$spread(LevelReader reader, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (reader instanceof Level level && WorldFlags.denies(level, pos, Flags.FIRE_SPREAD, RegionGuardConfig.MIXIN_FIRE)) {
            cir.setReturnValue(0);
        }
    }

    // tryCatchFire is a Forge-added method (vanilla has checkBurnOut without the face), so it has no obfuscation mapping: remap = false
    @Inject(method = "tryCatchFire", remap = false, at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$burn(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face, CallbackInfo ci) {
        if (WorldFlags.denies(level, pos, Flags.FIRE_BURN, RegionGuardConfig.MIXIN_FIRE)) {
            ci.cancel();
        }
    }
}
