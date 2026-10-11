package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.event.RegionEnterEvent;
import com.mrgregles.bsp_region_guard.api.event.RegionLeaveEvent;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.compat.LuckPermsBridge;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Follows every player across region borders, once per tick, and applies the movement flags: {@code entry},
 * {@code exit}, {@code exit-via-teleport}, {@code exit-override}, {@code allowed-ranks}, greetings and farewells
 * (chat and title), {@code notify-enter} / {@code notify-leave}, {@code game-mode}, {@code time-lock},
 * {@code weather-lock}, the heal and feed timers, and the {@code spawn} point after death.
 *
 * <p>A refused move puts the player back where they were last tick. Only crossing a border is checked, so a player
 * who is already inside when a region is created is never trapped by the entry rule.
 */
public final class MovementTracker {
    /** A move longer than this in one tick is a teleport, not a walk. */
    private static final double TELEPORT_DISTANCE_SQ = 10 * 10;
    private static final String PREV_GAME_MODE = BSPRegionGuard.MODID + ":PrevGameMode";

    private static final Map<UUID, Tracked> TRACKED = new HashMap<>();
    private static boolean warnedNoLuckPerms;

    private MovementTracker() {
    }

    /** What is remembered per player between ticks. */
    static final class Tracked {
        @Nullable
        ResourceKey<Level> dimension;
        @Nullable
        Vec3 pos;
        final Set<String> regions = new HashSet<>();
        boolean timeLocked;
        boolean weatherLocked;
        long lastHealTick = Long.MIN_VALUE;
        long lastFeedTick = Long.MIN_VALUE;
        @Nullable
        FlagLocation respawnAt;
    }

    static Tracked of(ServerPlayer player) {
        return TRACKED.computeIfAbsent(player.getUUID(), k -> new Tracked());
    }

