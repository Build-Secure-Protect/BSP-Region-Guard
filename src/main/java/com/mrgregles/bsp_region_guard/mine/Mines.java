package com.mrgregles.bsp_region_guard.mine;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.api.event.MineResetEvent;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the mines: schedules resets, warns the players near a mine before one, moves the players inside out, and
 * refills in batches. One check a second; refills are written at {@code mines.blocksPerTick} blocks per tick.
 */
public final class Mines {
    private static final Deque<MineFiller.Job> JOBS = new ArrayDeque<>();
    /** "mine id @ warning seconds" already announced for the current cycle. */
    private static final Set<String> WARNED = new HashSet<>();
    private static long lastCheckMs;

    private Mines() {
    }

    // ------------------------------------------------------------------ setup helpers (commands and the screen)

    /** Turns a region into a mine: type, break allowed and place refused inside. */
    public static void makeMine(RegionStore store, Region r) {
        r.setType(RegionType.MINE);
        r.setFlag(Flags.BLOCK_BREAK, State.ALLOW);
        r.setFlag(Flags.BLOCK_PLACE, State.DENY);
        store.touch(r);
    }

    /**
     * Creates (or resizes) the shell around a mine: a region {@code <mine>_shell} grown by {@code radius} blocks in
     * every direction, where only the mine's own blocks may be broken and nothing may be placed. The mine sits inside
     * it with a higher priority so the mine's own rules win there.
     */
    public static Region makeShell(ServerLevel level, RegionStore store, Region mine, int radius) {
        Cuboid b = mine.bounds();
        Cuboid grown = new Cuboid(b.minX() - radius, Math.max(level.getMinBuildHeight(), b.minY() - radius), b.minZ() - radius,
                b.maxX() + radius, Math.min(level.getMaxBuildHeight() - 1, b.maxY() + radius), b.maxZ() + radius);
        String id = mine.id() + "_shell";
        Region shell = store.get(id);
        if (shell == null) {
            shell = new Region(id, level.dimension(), grown, RegionType.PROTECTED);
            shell.setName(mine.name() + " shell");
            shell.setPriority(mine.priority() - 1);
            shell.setFlag(Flags.BUILD, State.ALLOW);
            shell.setFlag(Flags.BLOCK_PLACE, State.DENY);
            shell.setFlag(Flags.MINE_ONLY_BREAK, State.ALLOW);
            store.add(shell);
        } else {
            shell.setBounds(grown);
            store.touch(shell);
        }
        if (mine.priority() <= shell.priority()) {
            mine.setPriority(shell.priority() + 1);
            store.touch(mine);
        }
        return shell;
    }

