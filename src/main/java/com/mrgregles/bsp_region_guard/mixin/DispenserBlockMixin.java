package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code dispenser-use}: a dispenser or dropper may not fire into a block inside. */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$dispense(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        var state = level.getBlockState(pos);
        if (!state.hasProperty(DispenserBlock.FACING)) {
            return;
        }
        BlockPos target = pos.relative(state.getValue(DispenserBlock.FACING));
        if (WorldFlags.denies(level, target, Flags.DISPENSER_USE, RegionGuardConfig.MIXIN_DISPENSERS)) {
            ci.cancel();
        }
    }
}
