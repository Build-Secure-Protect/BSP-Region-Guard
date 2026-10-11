package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.WorldFlags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** {@code natural-health-regen}: food does not heal players inside (potions and golden apples still do). */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;heal(F)V"))
    private void bsp_region_guard$regen(Player player, float amount) {
        if (player instanceof ServerPlayer sp
                && WorldFlags.denies(sp.serverLevel(), sp.blockPosition(), Flags.NATURAL_HEALTH_REGEN, RegionGuardConfig.MIXIN_HUNGER)) {
            return;
        }
        player.heal(amount);
    }
}
