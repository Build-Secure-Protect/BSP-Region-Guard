package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.protection.TotemRule;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** The Shatter Totem rule: a region never keeps a raider from someone else's placed totem, and claims stay out of PvP worlds. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class TotemGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    @GameTest(template = EMPTY)
    public void raiders_are_not_stopped_near_another_players_totem(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.assertTrue(TotemRule.isPvpWorld(level), "the test world counts as a PvP world (BSP Core allows totems in the overworld)");
        Player owner = h.makeMockPlayer();
        Player raider = h.makeMockPlayer();
        BlockPos totem = h.absolutePos(new BlockPos(5, 2, 5));
        BlockPos near = h.absolutePos(new BlockPos(7, 2, 5));       // 2 blocks away, inside the steal radius
        BlockPos far = h.absolutePos(new BlockPos(5 + BspCore.stealRadius() + 3, 2, 5)); // beyond it, still in the region
        Region r = new Region("tt_rule", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(22, 8, 22))), RegionType.PROTECTED);
        r.setFlag(Flags.BUILD, State.DENY);
        r.setFlag(Flags.ENTRY, State.DENY);
        r.setFlag(Flags.PVP, State.DENY);
        store(h).add(r);
        BspCore.recordPlacedTotem(level, owner.getUUID(), totem, 0);
        try {
            h.assertTrue(RegionQuery.allows(level, near, Flags.BLOCK_BREAK, raider), "a raider may break near the totem despite build deny");
            h.assertTrue(RegionQuery.allows(level, near, Flags.INTERACT, raider), "and interact");
            h.assertTrue(RegionQuery.allows(level, near, Flags.PVP, raider), "and pvp cannot be switched off there");
            h.assertTrue(RegionQuery.isExempt(level, near, Flags.ENTRY, raider), "and entry cannot be refused");
            h.assertFalse(RegionQuery.allows(level, near, Flags.BLOCK_BREAK, owner), "the totem's own owner gets no exemption");
            h.assertFalse(RegionQuery.allows(level, far, Flags.BLOCK_BREAK, raider), "beyond the steal radius the region protects as usual");
            h.assertFalse(RegionQuery.allows(level, near, Flags.FIRE_SPREAD, null) && r.flag(Flags.FIRE_SPREAD) == State.DENY, "world flags are untouched");
            h.assertTrue(RegionQuery.allows(level, near, Flags.FIRE_SPREAD, null), "fire-spread (unset) still allows");
        } finally {
            BspCore.forgetPlacedTotem(level, totem);
            store(h).remove("tt_rule");
        }
        h.assertFalse(RegionQuery.allows(level, near, Flags.BLOCK_BREAK, raider) && store(h).has("tt_rule"), "cleanup");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void raider_walks_into_an_entry_deny_region_near_a_totem(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer raider = TestPlayers.mockServerPlayer(h);
        BlockPos totem = h.absolutePos(new BlockPos(4, 2, 5));
        Region r = new Region("tt_walk", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(9, 8, 9))), RegionType.PROTECTED);
        r.setFlag(Flags.ENTRY, State.DENY);
        store(h).add(r);
        BspCore.recordPlacedTotem(level, java.util.UUID.randomUUID(), totem, 0);
        try {
            BlockPos out = h.absolutePos(new BlockPos(11, 2, 5)), in = h.absolutePos(new BlockPos(8, 2, 5));
            raider.setPos(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
            MovementTracker.tick(raider, 0);
            raider.setPos(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
            MovementTracker.tick(raider, 1);
            h.assertTrue(raider.blockPosition().equals(in), "the raider is let in: the totem is within the steal radius of the border");
        } finally {
            BspCore.forgetPlacedTotem(level, totem);
            store(h).remove("tt_walk");
            MovementTracker.clearAll();
            raider.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void claims_are_refused_in_pvp_worlds(GameTestHelper h) {
        h.assertFalse(TotemRule.claimsAllowedIn(h.getLevel()), "no claims in a PvP world by default");
        h.succeed();
    }
}
