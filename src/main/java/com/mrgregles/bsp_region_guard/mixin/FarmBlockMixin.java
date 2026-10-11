package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code soil-dry}: dry farmland inside does not turn back into dirt. */
@Mixin(FarmBlock.class)
public abstract class FarmBlockMixin {
    @Redirect(method = "randomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FarmBlock;turnToDirt(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void bsp_region_guard$soilDry(Entity entity, BlockState state, Level level, BlockPos pos) {
        if (WorldFlags.denies(level, pos, Flags.SOIL_DRY, RegionGuardConfig.MIXIN_GROWTH)) {
            return;
        }
        FarmBlock.turnToDirt(entity, state, level, pos);
    }
}
