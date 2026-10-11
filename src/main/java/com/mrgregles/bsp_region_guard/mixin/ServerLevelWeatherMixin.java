package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code ice-form} and {@code snow-fall}: the weather tick may not freeze water or lay snow inside. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {
    @Redirect(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean bsp_region_guard$weather(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.ICE) && WorldFlags.denies(level, pos, Flags.ICE_FORM, RegionGuardConfig.MIXIN_WEATHER)) {
            return false;
        }
        if (state.is(Blocks.SNOW) && WorldFlags.denies(level, pos, Flags.SNOW_FALL, RegionGuardConfig.MIXIN_WEATHER)) {
            return false;
        }
        return level.setBlockAndUpdate(pos, state);
    }
}
