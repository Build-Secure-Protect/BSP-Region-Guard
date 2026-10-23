package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.protection.TotemRule;
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

    /**
     * B11, D92, D93: plots in a PvP world follow the world default's own switch, then the config; a world default with
     * pvp denied counts as not PvP for plots, while the totem exemption still treats the world as a PvP world (D94).
     * Its own batch, since it changes the world default's pvp flag.
     */
    @GameTest(template = EMPTY, batch = "plots-pvp")
    public void plots_follow_the_world_switch_and_a_denied_pvp_flag(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Region global = store(h).global();
        boolean configWas = com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.get();
        State pvpWas = global.flag(Flags.PVP);
        String ownWas = global.typeData().getString(TotemRule.PLOTS_IN_PVP);
        try {
            com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(false);
            global.setFlag(Flags.PVP, null);
            global.typeData().remove(TotemRule.PLOTS_IN_PVP);
            h.assertTrue(TotemRule.plotRule(level) == TotemRule.PlotRule.CONFIG_REFUSES, "the config refuses plots in a PvP world");
            var r = com.mrgregles.bsp_region_guard.command.RegionEdits.plotsInPvp(level, "allow");
            h.assertTrue(r.ok() && TotemRule.claimsAllowedIn(level) && TotemRule.plotRule(level) == TotemRule.PlotRule.WORLD_ALLOWS, "the world's switch allows them");
            com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(true);
            com.mrgregles.bsp_region_guard.command.RegionEdits.plotsInPvp(level, "deny");
            h.assertFalse(TotemRule.claimsAllowedIn(level), "the world's switch wins over the config");
            String refusal = TotemRule.plotsRefused(level);
            h.assertTrue(refusal.contains("/rg claims pvp allow"), "the refusal says how to allow plots");
            com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(false);
            com.mrgregles.bsp_region_guard.command.RegionEdits.plotsInPvp(level, "default");
            global.setFlag(Flags.PVP, State.DENY);
            h.assertTrue(TotemRule.plotRule(level) == TotemRule.PlotRule.PVP_DENIED, "pvp denied on the world default allows plots");
            h.assertTrue(TotemRule.isPvpWorld(level), "the totem rule still counts it as a PvP world");
            h.assertFalse(com.mrgregles.bsp_region_guard.command.RegionEdits.plotsInPvp(level, "maybe").ok(), "only allow, deny or default");
        } finally {
            com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(configWas);
            global.setFlag(Flags.PVP, pvpWas);
            if (ownWas.isEmpty()) {
                global.typeData().remove(TotemRule.PLOTS_IN_PVP);
            } else {
                global.typeData().putString(TotemRule.PLOTS_IN_PVP, ownWas);
            }
            store(h).touch(global);
        }
        h.succeed();
    }

    /** D32: until the zone systems merge, a BSP-Core Anti Totem zone denies the matching region flags too. */
    @GameTest(template = EMPTY)
    public void bsp_core_anti_totem_zones_deny_the_matching_flags(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Player player = h.makeMockPlayer();
        BlockPos centre = h.absolutePos(new BlockPos(5, 2, 5));
        BlockPos inside = h.absolutePos(new BlockPos(6, 2, 5));
        BlockPos outside = h.absolutePos(new BlockPos(5 + 6, 2, 5));
        BspCore.putZone(level, centre, 3, java.util.Set.of(BspCore.ZONE_TELEPORT_IN, BspCore.ZONE_TOTEM_PLACE));
        try {
            h.assertFalse(RegionQuery.allows(level, inside, Flags.TELEPORT_IN, player), "teleport-in is denied inside a BSP-Core zone that blocks it");
            h.assertFalse(RegionQuery.allows(level, inside, Flags.TOTEM_PLACE, player), "totem-place too");
            h.assertTrue(RegionQuery.allows(level, inside, Flags.TELEPORT_OUT, player), "a rule the zone does not block stays allowed");
            h.assertTrue(RegionQuery.allows(level, inside, Flags.BUILD, player), "other flags are untouched");
            h.assertTrue(RegionQuery.allows(level, outside, Flags.TELEPORT_IN, player), "outside the zone the rule allows");
        } finally {
            BspCore.removeZone(level, centre);
        }
        h.assertTrue(RegionQuery.allows(level, inside, Flags.TELEPORT_IN, player), "once the zone is gone the rule allows again");
        h.succeed();
    }
}
