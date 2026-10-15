package com.mrgregles.bsp_region_guard.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Client-only registrations: the region renderer and the "show regions" key. Never referenced from common code. */
@Mod.EventBusSubscriber(modid = BSPRegionGuard.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    public static final KeyMapping SHOW_REGIONS = new KeyMapping("key.bsp_region_guard.show_regions", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.bsp_region_guard");

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(SHOW_REGIONS);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.addListener(RegionRenderer::onRenderLevel);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onLoggingOut);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (SHOW_REGIONS.consumeClick()) {
            boolean on = RegionClient.toggleShowRegions();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(on ? Tr.t("regions_shown") : Tr.t("regions_hidden")), true);
            }
        }
    }

    private static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        RegionClient.clear();
    }
}
