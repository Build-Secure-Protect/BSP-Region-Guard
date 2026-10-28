package com.mrgregles.bsp_region_guard;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The shared storage's connection, in a COMMON config of its own, {@code config/bsp_region_guard-network.toml}
 * (owner decisions D126 to D128). Forge sends every SERVER config file to each player at login, so a database or Redis
 * password there reaches every player; COMMON configs are never sent. What is not secret (the storage mode, this
 * server's id, how often to poll, the Redis switch and channel) stays in {@link RegionGuardConfig}.
 */
public final class NetworkConfig {
    public static final String FILE = BSPRegionGuard.MODID + "-network.toml";
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> DB_HOST, DB_DATABASE, DB_USER, DB_PASSWORD, DB_TABLE_PREFIX;
    public static final ForgeConfigSpec.IntValue DB_PORT, REDIS_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_HOST, REDIS_USERNAME, REDIS_PASSWORD;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Region Guard's shared storage connection. Kept apart from bsp_region_guard-server.toml because Forge sends",
                "server configs to every player at login; this file is never sent. Never ship it in a client pack.",
                "Whether storage is used at all (storage.mode), this server's id and the Redis switch stay in the server config.").push("database");
        DB_HOST = b.define("host", "localhost");
        DB_PORT = b.defineInRange("port", 3306, 1, 65535);
        DB_DATABASE = b.define("database", "bsp");
        DB_USER = b.define("user", "bsp");
        DB_PASSWORD = b.comment("Plain text; keep this file private.").define("password", "");
        DB_TABLE_PREFIX = b.define("tablePrefix", "bsp_rg_");
        b.pop();
        b.comment("Redis, when storage.redis.enabled is on in the server config. Plain connection: keep Redis on the local network.").push("redis");
        REDIS_HOST = b.define("host", "localhost");
        REDIS_PORT = b.defineInRange("port", 6379, 1, 65535);
        REDIS_USERNAME = b.comment("Leave empty unless Redis uses ACL users; the password alone is the usual requirepass.").define("username", "");
        REDIS_PASSWORD = b.comment("Plain text; keep this file private.").define("password", "");
        b.pop();
        SPEC = b.build();
    }

    private NetworkConfig() {
    }

    /** One old server-config key and where its value goes. */
    private record Move<T>(String key, ForgeConfigSpec.ConfigValue<T> from, ForgeConfigSpec.ConfigValue<T> to) {
        /** True when an old value was cleared from the server config (and copied unless the new file already had one). */
        boolean run() {
            T old = from.get();
            if (Objects.equals(old, from.getDefault())) {
                return false;
            }
            if (Objects.equals(to.get(), to.getDefault())) {
                to.set(old); // a value already set in the new file wins
            }
            from.set(from.getDefault());
            return true;
        }
    }

    /**
     * The one-time move from the server config (D128): every old connection value that is not its default goes to this
     * file, unless this file already has its own, and the old key goes back to its default. Run at server about to
     * start, when server configs are loaded and nobody can have joined. Returns the warning it logged, which names keys
     * only and never a value, or an empty string when nothing was there to move.
     */
    public static String moveFromServerConfig() {
        List<Move<?>> moves = List.of(
                new Move<>("storage.host", RegionGuardConfig.MOVED_STORAGE_HOST, DB_HOST),
                new Move<>("storage.port", RegionGuardConfig.MOVED_STORAGE_PORT, DB_PORT),
                new Move<>("storage.database", RegionGuardConfig.MOVED_STORAGE_DATABASE, DB_DATABASE),
                new Move<>("storage.user", RegionGuardConfig.MOVED_STORAGE_USER, DB_USER),
                new Move<>("storage.password", RegionGuardConfig.MOVED_STORAGE_PASSWORD, DB_PASSWORD),
                new Move<>("storage.tablePrefix", RegionGuardConfig.MOVED_STORAGE_TABLE_PREFIX, DB_TABLE_PREFIX),
                new Move<>("storage.redis.host", RegionGuardConfig.MOVED_REDIS_HOST, REDIS_HOST),
                new Move<>("storage.redis.port", RegionGuardConfig.MOVED_REDIS_PORT, REDIS_PORT),
                new Move<>("storage.redis.username", RegionGuardConfig.MOVED_REDIS_USERNAME, REDIS_USERNAME),
                new Move<>("storage.redis.password", RegionGuardConfig.MOVED_REDIS_PASSWORD, REDIS_PASSWORD));
        List<String> moved = new ArrayList<>();
        try {
            for (Move<?> m : moves) {
                if (m.run()) {
                    moved.add(m.key());
                }
            }
        } catch (IllegalStateException e) {
            return ""; // a config not loaded (should not happen at server about to start)
        }
        if (moved.isEmpty()) {
            return "";
        }
        RegionGuardConfig.SPEC.save();
        SPEC.save();
        String line = "Region Guard moved " + String.join(", ", moved) + " from serverconfig/" + BSPRegionGuard.MODID + "-server.toml to config/" + FILE
                + ": server configs are sent to every player at login, this one is not. Change those passwords if players may have joined since they were set.";
        BSPRegionGuard.LOGGER.warn(line);
        return line;
    }
}
