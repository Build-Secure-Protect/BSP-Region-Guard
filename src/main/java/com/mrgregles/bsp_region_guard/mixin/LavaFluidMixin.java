package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code lava-fire}: lava's random tick may not light a fire inside. */
@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {
    @Redirect(method = "randomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean bsp_region_guard$lavaFire(Level level, BlockPos pos, BlockState fire) {
        if (WorldFlags.denies(level, pos, Flags.LAVA_FIRE, RegionGuardConfig.MIXIN_FIRE)) {
            return false;
        }
        return level.setBlockAndUpdate(pos, fire);
    }
}
