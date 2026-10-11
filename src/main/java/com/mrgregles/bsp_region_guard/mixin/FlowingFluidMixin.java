package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@code water-flow} / {@code lava-flow}: a fluid may not spread onto a block inside (checked at the destination). */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method = "canSpreadTo", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$canSpreadTo(BlockGetter getter, BlockPos from, BlockState fromState, Direction direction,
                                               BlockPos to, BlockState toState, FluidState toFluid, Fluid fluid,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (getter instanceof ServerLevel level) {
            var flag = fluid.is(FluidTags.LAVA) ? Flags.LAVA_FLOW : Flags.WATER_FLOW;
            if (WorldFlags.denies(level, to, flag, RegionGuardConfig.MIXIN_FLUIDS)) {
                cir.setReturnValue(false);
            }
        }
    }
}
