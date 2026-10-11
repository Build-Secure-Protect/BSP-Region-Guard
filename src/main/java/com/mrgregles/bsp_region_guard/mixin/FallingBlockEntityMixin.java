package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code falling-blocks}: sand, gravel and anvils may not land inside; they drop as items instead. This is the one
 * patch that redirects a block-setting call; Mohist rewrites that call for its Bukkit event, so there the patch is
 * skipped ({@code require = 0}) and the flag does nothing. Forge servers get the full rule.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
    @Redirect(method = "tick", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean bsp_region_guard$land(Level level, BlockPos pos, BlockState state, int flags) {
        if (WorldFlags.denies(level, pos, Flags.FALLING_BLOCKS, RegionGuardConfig.MIXIN_FALLING_BLOCKS)) {
            return false; // vanilla then drops the block as an item
        }
        return level.setBlock(pos, state, flags);
    }
}
