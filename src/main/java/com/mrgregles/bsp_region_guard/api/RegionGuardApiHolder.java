package com.mrgregles.bsp_region_guard.api;

import net.minecraftforge.fml.ModList;

/** Holds the API instance. Package-private so callers only ever see {@link RegionGuardApi}. */
final class RegionGuardApiHolder {
    static final RegionGuardApi INSTANCE = new RegionGuardApi() {
        @Override
        public String modVersion() {
            return ModList.get().getModContainerById("bsp_region_guard")
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
        }

        @Override
        public String serverId() {
            var mirror = com.mrgregles.bsp_region_guard.storage.RegionMirror.active();
            return mirror != null ? mirror.serverId()
                    : com.mrgregles.bsp_region_guard.RegionGuardConfig.getOr(com.mrgregles.bsp_region_guard.RegionGuardConfig.STORAGE_SERVER_ID, "server-1");
        }

        @Override
        public ClaimService claims() {
            return com.mrgregles.bsp_region_guard.claim.Claims.INSTANCE;
        }

        @Override
        public RegionService regions() {
            return REGIONS;
        }

        @Override
        public MineService mines() {
            return MINES;
        }

        @Override
        public boolean publish(String kind, String dimension, String regionId, String payload) {
            var mirror = com.mrgregles.bsp_region_guard.storage.RegionMirror.active();
            if (mirror == null) {
                return false;
            }
            mirror.publish(kind, dimension, regionId, payload);
            return true;
        }
    };

    private static final RegionService REGIONS = new RegionService() {
        @Override
        public java.util.List<String> regionIdsAt(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
            return com.mrgregles.bsp_region_guard.region.RegionQuery.at(level, pos).stream().map(com.mrgregles.bsp_region_guard.region.Region::id).toList();
        }

        @Override
        public boolean allows(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, com.mrgregles.bsp_region_guard.flag.StateFlag flag, net.minecraft.world.entity.player.Player player) {
            return com.mrgregles.bsp_region_guard.region.RegionQuery.allows(level, pos, flag, player);
        }

        @Override
        public <T> T value(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, com.mrgregles.bsp_region_guard.flag.Flag<T> flag) {
            return com.mrgregles.bsp_region_guard.region.RegionQuery.value(level, pos, flag);
        }

        @Override
        public Cuboid bounds(net.minecraft.server.level.ServerLevel level, String regionId) {
            var r = com.mrgregles.bsp_region_guard.region.RegionStore.get(level).get(regionId);
            return r == null || !r.hasCorners() ? null : r.bounds();
        }

        @Override
        public Border border(net.minecraft.server.level.ServerLevel level) {
            return com.mrgregles.bsp_region_guard.protection.WorldBorders.of(level);
        }

        @Override
        public <F extends com.mrgregles.bsp_region_guard.flag.Flag<?>> F registerFlag(F flag) {
            return com.mrgregles.bsp_region_guard.flag.FlagRegistry.register(flag);
        }

        @Override
        public void addExemption(Exemption exemption) {
            com.mrgregles.bsp_region_guard.region.RegionQuery.addExemption(exemption);
        }
    };

    private static final MineService MINES = new MineService() {
        @Override
        public boolean isMine(net.minecraft.server.level.ServerLevel level, String regionId) {
            var r = com.mrgregles.bsp_region_guard.region.RegionStore.get(level).get(regionId);
            return r != null && r.type() == com.mrgregles.bsp_region_guard.region.RegionType.MINE;
        }

        @Override
        public String reset(net.minecraft.server.level.ServerLevel level, String mineId) {
            var store = com.mrgregles.bsp_region_guard.region.RegionStore.get(level);
            var r = store.get(mineId);
            return r == null ? "No region named '" + mineId + "'." : com.mrgregles.bsp_region_guard.mine.Mines.reset(level, store, r);
        }

        @Override
        public long secondsToReset(net.minecraft.server.level.ServerLevel level, String mineId) {
            var r = com.mrgregles.bsp_region_guard.region.RegionStore.get(level).get(mineId);
            return r == null || r.type() != com.mrgregles.bsp_region_guard.region.RegionType.MINE ? -1 : com.mrgregles.bsp_region_guard.mine.Mines.secondsToReset(r);
        }
    };

    private RegionGuardApiHolder() {
    }
}
