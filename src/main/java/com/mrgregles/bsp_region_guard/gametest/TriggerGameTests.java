package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.RegionGuardApi;
import com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import com.mrgregles.bsp_region_guard.trigger.Trigger;
import com.mrgregles.bsp_region_guard.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Region triggers: entering fires and moves the player locally; a listener can take the move over; the API sees regions. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class TriggerGameTests {
    private static final String EMPTY = "empty";

    @GameTest(template = EMPTY)
    public void entering_fires_the_trigger_and_moves_the_player(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Region r = new Region("tg_enter", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(9, 8, 9))), RegionType.PROTECTED);
        BlockPos target = h.absolutePos(new BlockPos(20, 3, 20));
        Trigger.store(r.typeData(), List.of(new Trigger(RegionTriggerEvent.Cause.ENTER, "", level.dimension().location().toString(),
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0, "Whoosh")));
        RegionStore.get(level).add(r);
        AtomicInteger fired = new AtomicInteger();
        Consumer<RegionTriggerEvent> listener = e -> {
            if (e.regionId().equals("tg_enter")) {
                fired.incrementAndGet();
            }
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegionTriggerEvent.class, listener);
        try {
            BlockPos out = h.absolutePos(new BlockPos(11, 2, 5)), in = h.absolutePos(new BlockPos(8, 2, 5));
            p.setPos(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
            MovementTracker.tick(p, 0);
            p.setPos(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
            MovementTracker.tick(p, 1);
            h.assertTrue(fired.get() == 1, "the enter trigger fired once: " + fired.get());
            h.assertTrue(p.blockPosition().equals(target), "the player was moved to the trigger's destination on this server: " + p.blockPosition());

            // a listener that cancels takes the move over
            Consumer<RegionTriggerEvent> taker = e -> e.setCanceled(true);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, false, RegionTriggerEvent.class, taker);
            try {
                p.setPos(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
                MovementTracker.tick(p, 2);
                p.setPos(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
                MovementTracker.tick(p, 3);
                h.assertTrue(p.blockPosition().equals(in), "a cancelling listener stops the local move");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(taker);
            }

            // the API facade
            h.assertTrue(RegionGuardApi.get().regions().regionIdsAt(level, in).contains("tg_enter"), "the API lists the region at the position");
            h.assertTrue(RegionGuardApi.get().regions().allows(level, in, Flags.PVP, null), "the API answers flag questions");
            h.assertFalse(RegionGuardApi.get().mines().isMine(level, "tg_enter"), "and knows it is not a mine");
            h.assertFalse(RegionGuardApi.get().publish("x", "y", "z", ""), "publish reports false when storage is off");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
            MovementTracker.clearAll();
            Triggers.clearAll();
            RegionStore.get(level).remove("tg_enter");
            p.discard();
        }
        h.succeed();
    }
}
