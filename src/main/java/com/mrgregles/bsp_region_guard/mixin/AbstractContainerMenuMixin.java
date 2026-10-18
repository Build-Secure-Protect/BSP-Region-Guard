package com.mrgregles.bsp_region_guard.mixin;

import com.mrgregles.bsp_region_guard.protection.BannedBlocks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code deny-carry}: inside such a region a banned item cannot be taken out of a container, the ender chest included. */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void bsp_region_guard$clicked(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        if (BannedBlocks.refusesClick((AbstractContainerMenu) (Object) this, slotId, button, type, player)) {
            ci.cancel();
        }
    }
}