    // ------------------------------------------------------------------ events

    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer sp && !sp.isRemoved()) {
            tick(sp, sp.serverLevel().getGameTime());
        }
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            Tracked t = of(sp);
            t.dimension = sp.serverLevel().dimension();
            t.pos = sp.position();
            for (Region r : RegionStore.get(sp.serverLevel()).at(sp.blockPosition())) {
                if (!r.isGlobal()) {
                    t.regions.add(r.id());
                }
            }
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            restoreGameMode(sp);
            TRACKED.remove(sp.getUUID());
        }
    }

    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            Tracked t = of(sp);
            t.respawnAt = RegionQuery.value(sp.serverLevel(), sp.blockPosition(), Flags.SPAWN);
        }
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || event.isEndConquered()) {
            return;
        }
        Tracked t = of(sp);
        FlagLocation target = t.respawnAt;
        t.respawnAt = null;
        t.regions.clear();
        t.pos = null;
        if (target != null) {
            ServerLevel dest = sp.server.getLevel(target.dimension());
            if (dest != null) {
                sp.teleportTo(dest, target.x(), target.y(), target.z(), target.yaw(), target.pitch());
            }
        }
    }

    public static void clearAll() {
        TRACKED.clear();
        warnedNoLuckPerms = false;
    }

    // ------------------------------------------------------------------ the tick

    /** One tick for one player. Public so GameTests can drive it with a chosen game time. */
    public static void tick(ServerPlayer player, long gameTime) {
        Tracked t = of(player);
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Vec3 pos = player.position();
        BlockPos blockPos = player.blockPosition();

        boolean teleport = t.pos == null || t.dimension == null || !t.dimension.equals(level.dimension()) || t.pos.distanceToSqr(pos) > TELEPORT_DISTANCE_SQ;
        List<Region> here = store.at(blockPos);
        Set<String> nowIds = new HashSet<>();
        for (Region r : here) {
            if (!r.isGlobal()) {
                nowIds.add(r.id());
            }
        }

        if (!nowIds.equals(t.regions)) {
            Set<String> entered = new HashSet<>(nowIds);
            entered.removeAll(t.regions);
            Set<String> left = new HashSet<>(t.regions);
            left.removeAll(nowIds);

            String refusal = refusal(player, level, store, here, blockPos, t, entered, left, teleport);
            if (refusal != null && putBack(player, t, here)) {
                Protection.tell(player, level, blockPos, refusal);
                return; // position restored; the sets stay as they were
            }
            for (String id : left) {
                Region r = regionIn(store, t.dimension, player, id);
                if (r != null) {
                    farewell(player, store, r);
                }
            }
            for (String id : entered) {
                Region r = store.get(id);
                if (r != null) {
                    greet(player, store, r);
                }
            }
            t.regions.clear();
            t.regions.addAll(nowIds);
        }

        t.dimension = level.dimension();
        t.pos = pos;

        if (!RegionQuery.bypasses(player)) {
            gameMode(player, level, blockPos);
        }
        timeLock(player, level, blockPos, t, gameTime);
        weatherLock(player, level, blockPos, t);
        healAndFeed(player, level, blockPos, t, gameTime);
    }

    /** The region a player just left, which may be in the world they came from. */
    @Nullable
    private static Region regionIn(RegionStore store, @Nullable ResourceKey<Level> dimension, ServerPlayer player, String id) {
        if (dimension == null || dimension.equals(store.dimension())) {
            return store.get(id);
        }
        ServerLevel old = player.server.getLevel(dimension);
        return old == null ? null : RegionStore.get(old).get(id);
    }

    // ------------------------------------------------------------------ entry and exit

    /** The message to show when the move is refused, or {@code null} when it may go ahead. */
    @Nullable
    private static String refusal(ServerPlayer player, ServerLevel level, RegionStore store, List<Region> here, BlockPos pos,
                                  Tracked t, Set<String> entered, Set<String> left, boolean teleport) {
        boolean bypass = RegionQuery.bypasses(player);
        boolean memberHere = isMemberOfAny(player, here);

        if (!entered.isEmpty()) {
            if (!bypass && !memberHere && !RegionQuery.isExempt(level, pos, Flags.ENTRY, player)) {
                if (RegionQuery.explicit(level, pos, Flags.ENTRY).orElse(State.ALLOW) == State.DENY) {
                    String m = RegionQuery.value(level, pos, Flags.ENTRY_DENY_MESSAGE);
                    return m == null ? "You are not allowed in there." : m;
                }
                Set<String> ranks = RegionQuery.value(level, pos, Flags.ALLOWED_RANKS);
                if (ranks != null && !ranks.isEmpty() && !hasRank(player, ranks)) {
                    String m = RegionQuery.value(level, pos, Flags.ENTRY_DENY_MESSAGE);
                    return m == null ? "Your rank is not allowed in there." : m;
                }
            }
            for (String id : entered) {
                if (MinecraftForge.EVENT_BUS.post(new RegionEnterEvent(player, id, level.dimension(), teleport))) {
                    String m = RegionQuery.value(level, pos, Flags.ENTRY_DENY_MESSAGE);
                    return m == null ? "You are not allowed in there." : m;
                }
            }
        }

        if (!left.isEmpty() && t.pos != null && t.dimension != null) {
            ServerLevel oldLevel = player.server.getLevel(t.dimension);
            if (oldLevel != null) {
                BlockPos oldPos = BlockPos.containing(t.pos);
                RegionStore oldStore = RegionStore.get(oldLevel);
                List<Region> there = oldStore.at(oldPos);
                boolean memberThere = isMemberOfAny(player, there);
                if (!bypass && !memberThere && !RegionQuery.isExempt(oldLevel, oldPos, Flags.EXIT, player)
                        && RegionQuery.explicit(oldLevel, oldPos, Flags.EXIT).orElse(State.ALLOW) == State.DENY
                        && !Boolean.TRUE.equals(RegionQuery.value(oldLevel, oldPos, Flags.EXIT_OVERRIDE))
                        && !(teleport && RegionQuery.allows(oldLevel, oldPos, Flags.EXIT_VIA_TELEPORT))) {
                    String m = RegionQuery.value(oldLevel, oldPos, Flags.EXIT_DENY_MESSAGE);
                    return m == null ? "You are not allowed to leave here." : m;
                }
                for (String id : left) {
                    if (MinecraftForge.EVENT_BUS.post(new RegionLeaveEvent(player, id, t.dimension, teleport))) {
                        String m = RegionQuery.value(oldLevel, oldPos, Flags.EXIT_DENY_MESSAGE);
                        return m == null ? "You are not allowed to leave here." : m;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isMemberOfAny(Player player, List<Region> regions) {
        for (Region r : regions) {
            if (!r.isGlobal() && r.isMember(player.getUUID())) {
                return true;
            }
        }
        return false;
    }

    /** Rank gating needs LuckPerms; without it every rank passes and the log says so once. */
    static boolean hasRank(ServerPlayer player, Set<String> ranks) {
        if (!LuckPermsBridge.isPresent()) {
            if (!warnedNoLuckPerms) {
                warnedNoLuckPerms = true;
                BSPRegionGuard.LOGGER.warn("A region uses allowed-ranks but LuckPerms is not available; rank checks are skipped.");
            }
            return true;
        }
        Set<String> groups = LuckPermsBridge.groupsOf(player.getUUID());
        for (String rank : ranks) {
            if (groups.contains(rank.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Puts the player back where they were last tick; if that is unknown, just outside the regions they entered. */
    private static boolean putBack(ServerPlayer player, Tracked t, List<Region> here) {
        if (t.pos != null && t.dimension != null) {
            ServerLevel old = player.server.getLevel(t.dimension);
            if (old != null) {
                player.teleportTo(old, t.pos.x, t.pos.y, t.pos.z, player.getYRot(), player.getXRot());
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0;
                return true;
            }
        }
        for (Region r : here) {
            Cuboid b = r.bounds();
            if (b != null) {
                Vec3 out = nearestOutside(player.position(), b);
                player.teleportTo(player.serverLevel(), out.x, out.y, out.z, player.getYRot(), player.getXRot());
                player.setDeltaMovement(Vec3.ZERO);
                return true;
            }
        }
        return false;
    }

    /** The closest point one block beyond the box's faces, keeping the height when possible. */
    static Vec3 nearestOutside(Vec3 p, Cuboid b) {
        double dxMin = p.x - b.minX(), dxMax = b.maxX() + 1 - p.x;
        double dzMin = p.z - b.minZ(), dzMax = b.maxZ() + 1 - p.z;
        double best = Math.min(Math.min(dxMin, dxMax), Math.min(dzMin, dzMax));
        if (best == dxMin) {
            return new Vec3(b.minX() - 1.5, p.y, p.z);
        }
        if (best == dxMax) {
            return new Vec3(b.maxX() + 2.5, p.y, p.z);
        }
        if (best == dzMin) {
            return new Vec3(p.x, p.y, b.minZ() - 1.5);
        }
        return new Vec3(p.x, p.y, b.maxZ() + 2.5);
    }

    // ------------------------------------------------------------------ greetings

    private static void greet(ServerPlayer player, RegionStore store, Region region) {
        String chat = RegionQuery.effective(store, region, Flags.GREETING);
        if (chat != null && !chat.isEmpty()) {
            player.sendSystemMessage(Component.literal(chat));
        }
        String title = RegionQuery.effective(store, region, Flags.GREETING_TITLE);
        if (title != null && !title.isEmpty()) {
            sendTitle(player, title);
        }
        if (Boolean.TRUE.equals(RegionQuery.effective(store, region, Flags.NOTIFY_ENTER))) {
            notifyAdmins(player, player.getName().getString() + " entered " + region.id() + " in " + store.dimension().location());
        }
    }

    private static void farewell(ServerPlayer player, RegionStore store, Region region) {
        String chat = RegionQuery.effective(store, region, Flags.FAREWELL);
        if (chat != null && !chat.isEmpty()) {
            player.sendSystemMessage(Component.literal(chat));
        }
        String title = RegionQuery.effective(store, region, Flags.FAREWELL_TITLE);
        if (title != null && !title.isEmpty()) {
            sendTitle(player, title);
        }
        if (Boolean.TRUE.equals(RegionQuery.effective(store, region, Flags.NOTIFY_LEAVE))) {
            notifyAdmins(player, player.getName().getString() + " left " + region.id() + " in " + store.dimension().location());
        }
    }

    /** "Title\nSubtitle" or "Title\\nSubtitle" (as typed in a command). */
    static void sendTitle(ServerPlayer player, String text) {
        String[] parts = text.replace("\\n", "\n").split("\n", 2);
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
        if (parts.length > 1) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(parts[1])));
        }
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(parts[0])));
    }

    private static void notifyAdmins(ServerPlayer who, String text) {
        Component line = Component.literal("[Region Guard] " + text);
        for (ServerPlayer p : who.server.getPlayerList().getPlayers()) {
            if (p != who && BspCore.isAdmin(p)) {
                p.sendSystemMessage(line);
            }
        }
    }

    // ------------------------------------------------------------------ game mode, time, weather

    private static void gameMode(ServerPlayer player, ServerLevel level, BlockPos pos) {
        GameType wanted = RegionQuery.value(level, pos, Flags.GAME_MODE);
        var persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (wanted != null) {
            if (player.gameMode.getGameModeForPlayer() != wanted) {
                if (!persisted.contains(PREV_GAME_MODE)) {
                    persisted.putString(PREV_GAME_MODE, player.gameMode.getGameModeForPlayer().getName());
                    player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
                }
                player.setGameMode(wanted);
            }
        } else if (persisted.contains(PREV_GAME_MODE)) {
            restoreGameMode(player);
        }
    }

    static void restoreGameMode(ServerPlayer player) {
        var persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (!persisted.contains(PREV_GAME_MODE)) {
            return;
        }
        GameType previous = GameType.byName(persisted.getString(PREV_GAME_MODE), GameType.SURVIVAL);
        persisted.remove(PREV_GAME_MODE);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        player.setGameMode(previous);
    }

    /** "day", "noon", "sunset", "night", "midnight", "sunrise" or a number of ticks; {@code null} when not a time. */
    @Nullable
    static Long parseTime(String text) {
        switch (text.toLowerCase(Locale.ROOT)) {
            case "day": return 1000L;
            case "noon": return 6000L;
            case "sunset": return 12000L;
            case "night": return 13000L;
            case "midnight": return 18000L;
            case "sunrise": return 23000L;
            default:
                try {
                    return Long.parseLong(text.trim()) % 24000L;
                } catch (NumberFormatException e) {
                    return null;
                }
        }
    }

    private static void timeLock(ServerPlayer player, ServerLevel level, BlockPos pos, Tracked t, long gameTime) {
        String lock = RegionQuery.value(level, pos, Flags.TIME_LOCK);
        Long time = lock == null ? null : parseTime(lock);
        if (time != null) {
            if (!t.timeLocked || gameTime % 20 == 0) {
                // A negative day time stops the client's clock, so the sun stands still.
                player.connection.send(new ClientboundSetTimePacket(level.getGameTime(), -time, false));
            }
            t.timeLocked = true;
        } else if (t.timeLocked) {
            t.timeLocked = false;
            player.connection.send(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                    level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        }
    }

    private static void weatherLock(ServerPlayer player, ServerLevel level, BlockPos pos, Tracked t) {
        String lock = RegionQuery.value(level, pos, Flags.WEATHER_LOCK);
        if (lock != null) {
            if (!t.weatherLocked) {
                t.weatherLocked = true;
                if (lock.equals("downfall")) {
                    player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0));
                    player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 1));
                } else {
                    player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0));
                    player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0));
                }
            }
        } else if (t.weatherLocked) {
            t.weatherLocked = false;
            if (level.isRaining()) {
                player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0));
                player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1)));
                player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1)));
            } else {
                player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0));
            }
        }
    }

    // ------------------------------------------------------------------ heal and feed

    private static void healAndFeed(ServerPlayer player, ServerLevel level, BlockPos pos, Tracked t, long gameTime) {
        if (RegionQuery.bypasses(player) || player.isCreative() || player.isSpectator()) {
            return;
        }
        Integer healDelay = RegionQuery.value(level, pos, Flags.HEAL_DELAY);
        Integer healAmount = RegionQuery.value(level, pos, Flags.HEAL_AMOUNT);
        if (healDelay != null && healAmount != null && healDelay > 0 && healAmount != 0) {
            if (t.lastHealTick == Long.MIN_VALUE || gameTime - t.lastHealTick >= healDelay * 20L) {
                t.lastHealTick = gameTime;
                Double min = RegionQuery.value(level, pos, Flags.HEAL_MIN_HEALTH);
                Double max = RegionQuery.value(level, pos, Flags.HEAL_MAX_HEALTH);
                float lo = min == null ? 0 : min.floatValue();
                float hi = max == null ? player.getMaxHealth() : (float) Math.min(max, player.getMaxHealth());
                float target = Mth.clamp(player.getHealth() + healAmount, lo, hi);
                if (healAmount > 0 && target > player.getHealth() || healAmount < 0 && target < player.getHealth()) {
                    player.setHealth(target);
                }
            }
        } else {
            t.lastHealTick = Long.MIN_VALUE;
        }
        Integer feedDelay = RegionQuery.value(level, pos, Flags.FEED_DELAY);
        Integer feedAmount = RegionQuery.value(level, pos, Flags.FEED_AMOUNT);
        if (feedDelay != null && feedAmount != null && feedDelay > 0 && feedAmount != 0) {
            if (t.lastFeedTick == Long.MIN_VALUE || gameTime - t.lastFeedTick >= feedDelay * 20L) {
                t.lastFeedTick = gameTime;
                Integer min = RegionQuery.value(level, pos, Flags.FEED_MIN_HUNGER);
                Integer max = RegionQuery.value(level, pos, Flags.FEED_MAX_HUNGER);
                int lo = min == null ? 0 : min, hi = max == null ? 20 : Math.min(max, 20);
                int target = Mth.clamp(player.getFoodData().getFoodLevel() + feedAmount, lo, hi);
                player.getFoodData().setFoodLevel(target);
            }
        } else {
            t.lastFeedTick = Long.MIN_VALUE;
        }
    }
}
