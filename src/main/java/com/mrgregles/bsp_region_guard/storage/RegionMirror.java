package com.mrgregles.bsp_region_guard.storage;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.event.RemoteMessageEvent;
import com.mrgregles.bsp_region_guard.mine.Mines;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps this server's regions in the shared database and carries messages between servers (owner decisions D10,
 * D11, D29). Database work runs on one worker thread; results come back on the server thread. Each server owns its
 * regions: on start it reconciles only its own rows, and other servers' rows are read here only for the status view.
 */
public final class RegionMirror {
    private static final int MESSAGE_BATCH = 100;
    public static final String KIND_MINE_RESET = "mine-reset", KIND_REGION_CHANGED = "region-changed";

    @Nullable
    private static RegionMirror active;

    private final MirrorBackend backend;
    private final String serverId;
    private final int pollSeconds;
    private final MinecraftServer server;
    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    @Nullable
    private final Thread worker;
    /** What this server has pushed: "dimension|id" to version. */
    private final Map<String, Integer> pushed = new HashMap<>();
    private volatile long lastMessageId = -1;
    private volatile long lastPollMs;
    private volatile String lastError = "";
    private volatile int errors;

    private RegionMirror(MinecraftServer server, MirrorBackend backend, String serverId, int pollSeconds, boolean threaded) {
        this.server = server;
        this.backend = backend;
        this.serverId = serverId;
        this.pollSeconds = pollSeconds;
        if (threaded) {
            worker = new Thread(this::loop, "bsp-region-guard-storage");
            worker.setDaemon(true);
            worker.start();
        } else {
            worker = null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the mirror from the config, if {@code storage.mode} is mysql. */
    public static void start(MinecraftServer server) {
        stop();
        String mode = RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_MODE, "none");
        if (!"mysql".equalsIgnoreCase(mode)) {
            return;
        }
        MirrorBackend backend = new JdbcBackend(
                RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_HOST, "localhost"),
                RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_PORT, 3306),
                RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_DATABASE, "bsp"),
                RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_USER, "bsp"),
                RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_PASSWORD, ""),
                RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_TABLE_PREFIX, "bsp_rg_"));
        active = new RegionMirror(server, backend, RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_SERVER_ID, "server-1"),
                RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_POLL_SECONDS, 5), true);
        active.submit(() -> {
            backend.init();
            active.lastMessageId = backend.latestMessageId();
            BSPRegionGuard.LOGGER.info("Region storage connected: {} (this server is '{}')", backend.describe(), active.serverId);
        });
    }

    /** For tests: a mirror on a given backend whose work runs when {@link #drain()} is called. */
    public static RegionMirror startWith(MinecraftServer server, MirrorBackend backend, String serverId) {
        RegionMirror m = new RegionMirror(server, backend, serverId, 1, false);
        m.submit(() -> {
            backend.init();
            m.lastMessageId = backend.latestMessageId();
        });
        return m;
    }

    public static void stop() {
        if (active != null) {
            active.shutdown();
            active = null;
        }
    }

    @Nullable
    public static RegionMirror active() {
        return active;
    }

    /** For tests: makes a mirror the one the hooks talk to. */
    public static void setActive(@Nullable RegionMirror mirror) {
        active = mirror;
    }

    public static boolean enabled() {
        return active != null;
    }

    private void shutdown() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            backend.close();
        } catch (Exception e) {
            BSPRegionGuard.LOGGER.debug("closing region storage", e);
        }
    }

    // ------------------------------------------------------------------ worker

    private interface Task {
        void run() throws Exception;
    }

    private void submit(Task task) {
        work.add(() -> {
            try {
                task.run();
            } catch (Exception e) {
                errors++;
                lastError = e.toString();
                BSPRegionGuard.LOGGER.warn("Region storage: {}", e.toString());
            }
        });
    }

    private void loop() {
        while (running.get()) {
            try {
                Runnable r = work.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (r != null) {
                    r.run();
                }
                if (System.currentTimeMillis() - lastPollMs >= pollSeconds * 1000L) {
                    lastPollMs = System.currentTimeMillis();
                    pollMessages();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                BSPRegionGuard.LOGGER.warn("Region storage worker: {}", e.toString());
            }
        }
    }

    /** For tests: runs every queued task now, then polls messages once. */
    public void drain() {
        Runnable r;
        while ((r = work.poll()) != null) {
            r.run();
        }
        pollMessages();
        while ((r = work.poll()) != null) {
            r.run();
        }
    }

    // ------------------------------------------------------------------ regions out

    public static byte[] serialize(Region r) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(r.save(), bytes);
        return bytes.toByteArray();
    }

    public static Region deserialize(byte[] data) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data));
        return Region.load(tag);
    }

    /** Called (server thread) after a store changed: pushes regions whose version moved and tombstones the ones that vanished. */
    public void onStoreChanged(RegionStore store) {
        String dim = store.dimension().location().toString();
        Set<String> present = new HashSet<>();
        List<MirrorBackend.RegionRow> rows = new ArrayList<>();
        for (Region r : store.all()) {
            String key = dim + "|" + r.id();
            present.add(key);
            Integer last = pushed.get(key);
            if (last == null || last != r.version()) {
                try {
                    rows.add(new MirrorBackend.RegionRow(serverId, dim, r.id(), r.version(), r.updatedAt(), serialize(r), false));
                    pushed.put(key, r.version());
                } catch (IOException e) {
                    BSPRegionGuard.LOGGER.warn("Could not serialise region {}: {}", r.id(), e.toString());
                }
            }
        }
        List<String> gone = new ArrayList<>();
        for (String key : new ArrayList<>(pushed.keySet())) {
            if (key.startsWith(dim + "|") && !present.contains(key)) {
                gone.add(key.substring(dim.length() + 1));
                pushed.remove(key);
            }
        }
        if (rows.isEmpty() && gone.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        submit(() -> {
            for (MirrorBackend.RegionRow row : rows) {
                backend.upsertRegion(row);
            }
            for (String id : gone) {
                backend.markDeleted(serverId, dim, id, now);
            }
        });
    }

    /** Remembers what a store holds without pushing (after a reconcile, so the next change pushes only the delta). */
    private void remember(RegionStore store) {
        String dim = store.dimension().location().toString();
        for (Region r : store.all()) {
            pushed.put(dim + "|" + r.id(), r.version());
        }
    }

    // ------------------------------------------------------------------ regions in

    /**
     * Merges the database's view of this server's regions in a world into the local store: a row newer than the
     * local region replaces it, a tombstone removes it, a local region unknown to the database is pushed. Runs the
     * fetch on the worker and the merge on the server thread.
     */
    public void reconcile(ServerLevel level) {
        String dim = level.dimension().location().toString();
        submit(() -> {
            List<MirrorBackend.RegionRow> rows = backend.fetchRegions(serverId, dim);
            server.execute(() -> merge(level, rows));
        });
    }

    private void merge(ServerLevel level, List<MirrorBackend.RegionRow> rows) {
        RegionStore store = RegionStore.get(level);
        int replaced = 0, removed = 0, added = 0;
        Set<String> seen = new HashSet<>();
        for (MirrorBackend.RegionRow row : rows) {
            seen.add(row.id());
            Region local = store.get(row.id());
            if (row.deleted()) {
                // a tombstone written after the local region last changed wins
                if (local != null && row.updatedAt() >= local.updatedAt()) {
                    store.remove(row.id());
                    removed++;
                }
                continue;
            }
            if (local != null && local.version() >= row.version()) {
                continue;
            }
            try {
                Region fromDb = deserialize(row.data());
                if (local != null) {
                    store.remove(local.id());
                    replaced++;
                } else {
                    added++;
                }
                store.add(fromDb);
            } catch (IOException | RuntimeException e) {
                BSPRegionGuard.LOGGER.warn("Could not read region {} from storage: {}", row.id(), e.toString());
            }
        }
        remember(store);
        List<MirrorBackend.RegionRow> push = new ArrayList<>();
        for (Region r : store.all()) {
            if (!seen.contains(r.id())) {
                try {
                    push.add(new MirrorBackend.RegionRow(serverId, level.dimension().location().toString(), r.id(), r.version(), r.updatedAt(), serialize(r), false));
                } catch (IOException e) {
                    BSPRegionGuard.LOGGER.warn("Could not serialise region {}: {}", r.id(), e.toString());
                }
            }
        }
        if (!push.isEmpty()) {
            submit(() -> {
                for (MirrorBackend.RegionRow row : push) {
                    backend.upsertRegion(row);
                }
            });
        }
        BSPRegionGuard.LOGGER.info("Region storage reconcile for {}: {} added, {} replaced, {} removed, {} pushed", level.dimension().location(), added, replaced, removed, push.size());
    }

    // ------------------------------------------------------------------ messages

    public void publish(String kind, String dimension, String regionId, String payload) {
        long now = System.currentTimeMillis();
        submit(() -> backend.publish(new MirrorBackend.Message(0, now, serverId, kind, dimension, regionId, payload)));
    }

    private void pollMessages() {
        try {
            if (lastMessageId < 0) {
                lastMessageId = backend.latestMessageId();
                return;
            }
            List<MirrorBackend.Message> messages = backend.fetchMessagesAfter(lastMessageId, MESSAGE_BATCH);
            for (MirrorBackend.Message m : messages) {
                lastMessageId = Math.max(lastMessageId, m.id());
                if (m.source().equals(serverId)) {
                    continue;
                }
                server.execute(() -> deliver(m));
            }
        } catch (Exception e) {
            errors++;
            lastError = e.toString();
            BSPRegionGuard.LOGGER.warn("Region storage poll: {}", e.toString());
        }
    }

    private void deliver(MirrorBackend.Message m) {
        MinecraftForge.EVENT_BUS.post(new RemoteMessageEvent(m.source(), m.kind(), m.dimension(), m.regionId(), m.payload()));
        if (KIND_MINE_RESET.equals(m.kind()) && RegionGuardConfig.getOr(RegionGuardConfig.STORAGE_SYNC_MINE_RESETS, true)) {
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.dimension().location().toString().equals(m.dimension())) {
                    continue;
                }
                RegionStore store = RegionStore.get(level);
                Region mine = store.get(m.regionId());
                if (mine != null && mine.type() == RegionType.MINE) {
                    Mines.resetQuietly(level, store, mine);
                }
            }
        }
    }

    // ------------------------------------------------------------------ status

    public String status() {
        return "storage: " + backend.describe() + "; this server '" + serverId + "'; poll every " + pollSeconds + " s; last message id " + lastMessageId
                + "; pending " + work.size() + "; errors " + errors + (lastError.isEmpty() ? "" : " (last: " + lastError + ")");
    }

    public void countByServer(java.util.function.Consumer<Map<String, Integer>> then) {
        submit(() -> {
            Map<String, Integer> counts = backend.countByServer();
            server.execute(() -> then.accept(counts));
        });
    }

    public String serverId() {
        return serverId;
    }

    /** Other servers' live regions in a world, as read-only summaries ("server/id", type "remote"), delivered on the server thread. */
    public void fetchOthers(ServerLevel level, java.util.function.Consumer<List<com.mrgregles.bsp_region_guard.network.RegionListPacket.Summary>> then) {
        String dim = level.dimension().location().toString();
        submit(() -> {
            List<com.mrgregles.bsp_region_guard.network.RegionListPacket.Summary> out = new ArrayList<>();
            for (Map.Entry<String, Integer> e : backend.countByServer().entrySet()) {
                if (e.getKey().equals(serverId)) {
                    continue;
                }
                for (MirrorBackend.RegionRow row : backend.fetchRegions(e.getKey(), dim)) {
                    if (row.deleted()) {
                        continue;
                    }
                    try {
                        Region r = deserialize(row.data());
                        out.add(new com.mrgregles.bsp_region_guard.network.RegionListPacket.Summary(e.getKey() + "/" + r.id(), r.name(), "remote", r.priority(), null, r.bounds(),
                                r.owners().size(), r.members().size(), r.flags().size()));
                    } catch (IOException | RuntimeException ex) {
                        BSPRegionGuard.LOGGER.debug("skipping remote region {}: {}", row.id(), ex.toString());
                    }
                }
            }
            server.execute(() -> then.accept(out));
        });
    }
}
