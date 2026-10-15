package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.api.RegionGuardApi;
import com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
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

    /** Tab completion of a trigger's destination, word by word. */
    @GameTest(template = EMPTY)
    public void trigger_destinations_are_suggested_word_by_word(GameTestHelper h) {
        java.util.List<String> worlds = java.util.List.of("minecraft:overworld", "minecraft:the_nether");
        java.util.List<String> remote = java.util.List.of("survival-2/minecraft:overworld");
        int[] at = {120, 64, -40};
        var first = com.mrgregles.bsp_region_guard.command.TriggerSuggestions.suggest("enter", "", at, worlds, remote);
        h.assertTrue(first.start() == 0 && first.suggestions().containsAll(java.util.List.of("here", "minecraft:overworld", "overworld", "survival-2/minecraft:overworld")),
                "first word: here, this server's worlds (short too) and other servers' worlds: " + first.suggestions());
        var partial = com.mrgregles.bsp_region_guard.command.TriggerSuggestions.suggest("enter", "surv", at, worlds, remote);
        h.assertTrue(partial.suggestions().equals(java.util.List.of("survival-2/minecraft:overworld")), "narrowed by what is typed: " + partial.suggestions());
        var x = com.mrgregles.bsp_region_guard.command.TriggerSuggestions.suggest("enter", "survival-2/minecraft:overworld ", at, worlds, remote);
        h.assertTrue(x.start() == "survival-2/minecraft:overworld ".length() && x.suggestions().contains("120 64 -40") && x.suggestions().contains("120"),
                "then the admin's own position: " + x.suggestions());
        var z = com.mrgregles.bsp_region_guard.command.TriggerSuggestions.suggest("enter", "nether 120 64 ", at, worlds, remote);
        h.assertTrue(z.suggestions().equals(java.util.List.of("-40")), "the last coordinate: " + z.suggestions());
        var message = com.mrgregles.bsp_region_guard.command.TriggerSuggestions.suggest("enter", "here Welc", at, worlds, remote);
        h.assertTrue(message.suggestions().isEmpty(), "nothing after here: the rest is a free message");
        var timer = com.mrgregles.bsp_region_guard.command.TriggerSuggestions.suggest("timer", "", at, worlds, remote);
        h.assertTrue(timer.suggestions().contains("60") && !timer.suggestions().contains("here"), "a timer asks for seconds first: " + timer.suggestions());
        var afterSeconds = com.mrgregles.bsp_region_guard.command.TriggerSuggestions.suggest("timer", "60 ", at, worlds, remote);
        h.assertTrue(afterSeconds.suggestions().contains("here"), "then the destination");
        var console = com.mrgregles.bsp_region_guard.command.TriggerSuggestions.suggest("enter", "overworld ", null, worlds, remote);
        h.assertTrue(console.suggestions().isEmpty(), "no position to offer from the console");
        h.succeed();
    }
}
