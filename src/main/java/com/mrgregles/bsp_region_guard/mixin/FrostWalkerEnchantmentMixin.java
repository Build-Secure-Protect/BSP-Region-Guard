package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.FrostWalkerEnchantment;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code frosted-ice-form}: Frost Walker does nothing while its wearer stands inside. */
@Mixin(FrostWalkerEnchantment.class)
public abstract class FrostWalkerEnchantmentMixin {
    @Inject(method = "onEntityMoved", at = @At("HEAD"), cancellable = true)
    private static void bsp_region_guard$frost(LivingEntity entity, Level level, BlockPos pos, int enchantLevel, CallbackInfo ci) {
        if (WorldFlags.denies(level, pos, Flags.FROSTED_ICE_FORM, RegionGuardConfig.MIXIN_WEATHER)) {
            ci.cancel();
        }
    }
}