    /** The spawn to send players to on reset: the set point, else just above the top of the mine's centre. */
    public static FlagLocation spawnOf(ServerLevel level, Region mine) {
        FlagLocation set = MineData.spawn(mine);
        if (set != null) {
            return set;
        }
        Cuboid b = mine.bounds();
        double x = (b.minX() + b.maxX() + 1) / 2.0, z = (b.minZ() + b.maxZ() + 1) / 2.0;
        int y = Math.max(b.maxY() + 1, level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)));
        return FlagLocation.of(level, x, y, z, 0, 0);
    }

    /** Players whose body overlaps the mine box. */
    public static List<ServerPlayer> playersInside(ServerLevel level, Region mine) {
        Cuboid b = mine.bounds();
        AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.getBoundingBox().intersects(box)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Players inside the mine or within 48 blocks of it: the ones who are told about a reset. */
    static List<ServerPlayer> playersNear(ServerLevel level, Region mine) {
        Cuboid b = mine.bounds();
        AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1).inflate(48);
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (box.contains(p.position())) {
                out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ resets

    /** Resets a mine now: moves players out, plans the refill, schedules the next reset, tells the other servers. */
    public static String reset(ServerLevel level, RegionStore store, Region mine) {
        String message = resetQuietly(level, store, mine);
        if (com.mrgregles.bsp_region_guard.storage.RegionMirror.active() != null && mine.type() == RegionType.MINE) {
            com.mrgregles.bsp_region_guard.storage.RegionMirror.active().publish(com.mrgregles.bsp_region_guard.storage.RegionMirror.KIND_MINE_RESET,
                    level.dimension().location().toString(), mine.id(), "");
        }
        return message;
    }

    /** A reset that does not tell other servers (used when the reset came from one). */
    public static String resetQuietly(ServerLevel level, RegionStore store, Region mine) {
        if (mine.type() != RegionType.MINE || mine.bounds() == null) {
            return mine.id() + " is not a mine.";
        }
        List<MineData.Entry> fill = MineData.fill(mine);
        if (fill.isEmpty()) {
            return mine.id() + " has no blocks in its fill table yet (/rg mine add).";
        }
        FlagLocation spawn = spawnOf(level, mine);
        ServerLevel dest = level.getServer().getLevel(spawn.dimension());
        for (ServerPlayer p : playersInside(level, mine)) {
            if (dest != null) {
                p.teleportTo(dest, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
            }
            p.displayClientMessage(Component.literal(mine.name() + " is resetting."), true);
        }
        MineFiller.Job job;
        try {
            job = MineFiller.plan(level, mine.bounds(), fill, level.getRandom());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        JOBS.add(job);
        int interval = MineData.intervalSeconds(mine);
        if (interval > 0) {
            MineData.setNextResetAt(mine, System.currentTimeMillis() + interval * 1000L);
            store.touch(mine);
        }
        WARNED.removeIf(k -> k.startsWith(mine.id() + "@"));
        MinecraftForge.EVENT_BUS.post(new MineResetEvent(level, mine.id(), job.total()));
        for (ServerPlayer p : playersNear(level, mine)) {
            p.displayClientMessage(Component.literal(mine.name() + " has been reset."), false);
        }
        BSPRegionGuard.LOGGER.info("Mine {} in {} resetting: {} blocks", mine.id(), level.dimension().location(), job.total());
        return mine.id() + " is resetting (" + job.total() + " blocks).";
    }

    /** Seconds until the next automatic reset, or -1 when none is scheduled. */
    public static long secondsToReset(Region mine) {
        long at = MineData.nextResetAt(mine);
        return at <= 0 ? -1 : Math.max(0, (at - System.currentTimeMillis()) / 1000);
    }

    /** Called when an interval is set: schedules the first reset from now. */
    public static void schedule(RegionStore store, Region mine, int seconds) {
        MineData.setIntervalSeconds(mine, seconds);
        if (seconds > 0) {
            MineData.setNextResetAt(mine, System.currentTimeMillis() + seconds * 1000L);
        }
        store.touch(mine);
    }

    // ------------------------------------------------------------------ the tick

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        // refills: a budget of blocks per tick, oldest job first
        int budget = RegionGuardConfig.getOr(RegionGuardConfig.MINE_BLOCKS_PER_TICK, 20000);
        while (budget > 0 && !JOBS.isEmpty()) {
            MineFiller.Job job = JOBS.peek();
            budget -= job.apply(budget);
            if (job.done()) {
                JOBS.poll();
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < 1000) {
            return;
        }
        lastCheckMs = now;
        for (ServerLevel level : server.getAllLevels()) {
            RegionStore store = RegionStore.get(level);
            if (!store.hasBoundedRegions()) {
                continue;
            }
            for (Region mine : new ArrayList<>(store.all())) {
                if (mine.type() != RegionType.MINE || MineData.intervalSeconds(mine) <= 0 || mine.bounds() == null) {
                    continue;
                }
                long at = MineData.nextResetAt(mine);
                if (at <= 0) {
                    MineData.setNextResetAt(mine, now + MineData.intervalSeconds(mine) * 1000L);
                    store.touch(mine);
                    continue;
                }
                long left = (at - now) / 1000;
                if (left <= 0) {
                    reset(level, store, mine);
                    continue;
                }
                for (int warn : warnSeconds()) {
                    String key = mine.id() + "@" + warn;
                    if (left <= warn && !WARNED.contains(key)) {
                        WARNED.add(key);
                        for (ServerPlayer p : playersNear(level, mine)) {
                            p.displayClientMessage(Component.literal(mine.name() + " resets in " + left + " seconds."), false);
                            p.displayClientMessage(Component.literal(mine.name() + " resets in " + left + " s"), true);
                        }
                    }
                }
            }
        }
    }

    static List<Integer> warnSeconds() {
        List<? extends Integer> cfg = RegionGuardConfig.getOr(RegionGuardConfig.MINE_WARN_SECONDS, List.of(60, 10));
        return cfg == null ? List.of(60, 10) : new ArrayList<>(cfg);
    }

    /** For tests: whether any refill is still being written. */
    public static boolean busy() {
        return !JOBS.isEmpty();
    }

    /** For tests: writes everything that is queued at once. */
    public static void flush() {
        while (!JOBS.isEmpty()) {
            MineFiller.Job job = JOBS.poll();
            job.apply(Integer.MAX_VALUE);
        }
    }

    public static void clearAll() {
        JOBS.clear();
        WARNED.clear();
    }

    @Nullable
    public static Region mineAt(RegionStore store, BlockPos pos) {
        for (Region r : store.at(pos)) {
            if (r.type() == RegionType.MINE) {
                return r;
            }
        }
        return null;
    }
}
