package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code block-break} (and the world border, bug B26) for blocks another mod breaks for a player with
 * {@code Level.destroyBlock} instead of Minecraft's own breaking, which never fires Forge's break event. Just Hammers
 * breaks the blocks around the one mined this way, so they slipped past every region and the border. The breaker is the
 * player passed in; mobs, projectiles and null are left alone. An inject at the head, never a redirect: Mohist
 * rewrites block-setting calls (see the handoff's hard-won rules).
 */
@Mixin(Level.class)
public abstract class LevelDestroyBlockMixin {
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$destroyBlock(BlockPos pos, boolean drop, Entity breaker, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (breaker instanceof ServerPlayer player && (Object) this instanceof ServerLevel level
                && RegionGuardConfig.getOr(RegionGuardConfig.MIXIN_PLAYER_BREAKS, true)
                && !Protection.mayPlayer(level, pos, Flags.BLOCK_BREAK, player)) {
            cir.setReturnValue(false);
        }
    }
}
