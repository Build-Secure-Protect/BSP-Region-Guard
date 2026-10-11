package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code natural-hunger-drain}: players inside do not get hungry. */
@Mixin(Player.class)
public abstract class PlayerHungerMixin {
    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$exhaustion(float amount, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player
                && WorldFlags.denies(player.serverLevel(), player.blockPosition(), Flags.NATURAL_HUNGER_DRAIN, RegionGuardConfig.MIXIN_HUNGER)) {
            ci.cancel();
        }
    }
}
