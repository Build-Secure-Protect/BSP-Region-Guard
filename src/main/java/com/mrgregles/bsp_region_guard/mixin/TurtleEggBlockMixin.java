package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.Protection;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code block-trampling}: turtle eggs inside are not trampled (players by membership, mobs by the flag). */
@Mixin(TurtleEggBlock.class)
public abstract class TurtleEggBlockMixin {
    @Inject(method = "destroyEgg", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$trample(Level level, BlockState state, BlockPos pos, Entity entity, int chance, CallbackInfo ci) {
        if (!(level instanceof ServerLevel server) || !com.mrgregles.bsp_region_guard.RegionGuardConfig.getOr(RegionGuardConfig.MIXIN_EGGS, true)) {
            return;
        }
        boolean ok = entity instanceof Player player
                ? Protection.mayPlayer(server, pos, Flags.BLOCK_TRAMPLING, player)
                : WorldFlags.allows(server, pos, Flags.BLOCK_TRAMPLING, RegionGuardConfig.MIXIN_EGGS);
        if (!ok) {
            ci.cancel();
        }
    }
}
