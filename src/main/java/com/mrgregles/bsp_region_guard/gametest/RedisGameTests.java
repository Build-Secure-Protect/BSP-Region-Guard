package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.event.RemoteMessageEvent;
import com.mrgregles.bsp_region_guard.storage.MemoryBackend;
import com.mrgregles.bsp_region_guard.storage.RedisLink;
import com.mrgregles.bsp_region_guard.storage.RegionMirror;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Messages through Redis (owner decision D34), against {@link FakeRedis} on a real local socket: they arrive without
 * waiting for the table, the table's copy is dropped, an outage falls back to the table and the link comes back by
 * itself, and a wrong password is reported. Two mirrors on one in-memory backend play two servers.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class RedisGameTests {
    private static final String EMPTY = "empty";

    /** Two servers sharing a backend and a fake Redis; collects the events of one message kind. */
    private static final class Pair {
        final FakeRedis redis;
        final MemoryBackend backend = new MemoryBackend();
        final RegionMirror alpha, beta;
        final RedisLink.Settings settings;
        final List<RemoteMessageEvent> got = new CopyOnWriteArrayList<>();
        final Consumer<RemoteMessageEvent> listener;

        Pair(GameTestHelper h, String kind, String password) throws IOException {
            redis = FakeRedis.start("secret");
            alpha = RegionMirror.startWith(h.getLevel().getServer(), backend, "alpha");
            beta = RegionMirror.startWith(h.getLevel().getServer(), backend, "beta");
            alpha.drain();
            beta.drain();
            settings = new RedisLink.Settings("127.0.0.1", redis.port(), "", password, "test-" + kind);
            alpha.connectRedis(settings);
            beta.connectRedis(settings);
            listener = e -> {
                if (kind.equals(e.kind())) {
                    got.add(e);
                }
            };
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RemoteMessageEvent.class, listener);
        }

        boolean connected() {
            return redis.subscribers(settings.fullChannel()) == 2 && alpha.redis().publishing() && beta.redis().publishing()
                    && alpha.redis().subscribed() && beta.redis().subscribed();
        }

        void close() {
            MinecraftForge.EVENT_BUS.unregister(listener);
            alpha.close();
            beta.close();
            redis.close();
        }
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void messages_arrive_through_redis_and_the_table_copy_is_dropped(GameTestHelper h) throws IOException {
        Pair p = new Pair(h, "rd_fast", "secret");
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(p.connected(), "both servers connect and subscribe"))
                .thenExecute(() -> {
                    h.assertTrue(p.beta.pollEverySeconds() == 30, "with Redis up the table is read only every 30 s, not " + p.beta.pollEverySeconds());
                    p.alpha.publish("rd_fast", "", "", "hello");
                })
                .thenWaitUntil(() -> h.assertTrue(p.got.size() == 1, "the other server gets it through Redis"))
                .thenExecute(() -> {
                    h.assertTrue("alpha".equals(p.got.get(0).source()) && "hello".equals(p.got.get(0).payload()), "source and payload carried");
                    h.assertTrue(p.backend.messages() == 0, "it arrived before the table was even written");
                    p.alpha.drain(); // writes the table copy
                    p.beta.drain();  // reads it
                })
                .thenExecuteAfter(3, () -> {
                    h.assertTrue(p.backend.messages() == 1, "the table has its copy");
                    h.assertTrue(p.got.size() == 1 && p.beta.delivered() >= 1, "and the copy is not handled a second time");
                    h.assertTrue(p.alpha.delivered() == 0, "a server never handles its own message");
                    p.close();
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public void an_outage_falls_back_to_the_table_and_the_link_comes_back(GameTestHelper h) throws IOException {
        Pair p = new Pair(h, "rd_outage", "secret");
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(p.connected(), "both servers connect"))
                .thenExecute(p.redis::stop)
                // the receiving side notices at once; the sending side only on its next send (or its 30 s keep-alive)
                .thenWaitUntil(() -> h.assertTrue(!p.alpha.redis().subscribed() && !p.beta.redis().subscribed(), "both notice Redis is gone"))
                .thenExecute(() -> {
                    h.assertTrue(p.beta.pollEverySeconds() == 1, "without Redis the table is read at the normal rate");
                    p.alpha.publish("rd_outage", "", "", "while down");
                    p.alpha.drain();
                    p.beta.drain();
                })
                .thenExecuteAfter(3, () -> h.assertTrue(p.got.size() == 1 && "while down".equals(p.got.get(0).payload()), "the table carries it meanwhile"))
                .thenExecute(() -> {
                    try {
                        p.redis.restart();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .thenWaitUntil(() -> h.assertTrue(p.connected(), "both reconnect by themselves"))
                .thenExecute(() -> p.alpha.publish("rd_outage", "", "", "back"))
                .thenWaitUntil(() -> h.assertTrue(p.got.size() == 2 && "back".equals(p.got.get(1).payload()), "and Redis carries messages again"))
                .thenExecute(p::close)
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public void a_wrong_password_is_reported(GameTestHelper h) throws IOException {
        Pair p = new Pair(h, "rd_wrong", "not-the-password");
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(p.alpha.redis().status().contains("refused the login"), "the status says why: " + p.alpha.redis().status()))
                .thenExecute(() -> {
                    h.assertFalse(p.alpha.redis().subscribed() || p.alpha.redis().publishing(), "and it stays down");
                    h.assertTrue(p.alpha.pollEverySeconds() == 1, "so the table is read at the normal rate");
                    p.close();
                })
                .thenSucceed();
    }
}
