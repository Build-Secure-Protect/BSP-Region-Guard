package com.mrgregles.bsp_region_guard.event;

import com.mrgregles.bsp_region_guard.item.RegionWandItem;
import com.mrgregles.bsp_region_guard.registry.ModItems;
import com.mrgregles.bsp_region_guard.selection.Selection;
import com.mrgregles.bsp_region_guard.selection.Selections;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/** Left-click with the wand sets the first corner. The selection itself is drawn by the client ({@code client/RegionRenderer}). */
public final class WandHandler {
    private WandHandler() {
    }

    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getItemStack().is(ModItems.REGION_WAND.get())) {
            return;
        }
        event.setCanceled(true); // never break the block
        if (event.getEntity() instanceof ServerPlayer player) {
            Selection sel = Selections.of(player);
            sel.setPos1(player.serverLevel().dimension(), event.getPos());
            RegionWandItem.tell(player, sel, "First corner", event.getPos());
            Selections.sync(player);
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Selections.forget(event.getEntity().getUUID());
        com.mrgregles.bsp_region_guard.protection.Protection.forget(event.getEntity().getUUID());
    }
}
