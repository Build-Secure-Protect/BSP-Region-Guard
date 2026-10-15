package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.RegionGuardApi;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.api.event.RemoteMessageEvent;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.mine.MineData;
import com.mrgregles.bsp_region_guard.mine.Mines;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import com.mrgregles.bsp_region_guard.storage.MemoryBackend;
import com.mrgregles.bsp_region_guard.storage.MirrorBackend;
import com.mrgregles.bsp_region_guard.storage.RegionMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** The shared-storage mirror on an in-memory backend: pushing, tombstones, reconciling after a restart, messages between two servers. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class MirrorGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    private static MirrorBackend.RegionRow row(MemoryBackend backend, String server, String dim, String id) {
        for (MirrorBackend.RegionRow r : backend.fetchRegions(server, dim)) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** BSP Teleport's TR4: the API names this server as the storage does, and still gives the configured name with storage off. */
    @GameTest(template = EMPTY)
    public void api_names_this_server(GameTestHelper h) {
        RegionMirror before = RegionMirror.active();
        try {
            RegionMirror mirror = RegionMirror.startWith(h.getLevel().getServer(), new MemoryBackend(), "tr4-alpha");
            RegionMirror.setActive(mirror);
            h.assertTrue("tr4-alpha".equals(RegionGuardApi.get().serverId()), "with storage on, the mirror's server name");
            RegionMirror.setActive(null);
            String configured = RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_SERVER_ID, "server-1");
            h.assertTrue(configured.equals(RegionGuardApi.get().serverId()), "with storage off, storage.serverId from the config");
            h.assertTrue(RegionGuardApi.API_VERSION >= 5, "API version 5 or later");
        } finally {
            RegionMirror.setActive(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void changes_are_pushed_and_removals_leave_tombstones(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String dim = level.dimension().location().toString();
        MemoryBackend backend = new MemoryBackend();
        RegionMirror mirror = RegionMirror.startWith(level.getServer(), backend, "alpha");
        RegionMirror.setActive(mirror);
        try {
            mirror.drain();
            Region r = new Region("mr_push", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(4, 4, 4))), RegionType.PROTECTED);
            store(h).add(r);
            mirror.drain();
            MirrorBackend.RegionRow pushed = row(backend, "alpha", dim, "mr_push");
            h.assertTrue(pushed != null && !pushed.deleted() && pushed.version() == r.version(), "the new region was pushed with its version");
            r.setFlag(Flags.PVP, State.DENY);
            store(h).touch(r);
            mirror.drain();
            MirrorBackend.RegionRow again = row(backend, "alpha", dim, "mr_push");
            h.assertTrue(again.version() == r.version() && again.version() > pushed.version(), "a change pushes the new version");
            Region back = RegionMirror.deserialize(again.data());
            h.assertTrue(back.flag(Flags.PVP) == State.DENY && back.bounds().equals(r.bounds()), "the stored data is the region");
            store(h).remove("mr_push");
            mirror.drain();
            MirrorBackend.RegionRow gone = row(backend, "alpha", dim, "mr_push");
            h.assertTrue(gone != null && gone.deleted(), "removal leaves a tombstone");
        } catch (Exception e) {
            h.fail(e.toString());
        } finally {
            RegionMirror.setActive(null);
            store(h).remove("mr_push");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void reconcile_restores_from_the_database_and_pushes_unknown_locals(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String dim = level.dimension().location().toString();
        MemoryBackend backend = new MemoryBackend();
        RegionMirror mirror = RegionMirror.startWith(level.getServer(), backend, "alpha");
        try {
            mirror.drain();
            // a region that exists only in the database (as if saved by this server before a world rollback)
            Region saved = new Region("mr_fromdb", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(3, 3, 3))), RegionType.PROTECTED);
            saved.setFlag(Flags.GREETING, "restored");
            backend.upsertRegion(new MirrorBackend.RegionRow("alpha", dim, "mr_fromdb", 5, 1L, RegionMirror.serialize(saved), false));
            // a tombstone for a region that still exists locally
            Region doomed = new Region("mr_doomed", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(5, 1, 1)), h.absolutePos(new BlockPos(6, 3, 3))), RegionType.PROTECTED);
            store(h).add(doomed);
            backend.markDeleted("alpha", dim, "mr_doomed", System.currentTimeMillis() + 1000); // deleted after the local copy last changed
            // a local region the database has never seen
            Region local = new Region("mr_localonly", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(8, 1, 1)), h.absolutePos(new BlockPos(9, 3, 3))), RegionType.PROTECTED);
            store(h).add(local);

            mirror.reconcile(level);
            mirror.drain();
            Region restored = store(h).get("mr_fromdb");
            h.assertTrue(restored != null && "restored".equals(restored.flag(Flags.GREETING)), "the database's region was restored locally");
            h.assertTrue(store(h).get("mr_doomed") == null, "the tombstoned region was removed locally");
            h.assertTrue(row(backend, "alpha", dim, "mr_localonly") != null, "the local-only region was pushed");
            h.assertTrue(backend.fetchRegions("beta", dim).isEmpty(), "another server's rows are untouched");
        } catch (Exception e) {
            h.fail(e.toString());
        } finally {
            RegionMirror.setActive(null);
            for (String id : List.of("mr_fromdb", "mr_doomed", "mr_localonly")) {
                store(h).remove(id);
            }
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void messages_reach_the_other_server_and_sync_a_mine_reset(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String dim = level.dimension().location().toString();
        MemoryBackend backend = new MemoryBackend();
        RegionMirror alpha = RegionMirror.startWith(level.getServer(), backend, "alpha");
        RegionMirror beta = RegionMirror.startWith(level.getServer(), backend, "beta");
        AtomicReference<RemoteMessageEvent> received = new AtomicReference<>();
        Consumer<RemoteMessageEvent> listener = received::set;
        MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false, RemoteMessageEvent.class, listener);
        Region mine = new Region("mr_mine", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(4, 3, 4))), RegionType.PROTECTED);
        store(h).add(mine);
        Mines.makeMine(store(h), mine);
        MineData.put(mine, ResourceLocation.parse("minecraft:stone"), 1);
        store(h).touch(mine);
        try {
            alpha.drain();
            beta.drain();
            alpha.publish("hello", dim, "", "from alpha");
            alpha.drain(); // alpha publishes and polls: its own message is skipped
            h.assertTrue(received.get() == null, "a server ignores its own messages");
            beta.drain();
            h.assertTrue(received.get() != null && "alpha".equals(received.get().source()) && "hello".equals(received.get().kind()) && "from alpha".equals(received.get().payload()),
                    "the other server receives it as an event");
            received.set(null);
            Mines.clearAll();
            alpha.publish(RegionMirror.KIND_MINE_RESET, dim, "mr_mine", "");
            alpha.drain();
            beta.drain();
            h.assertTrue(Mines.busy(), "a mine-reset message from another server resets the mine with that id here");
            Mines.flush();
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
            RegionMirror.setActive(null);
            Mines.clearAll();
            store(h).remove("mr_mine");
        }
        h.succeed();
    }

    /** Request TR2: messages queued while the server stops (a SHUTDOWN landing) reach the database before the mirror closes. */
    @GameTest(template = EMPTY)
    public void stopping_sends_queued_messages_first(GameTestHelper h) {
        MemoryBackend backend = new MemoryBackend(40);
        RegionMirror mirror = RegionMirror.startThreaded(h.getLevel().getServer(), backend, "alpha");
        RegionMirror.setActive(mirror);
        try {
            for (int i = 0; i < 5; i++) {
                mirror.publish("landing", "", "", "player " + i);
            }
        } finally {
            RegionMirror.stop();
        }
        h.assertTrue(backend.messages() == 5, "all 5 queued messages are sent before the mirror stops, got " + backend.messages());
        h.succeed();
    }
}
