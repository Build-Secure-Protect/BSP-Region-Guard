package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.protection.EntityProtection;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code natural-health-regen}: marks the food tick while it runs, so the heal event handler in
 * {@link EntityProtection} knows a heal is natural regeneration (potions and golden apples are untouched).
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void bsp_region_guard$begin(Player player, CallbackInfo ci) {
        EntityProtection.beginFoodTick(player);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void bsp_region_guard$end(Player player, CallbackInfo ci) {
        EntityProtection.endFoodTick();
    }
}
