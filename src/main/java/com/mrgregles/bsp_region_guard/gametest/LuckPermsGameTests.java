package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.claim.Claims;
import com.mrgregles.bsp_region_guard.compat.LuckPermsBridge;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/**
 * The LuckPerms bridge against the real LuckPerms Forge mod (luckperms_dev_version in gradle.properties, dev runs only: see build.gradle and
 * tools/make_luckperms_dev_jar.py). Skips itself with a note when LuckPerms is not on the classpath, so the suite
 * still passes in a checkout without the jar.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class LuckPermsGameTests {
    private static final String EMPTY = "empty";

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public void ranks_come_from_luckperms_and_gate_entry_and_claim_limits(GameTestHelper h) {
        if (!LuckPermsBridge.isPresent()) {
            BSPRegionGuard.LOGGER.warn("LuckPerms is not loaded in this run; the LuckPerms test did nothing.");
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        ServerPlayer vip = TestPlayers.mockServerPlayer(h);
        ServerPlayer pleb = TestPlayers.mockServerPlayer(h);
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        net.luckperms.api.model.group.Group group = null;
        net.luckperms.api.model.user.User vipUser = null;
        Region r = new Region("lp_gate", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(9, 8, 9))), RegionType.PROTECTED);
        r.setFlag(Flags.ALLOWED_RANKS, Set.of("vip"));
        RegionStore.get(level).add(r);
        try {
            group = lp.getGroupManager().createAndLoadGroup("vip").join();
            vipUser = lp.getUserManager().loadUser(vip.getUUID(), vip.getGameProfile().getName()).join();
            vipUser.data().add(net.luckperms.api.node.types.InheritanceNode.builder(group).build());
            lp.getUserManager().saveUser(vipUser).join();
            lp.getUserManager().loadUser(pleb.getUUID(), pleb.getGameProfile().getName()).join();

            h.assertTrue(LuckPermsBridge.groupNames().contains("vip") && LuckPermsBridge.groupNames().contains("default"), "the bridge lists the groups: " + LuckPermsBridge.groupNames());
            h.assertTrue(LuckPermsBridge.groupsOf(vip.getUUID()).contains("vip"), "the vip player's groups include vip: " + LuckPermsBridge.groupsOf(vip.getUUID()));
            h.assertFalse(LuckPermsBridge.groupsOf(pleb.getUUID()).contains("vip"), "the other player is not vip");

            BlockPos out = h.absolutePos(new BlockPos(11, 2, 5)), in = h.absolutePos(new BlockPos(8, 2, 5));
            for (ServerPlayer p : new ServerPlayer[]{vip, pleb}) {
                p.setPos(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
                MovementTracker.tick(p, 0);
                p.setPos(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
                MovementTracker.tick(p, 1);
            }
            h.assertTrue(vip.blockPosition().equals(in), "the vip walks into the rank-gated region");
            h.assertTrue(pleb.blockPosition().equals(out), "the other player is held at the border");

            h.assertTrue(Claims.INSTANCE.claimLimit(vip) == 5, "vip:5 from the config raises the claim limit: " + Claims.INSTANCE.claimLimit(vip));
            h.assertTrue(Claims.INSTANCE.claimLimit(pleb) == 3, "the default limit stays 3: " + Claims.INSTANCE.claimLimit(pleb));
        } finally {
            RegionStore.get(level).remove("lp_gate");
            MovementTracker.clearAll();
            if (vipUser != null) {
                lp.getUserManager().cleanupUser(vipUser);
            }
            if (group != null) {
                lp.getGroupManager().deleteGroup(group).join();
            }
            vip.discard();
            pleb.discard();
        }
        h.succeed();
    }
}
