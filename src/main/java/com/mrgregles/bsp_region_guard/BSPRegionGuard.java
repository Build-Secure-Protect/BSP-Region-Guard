package com.mrgregles.bsp_region_guard;

import com.mojang.logging.LogUtils;
import com.mrgregles.bsp_region_guard.command.RegionCommands;
import com.mrgregles.bsp_region_guard.compat.BspTeleport;
import com.mrgregles.bsp_region_guard.compat.LuckPermsBridge;
import com.mrgregles.bsp_region_guard.event.WandHandler;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.BlockProtection;
import com.mrgregles.bsp_region_guard.protection.ChatProtection;
import com.mrgregles.bsp_region_guard.protection.EntityProtection;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.protection.Protection;
import com.mrgregles.bsp_region_guard.protection.WorldProtection;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import com.mrgregles.bsp_region_guard.registry.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Entry point for BSP Region Guard: world protection, land claims, automated mines and region-driven teleports for
 * the Build Secure Protect modpack.
 *
 * <p>Features live in their own packages and are wired up here. Everything another mod may call is in
 * {@code com.mrgregles.bsp_region_guard.api}.
 */
@Mod(BSPRegionGuard.MODID)
public class BSPRegionGuard {
    /** Mod id. Must match {@code mod_id} in gradle.properties and {@code modId} in mods.toml. */
    public static final String MODID = "bsp_region_guard";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BSPRegionGuard(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.SERVER, RegionGuardConfig.SPEC);
        Flags.init();
        com.mrgregles.bsp_region_guard.protection.TotemRule.init();
        ModItems.register(context.getModEventBus());
        com.mrgregles.bsp_region_guard.network.RegionNetwork.register();

        MinecraftForge.EVENT_BUS.addListener(RegionCommands::onRegister);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.RegisterCommandsEvent e) -> com.mrgregles.bsp_region_guard.command.ClaimCommands.register(e.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener(WandHandler::onLeftClick);
        MinecraftForge.EVENT_BUS.addListener(WandHandler::onLogout);
        MinecraftForge.EVENT_BUS.addListener(com.mrgregles.bsp_region_guard.network.RegionSync::onLogin);
        MinecraftForge.EVENT_BUS.addListener(com.mrgregles.bsp_region_guard.network.RegionSync::onChangedDimension);
        MinecraftForge.EVENT_BUS.addListener(com.mrgregles.bsp_region_guard.network.RegionSync::onRespawn);
        com.mrgregles.bsp_region_guard.region.RegionStore.setChangeListener(com.mrgregles.bsp_region_guard.network.RegionSync::broadcast);
        MinecraftForge.EVENT_BUS.addListener(com.mrgregles.bsp_region_guard.mine.Mines::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
        registerProtection(MinecraftForge.EVENT_BUS);
    }

    /** Every enforcement listener. HIGH priority so a refusal comes before other mods act on the event. */
    public static void registerProtection(IEventBus bus) {
        bus.addListener(EventPriority.HIGH, BlockProtection::onBreak);
        bus.addListener(EventPriority.HIGH, BlockProtection::onPlace);
        bus.addListener(EventPriority.HIGH, BlockProtection::onRightClickBlock);
        bus.addListener(EventPriority.HIGH, BlockProtection::onRightClickItem);
        bus.addListener(EventPriority.HIGH, BlockProtection::onToolModification);
        bus.addListener(EventPriority.HIGH, BlockProtection::onFillBucket);
        bus.addListener(EventPriority.HIGH, BlockProtection::onBonemeal);
        bus.addListener(EventPriority.HIGH, BlockProtection::onTrample);

        bus.addListener(EventPriority.HIGH, EntityProtection::onLivingAttack);
        bus.addListener(EventPriority.HIGH, EntityProtection::onAttackEntity);
        bus.addListener(EventPriority.HIGH, EntityProtection::onEntityInteract);
        bus.addListener(EventPriority.HIGH, EntityProtection::onMount);
        bus.addListener(EventPriority.HIGH, EntityProtection::onSleep);
        bus.addListener(EventPriority.HIGH, EntityProtection::onProjectileImpact);
        bus.addListener(EventPriority.HIGH, EntityProtection::onEnderPearl);
        bus.addListener(EventPriority.HIGH, EntityProtection::onChorusFruit);
        bus.addListener(EventPriority.HIGH, EntityProtection::onItemToss);
        bus.addListener(EventPriority.HIGH, EntityProtection::onItemPickup);
        bus.addListener(EventPriority.HIGH, EntityProtection::onExperienceDrop);
        bus.addListener(EventPriority.HIGH, EntityProtection::onLivingHeal);

        bus.addListener(EventPriority.HIGH, WorldProtection::onExplosionStart);
        bus.addListener(EventPriority.HIGH, WorldProtection::onExplosionDetonate);
        bus.addListener(EventPriority.HIGH, WorldProtection::onMobGriefing);
        bus.addListener(EventPriority.HIGH, WorldProtection::onDestroyBlock);
        bus.addListener(EventPriority.HIGH, WorldProtection::onFinalizeSpawn);
        bus.addListener(EventPriority.HIGH, WorldProtection::onEntityJoin);
        bus.addListener(EventPriority.HIGH, WorldProtection::onCropGrow);
        bus.addListener(EventPriority.HIGH, WorldProtection::onSaplingGrow);
        bus.addListener(EventPriority.HIGH, WorldProtection::onPiston);

        bus.addListener(EventPriority.HIGH, ChatProtection::onCommand);
        bus.addListener(EventPriority.HIGH, ChatProtection::onChat);

        bus.addListener(MovementTracker::onPlayerTick);
        bus.addListener(MovementTracker::onLogin);
        bus.addListener(MovementTracker::onLogout);
        bus.addListener(MovementTracker::onDeath);
        bus.addListener(MovementTracker::onRespawn);
    }

    private void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("BSP Region Guard ready. LuckPerms: {}. BSP-Teleport: {}.",
                LuckPermsBridge.isPresent() ? "found" : "not found",
                BspTeleport.isPresent() ? "found" : "not found");
    }

    private void onServerStopped(ServerStoppedEvent event) {
        com.mrgregles.bsp_region_guard.selection.Selections.clearAll();
        MovementTracker.clearAll();
        com.mrgregles.bsp_region_guard.mine.Mines.clearAll();
        LuckPermsBridge.reset();
    }
}
