package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.enchantment.FrostWalkerEnchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code frosted-ice-form}: Frost Walker boots do not freeze water inside. */
@Mixin(FrostWalkerEnchantment.class)
public abstract class FrostWalkerEnchantmentMixin {
    @Redirect(method = "onEntityMoved", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private static boolean bsp_region_guard$frost(Level level, BlockPos pos, BlockState ice) {
        if (WorldFlags.denies(level, pos, Flags.FROSTED_ICE_FORM, RegionGuardConfig.MIXIN_WEATHER)) {
            return false;
        }
        return level.setBlockAndUpdate(pos, ice);
    }
}
