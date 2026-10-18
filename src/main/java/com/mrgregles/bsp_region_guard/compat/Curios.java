package com.mrgregles.bsp_region_guard.compat;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The only class that touches Curios, which is optional: the items a player wears in Curios slots, read by
 * reflection ({@code CuriosApi.getCuriosInventory(entity)} then {@code getEquippedCurios()}, Curios 5.x for 1.20.1),
 * so Region Guard neither compiles against Curios nor fails without it. Any error is logged once and treated as
 * "nothing worn".
 */
public final class Curios {
    private static Method inventory, equipped;
    private static boolean failed;

    private Curios() {
    }

    public static List<ItemStack> equipped(LivingEntity entity) {
        List<ItemStack> out = new ArrayList<>();
        if (failed || !ModList.get().isLoaded("curios")) {
            return out;
        }
        try {
            if (inventory == null) {
                inventory = Class.forName("top.theillusivec4.curios.api.CuriosApi").getMethod("getCuriosInventory", LivingEntity.class);
            }
            Object lazy = inventory.invoke(null, entity);
            Optional<?> handler = lazy instanceof LazyOptional<?> lo ? lo.resolve() : Optional.empty();
            if (handler.isEmpty()) {
                return out;
            }
            if (equipped == null) {
                equipped = handler.get().getClass().getMethod("getEquippedCurios");
            }
            if (equipped.invoke(handler.get()) instanceof IItemHandler items) {
                for (int i = 0; i < items.getSlots(); i++) {
                    out.add(items.getStackInSlot(i));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            BSPRegionGuard.LOGGER.warn("Could not read Curios slots ({}); banned items worn there are not checked.", e.toString());
        }
        return out;
    }
}
