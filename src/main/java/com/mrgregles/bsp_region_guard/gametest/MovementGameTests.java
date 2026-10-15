package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for the movement tracker, driven tick by tick with a mock server player: entry and exit refusals,
 * forced game mode, timed healing and feeding.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class MovementGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    private static Region define(GameTestHelper h, String id) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(9, 8, 9))), RegionType.PROTECTED);
        store(h).add(r);
        return r;
    }

    private static void finish(GameTestHelper h, Runnable body, String... ids) {
        try {
            body.run();
        } finally {
            for (String id : ids) {
                store(h).remove(id);
            }
            MovementTracker.clearAll();
        }
        h.succeed();
    }

    private static void moveTo(GameTestHelper h, ServerPlayer p, BlockPos rel) {
        BlockPos a = h.absolutePos(rel);
        p.setPos(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
    }

    private static boolean standsAt(GameTestHelper h, ServerPlayer p, BlockPos rel) {
        return p.blockPosition().equals(h.absolutePos(rel));
    }

    private static final BlockPos OUT = new BlockPos(12, 2, 5);
    private static final BlockPos NEAR = new BlockPos(10, 2, 5);
    private static final BlockPos IN = new BlockPos(8, 2, 5);
    private static final BlockPos DEEP = new BlockPos(5, 2, 5);

    @GameTest(template = EMPTY)
    public void entry_deny_puts_strangers_back_and_lets_members_in(GameTestHelper h) {
        ServerPlayer stranger = TestPlayers.mockServerPlayer(h);
        ServerPlayer member = TestPlayers.mockServerPlayer(h);
        finish(h, () -> {
            Region r = define(h, "m_entry");
            r.setFlag(Flags.ENTRY, State.DENY);
            r.addMember(member.getUUID());
            store(h).touch(r);
            long t = 0;
            for (ServerPlayer p : new ServerPlayer[]{stranger, member}) {
                moveTo(h, p, OUT);
                MovementTracker.tick(p, t++);
                moveTo(h, p, NEAR);
                MovementTracker.tick(p, t++);
                moveTo(h, p, IN);
                MovementTracker.tick(p, t++);
            }
            h.assertTrue(standsAt(h, stranger, NEAR), "the stranger is back where they were");
            h.assertTrue(standsAt(h, member, IN), "the member walked in");
            moveTo(h, stranger, IN);
            MovementTracker.tick(stranger, t++);
            h.assertTrue(standsAt(h, stranger, NEAR), "and is refused again next tick");
            r.setFlag(Flags.ENTRY, null);
            store(h).touch(r);
            moveTo(h, stranger, IN);
            MovementTracker.tick(stranger, t++);
            h.assertTrue(standsAt(h, stranger, IN), "with the flag cleared the stranger walks in");
        }, "m_entry");
        stranger.discard();
        member.discard();
    }

    @GameTest(template = EMPTY)
    public void exit_deny_keeps_strangers_in_unless_overridden(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        finish(h, () -> {
            Region r = define(h, "m_exit");
            r.setFlag(Flags.EXIT, State.DENY);
            store(h).touch(r);
            long t = 0;
            moveTo(h, p, DEEP);
            MovementTracker.tick(p, t++);
            moveTo(h, p, IN);
            MovementTracker.tick(p, t++);
            moveTo(h, p, NEAR);
            MovementTracker.tick(p, t++);
            h.assertTrue(standsAt(h, p, IN), "leaving on foot is refused");
            moveTo(h, p, new BlockPos(20, 2, 20));
            MovementTracker.tick(p, t++);
            h.assertTrue(standsAt(h, p, new BlockPos(20, 2, 20)), "leaving by teleport is allowed by default (exit-via-teleport)");
            r.setFlag(Flags.EXIT_VIA_TELEPORT, State.DENY);
            store(h).touch(r);
            moveTo(h, p, IN);
            MovementTracker.tick(p, t++);
            moveTo(h, p, new BlockPos(20, 2, 20));
            MovementTracker.tick(p, t++);
            h.assertTrue(standsAt(h, p, IN), "with exit-via-teleport denied the teleport is refused too");
            r.setFlag(Flags.EXIT_OVERRIDE, true);
            store(h).touch(r);
            moveTo(h, p, NEAR);
            MovementTracker.tick(p, t++);
            h.assertTrue(standsAt(h, p, NEAR), "exit-override lets everyone out");
        }, "m_exit");
        p.discard();
    }

    @GameTest(template = EMPTY)
    public void game_mode_is_forced_inside_and_restored_outside(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        finish(h, () -> {
            Region r = define(h, "m_mode");
            r.setFlag(Flags.GAME_MODE, GameType.ADVENTURE);
            store(h).touch(r);
            p.setGameMode(GameType.SURVIVAL);
            long t = 0;
            moveTo(h, p, OUT);
            MovementTracker.tick(p, t++);
            moveTo(h, p, IN);
            MovementTracker.tick(p, t++);
            h.assertTrue(p.gameMode.getGameModeForPlayer() == GameType.ADVENTURE, "adventure inside");
            moveTo(h, p, OUT);
            MovementTracker.tick(p, t++);
            h.assertTrue(p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "survival again outside");
        }, "m_mode");
        p.discard();
    }

    @GameTest(template = EMPTY)
    public void heal_and_feed_timers_run_inside(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        finish(h, () -> {
            Region r = define(h, "m_heal");
            r.setFlag(Flags.HEAL_DELAY, 2);
            r.setFlag(Flags.HEAL_AMOUNT, 4);
            r.setFlag(Flags.HEAL_MAX_HEALTH, 12.0);
            r.setFlag(Flags.FEED_DELAY, 1);
            r.setFlag(Flags.FEED_AMOUNT, -3);
            r.setFlag(Flags.FEED_MIN_HUNGER, 5);
            store(h).touch(r);
            p.setGameMode(GameType.SURVIVAL);
            p.setHealth(5f);
            p.getFoodData().setFoodLevel(7);
            moveTo(h, p, DEEP);
            MovementTracker.tick(p, 0);
            h.assertTrue(p.getHealth() == 9f, "first heal happens at once: 5 + 4");
            h.assertTrue(p.getFoodData().getFoodLevel() == 5, "first feed happens at once and stops at the minimum: 7 - 3 clamped to 5");
            MovementTracker.tick(p, 20);
            h.assertTrue(p.getHealth() == 9f, "one second later: no heal yet (delay 2 s)");
            MovementTracker.tick(p, 40);
            h.assertTrue(p.getHealth() == 12f, "two seconds later: 9 + 4 clamped to the max of 12");
            MovementTracker.tick(p, 80);
            h.assertTrue(p.getHealth() == 12f, "never above the max");
        }, "m_heal");
        p.discard();
    }
}
