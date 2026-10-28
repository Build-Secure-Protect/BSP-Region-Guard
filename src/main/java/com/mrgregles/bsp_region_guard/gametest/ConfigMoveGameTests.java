package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.NetworkConfig;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.RegionGuardApi;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * T14 (D126 to D128): the shared storage's connection moves out of the server config, which Forge sends to every
 * player, into config/bsp_region_guard-network.toml. Its own batch, since it changes and saves both config files.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class ConfigMoveGameTests {
    private static final String EMPTY = "empty";

    @GameTest(template = EMPTY, batch = "config-move")
    public void the_connection_moves_out_of_the_synced_config_once(GameTestHelper h) {
        String host = NetworkConfig.DB_HOST.get(), password = NetworkConfig.DB_PASSWORD.get(), redisPassword = NetworkConfig.REDIS_PASSWORD.get();
        int port = NetworkConfig.DB_PORT.get();
        try {
            // an older install: everything still in the server config, and the new file already given its own host
            RegionGuardConfig.MOVED_STORAGE_HOST.set("old.lan");
            RegionGuardConfig.MOVED_STORAGE_PORT.set(3307);
            RegionGuardConfig.MOVED_STORAGE_PASSWORD.set("s3cret-db");
            RegionGuardConfig.MOVED_REDIS_PASSWORD.set("s3cret-redis");
            NetworkConfig.DB_HOST.set("db.lan");
            NetworkConfig.DB_PORT.set(NetworkConfig.DB_PORT.getDefault());
            NetworkConfig.DB_PASSWORD.set("");
            NetworkConfig.REDIS_PASSWORD.set("");

            String first = NetworkConfig.moveFromServerConfig();
            h.assertTrue(first.contains("storage.password") && first.contains("storage.redis.password") && first.contains("storage.host") && first.contains("storage.port"),
                    "the warning names what moved: " + first);
            h.assertFalse(first.contains("s3cret") || first.contains("old.lan") || first.contains("3307"), "and never a value");
            h.assertTrue("s3cret-db".equals(NetworkConfig.DB_PASSWORD.get()) && "s3cret-redis".equals(NetworkConfig.REDIS_PASSWORD.get()), "the passwords moved");
            h.assertTrue(NetworkConfig.DB_PORT.get() == 3307, "the port moved");
            h.assertTrue("db.lan".equals(NetworkConfig.DB_HOST.get()), "a value already in the new file wins");
            h.assertTrue("".equals(RegionGuardConfig.MOVED_STORAGE_PASSWORD.get()) && "".equals(RegionGuardConfig.MOVED_REDIS_PASSWORD.get())
                    && "localhost".equals(RegionGuardConfig.MOVED_STORAGE_HOST.get()) && RegionGuardConfig.MOVED_STORAGE_PORT.get() == 3306,
                    "the server config keeps only empty placeholders");
            h.assertTrue("s3cret-db".equals(RegionGuardConfig.STORAGE_PASSWORD.get()) && RegionGuardConfig.STORAGE_HOST == NetworkConfig.DB_HOST,
                    "the old names answer from the new file");

            String second = NetworkConfig.moveFromServerConfig();
            h.assertTrue(second.isEmpty(), "a second start moves and logs nothing: " + second);
            h.assertTrue("s3cret-db".equals(NetworkConfig.DB_PASSWORD.get()), "and keeps the moved values");

            h.assertTrue(RegionGuardConfig.STORAGE_SERVER_ID.get().equals(RegionGuardApi.get().serverId()), "serverId still answers from the server config");
        } finally {
            NetworkConfig.DB_HOST.set(host);
            NetworkConfig.DB_PORT.set(port);
            NetworkConfig.DB_PASSWORD.set(password);
            NetworkConfig.REDIS_PASSWORD.set(redisPassword);
            RegionGuardConfig.MOVED_STORAGE_HOST.set(RegionGuardConfig.MOVED_STORAGE_HOST.getDefault());
            RegionGuardConfig.MOVED_STORAGE_PORT.set(RegionGuardConfig.MOVED_STORAGE_PORT.getDefault());
            RegionGuardConfig.MOVED_STORAGE_PASSWORD.set("");
            RegionGuardConfig.MOVED_REDIS_PASSWORD.set("");
            NetworkConfig.SPEC.save();
            RegionGuardConfig.SPEC.save();
        }
        h.succeed();
    }
}
