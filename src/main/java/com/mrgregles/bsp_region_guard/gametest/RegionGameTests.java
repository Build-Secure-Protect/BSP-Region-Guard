package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Forge GameTests for the region model and the resolution rules. Run headlessly with
 * {@code ./gradlew runGameTestServer}. Every test works inside the empty template {@code bsp_region_guard:empty}
 * (24 x 10 x 24 of air, from tools/gen_gametest_structures.py), with region ids unique to the test so tests that
 * share a world do not collide, and removes its regions when done.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class RegionGameTests {
    private static final String EMPTY = "empty";

    // ------------------------------------------------------------------ helpers

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    /** A region covering the template-relative box, with a test-unique id. */
    private static Region define(GameTestHelper h, String id, BlockPos a, BlockPos b, int priority) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(a), h.absolutePos(b)), RegionType.PROTECTED);
        r.setPriority(priority);
        store(h).add(r);
        return r;
    }

    private static void cleanup(GameTestHelper h, String... ids) {
        for (String id : ids) {
            store(h).remove(id);
        }
    }

    private static void finish(GameTestHelper h, Runnable body, String... ids) {
        try {
            body.run();
        } finally {
            cleanup(h, ids);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ containment and index

    @GameTest(template = EMPTY)
    public void region_is_found_inside_and_not_outside(GameTestHelper h) {
        finish(h, () -> {
            Region r = define(h, "t_contain", new BlockPos(2, 1, 2), new BlockPos(6, 4, 6), 0);
            List<Region> inside = store(h).at(h.absolutePos(new BlockPos(4, 2, 4)));
            h.assertTrue(inside.contains(r), "region found at a position inside it");
            List<Region> edge = store(h).at(h.absolutePos(new BlockPos(6, 4, 6)));
            h.assertTrue(edge.contains(r), "both corners are inclusive");
            List<Region> outside = store(h).at(h.absolutePos(new BlockPos(7, 2, 4)));
            h.assertFalse(outside.contains(r), "region not found one block outside");
            h.assertTrue(store(h).nothingAt(h.absolutePos(new BlockPos(20, 1, 20)).getX() + 5000, 5000), "a far column is empty");
        }, "t_contain");
    }

    @GameTest(template = EMPTY)
    public void redefine_moves_the_region_in_the_index(GameTestHelper h) {
        finish(h, () -> {
            Region r = define(h, "t_move", new BlockPos(1, 1, 1), new BlockPos(3, 3, 3), 0);
            BlockPos oldInside = h.absolutePos(new BlockPos(2, 2, 2));
            BlockPos newInside = h.absolutePos(new BlockPos(12, 2, 12));
            h.assertTrue(store(h).at(oldInside).contains(r), "found at the old place");
            r.setBounds(Cuboid.of(h.absolutePos(new BlockPos(11, 1, 11)), h.absolutePos(new BlockPos(13, 3, 13))));
            store(h).touch(r);
            h.assertFalse(store(h).at(oldInside).contains(r), "gone from the old place");
            h.assertTrue(store(h).at(newInside).contains(r), "found at the new place");
            h.assertTrue(r.version() == 2, "version counts the add and the touch");
        }, "t_move");
    }

    // ------------------------------------------------------------------ resolution rules

    @GameTest(template = EMPTY)
    public void deny_beats_allow_at_the_same_priority_and_higher_priority_wins(GameTestHelper h) {
        finish(h, () -> {
            Region a = define(h, "t_prio_a", new BlockPos(1, 1, 1), new BlockPos(8, 8, 8), 0);
            Region b = define(h, "t_prio_b", new BlockPos(1, 1, 1), new BlockPos(8, 8, 8), 0);
            a.setFlag(Flags.PVP, State.ALLOW);
            b.setFlag(Flags.PVP, State.DENY);
            store(h).touch(a);
            store(h).touch(b);
            BlockPos p = h.absolutePos(new BlockPos(4, 4, 4));
            h.assertFalse(RegionQuery.allows(h.getLevel(), p, Flags.PVP), "deny beats allow at one priority");
            a.setPriority(5);
            store(h).touch(a);
            h.assertTrue(RegionQuery.allows(h.getLevel(), p, Flags.PVP), "the higher priority allow wins");
            h.assertTrue(RegionQuery.allows(h.getLevel(), h.absolutePos(new BlockPos(20, 1, 20)), Flags.PVP), "default allow outside");
        }, "t_prio_a", "t_prio_b");
    }

    @GameTest(template = EMPTY)
    public void child_inherits_from_parent_and_speaks_for_it(GameTestHelper h) {
        finish(h, () -> {
            Region parent = define(h, "t_parent", new BlockPos(1, 1, 1), new BlockPos(10, 8, 10), 0);
            Region child = define(h, "t_child", new BlockPos(3, 1, 3), new BlockPos(5, 8, 5), 0);
            parent.setFlag(Flags.MOB_SPAWNING, State.DENY);
            parent.setFlag(Flags.PVP, State.DENY);
            child.setParentId(parent.id());
            child.setFlag(Flags.PVP, State.ALLOW);
            store(h).touch(parent);
            store(h).touch(child);
            BlockPos inChild = h.absolutePos(new BlockPos(4, 4, 4));
            List<Region> here = store(h).at(inChild);
            h.assertTrue(here.contains(child) && !here.contains(parent), "the parent is left out where the child applies");
            h.assertFalse(RegionQuery.allows(h.getLevel(), inChild, Flags.MOB_SPAWNING), "child inherits the parent's deny");
            h.assertTrue(RegionQuery.allows(h.getLevel(), inChild, Flags.PVP), "the child's own value overrides the parent");
            h.assertFalse(RegionQuery.allows(h.getLevel(), h.absolutePos(new BlockPos(8, 4, 8)), Flags.PVP), "the parent still applies outside the child");
        }, "t_child", "t_parent");
    }

    @GameTest(template = EMPTY)
    public void build_is_for_members_unless_set_explicitly(GameTestHelper h) {
        Player member = h.makeMockPlayer();
        Player stranger = h.makeMockPlayer();
        finish(h, () -> {
            Region r = define(h, "t_build", new BlockPos(1, 1, 1), new BlockPos(8, 8, 8), 0);
            r.addMember(member.getUUID());
            store(h).touch(r);
            BlockPos in = h.absolutePos(new BlockPos(4, 4, 4));
            BlockPos out = h.absolutePos(new BlockPos(20, 4, 20));
            h.assertTrue(RegionQuery.allows(h.getLevel(), in, Flags.BLOCK_BREAK, member), "a member may break");
            h.assertFalse(RegionQuery.allows(h.getLevel(), in, Flags.BLOCK_BREAK, stranger), "a stranger may not");
            h.assertTrue(RegionQuery.allows(h.getLevel(), out, Flags.BLOCK_BREAK, stranger), "anyone may build where nothing protects");
            r.setFlag(Flags.BUILD, State.ALLOW);
            store(h).touch(r);
            h.assertTrue(RegionQuery.allows(h.getLevel(), in, Flags.BLOCK_BREAK, stranger), "block-break falls back to an explicit build allow");
            r.setFlag(Flags.BUILD, State.DENY);
            store(h).touch(r);
            h.assertFalse(RegionQuery.allows(h.getLevel(), in, Flags.BLOCK_PLACE, member), "an explicit build deny stops members too");
            r.setFlag(Flags.BLOCK_PLACE, State.ALLOW);
            store(h).touch(r);
            h.assertTrue(RegionQuery.allows(h.getLevel(), in, Flags.BLOCK_PLACE, member), "a flag set on its own beats the build fallback");
            h.assertFalse(RegionQuery.allows(h.getLevel(), in, Flags.BLOCK_BREAK, member), "other build-family flags still see the deny");
        }, "t_build");
    }

    @GameTest(template = EMPTY)
    public void passthrough_region_does_not_protect(GameTestHelper h) {
        Player stranger = h.makeMockPlayer();
        finish(h, () -> {
            Region r = define(h, "t_pass", new BlockPos(1, 1, 1), new BlockPos(8, 8, 8), 0);
            r.setFlag(Flags.PASSTHROUGH, State.ALLOW);
            r.setFlag(Flags.GREETING, "hello");
            store(h).touch(r);
            BlockPos in = h.absolutePos(new BlockPos(4, 4, 4));
            h.assertTrue(RegionQuery.allows(h.getLevel(), in, Flags.BLOCK_BREAK, stranger), "a passthrough region lets strangers build");
            h.assertTrue("hello".equals(RegionQuery.value(h.getLevel(), in, Flags.GREETING)), "but its other flags still apply");
        }, "t_pass");
    }

    @GameTest(template = EMPTY)
    public void global_region_applies_everywhere_and_is_overridden(GameTestHelper h) {
        finish(h, () -> {
            Region global = store(h).global();
            global.setFlag(Flags.PVP, State.DENY);
            store(h).touch(global);
            Region r = define(h, "t_global_r", new BlockPos(1, 1, 1), new BlockPos(4, 4, 4), 0);
            r.setFlag(Flags.PVP, State.ALLOW);
            store(h).touch(r);
            h.assertFalse(RegionQuery.allows(h.getLevel(), h.absolutePos(new BlockPos(20, 1, 20)), Flags.PVP), "the global deny applies in the open");
            h.assertTrue(RegionQuery.allows(h.getLevel(), h.absolutePos(new BlockPos(2, 2, 2)), Flags.PVP), "a region overrides the global value");
            global.setFlag(Flags.PVP, null);
            store(h).touch(global);
        }, "t_global_r");
    }

    @GameTest(template = EMPTY)
    public void non_state_flags_take_the_top_priority_value(GameTestHelper h) {
        finish(h, () -> {
            Region low = define(h, "t_msg_low", new BlockPos(1, 1, 1), new BlockPos(8, 8, 8), 0);
            Region high = define(h, "t_msg_high", new BlockPos(1, 1, 1), new BlockPos(8, 8, 8), 3);
            low.setFlag(Flags.DENY_MESSAGE, "low");
            low.setFlag(Flags.HEAL_DELAY, 7);
            high.setFlag(Flags.DENY_MESSAGE, "high");
            store(h).touch(low);
            store(h).touch(high);
            BlockPos in = h.absolutePos(new BlockPos(4, 4, 4));
            h.assertTrue("high".equals(RegionQuery.denyMessage(h.getLevel(), in)), "the higher priority message wins");
            h.assertTrue(Integer.valueOf(7).equals(RegionQuery.value(h.getLevel(), in, Flags.HEAL_DELAY)), "a flag only the lower region sets still comes through");
            h.assertTrue("You can't do that here.".equals(RegionQuery.denyMessage(h.getLevel(), h.absolutePos(new BlockPos(20, 1, 20)))), "default message outside");
        }, "t_msg_low", "t_msg_high");
    }

    // ------------------------------------------------------------------ saving

    @GameTest(template = EMPTY)
    public void region_survives_a_save_and_load(GameTestHelper h) {
        finish(h, () -> {
            Region r = new Region("t_save", h.getLevel().dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(5, 5, 5))), RegionType.CLAIM);
            r.setName("Saved plot");
            r.setPriority(4);
            r.setParentId("t_save_parent");
            UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
            r.addOwner(owner);
            r.addMember(member);
            r.addRank("Gold");
            r.setFlag(Flags.PVP, State.DENY);
            r.setFlag(Flags.GREETING, "Welcome");
            r.setFlag(Flags.HEAL_AMOUNT, 2);
            r.setFlag(Flags.HEAL_MIN_HEALTH, 4.5);
            r.setFlag(Flags.EXIT_OVERRIDE, true);
            r.setFlag(Flags.DENY_SPAWN, Set.of("minecraft:creeper", "minecraft:zombie"));
            r.setFlag(Flags.TELEPORT, FlagLocation.of(h.getLevel(), 1.5, 64, -3.5, 90, 10));
            r.setFlag(Flags.GAME_MODE, net.minecraft.world.level.GameType.ADVENTURE);
            r.typeData().putInt("price", 1200);
            CompoundTag tag = r.save();
            tag.getCompound("flags").putString("othermod-flag", "kept");

            Region back = Region.load(tag);
            h.assertTrue(back.id().equals("t_save") && back.name().equals("Saved plot"), "id and name");
            h.assertTrue(back.type() == RegionType.CLAIM && back.priority() == 4 && "t_save_parent".equals(back.parentId()), "type, priority, parent");
            h.assertTrue(back.bounds().equals(r.bounds()), "bounds");
            h.assertTrue(back.isOwner(owner) && back.isMember(member) && !back.isOwner(member) && back.hasRank("gold"), "people and ranks");
            h.assertTrue(back.flag(Flags.PVP) == State.DENY, "state flag");
            h.assertTrue("Welcome".equals(back.flag(Flags.GREETING)), "string flag");
            h.assertTrue(Integer.valueOf(2).equals(back.flag(Flags.HEAL_AMOUNT)), "int flag");
            h.assertTrue(Double.valueOf(4.5).equals(back.flag(Flags.HEAL_MIN_HEALTH)), "double flag");
            h.assertTrue(Boolean.TRUE.equals(back.flag(Flags.EXIT_OVERRIDE)), "boolean flag");
            h.assertTrue(Set.of("minecraft:creeper", "minecraft:zombie").equals(back.flag(Flags.DENY_SPAWN)), "set flag");
            FlagLocation loc = back.flag(Flags.TELEPORT);
            h.assertTrue(loc != null && loc.x() == 1.5 && loc.z() == -3.5 && loc.yaw() == 90 && loc.dimension().equals(h.getLevel().dimension()), "location flag");
            h.assertTrue(back.flag(Flags.GAME_MODE) == net.minecraft.world.level.GameType.ADVENTURE, "game mode flag");
            h.assertTrue(back.typeData().getInt("price") == 1200, "type data");
            h.assertTrue("kept".equals(back.save().getCompound("flags").getString("othermod-flag")), "an unknown flag is kept through save and load");
        });
    }

    @GameTest(template = EMPTY)
    public void removing_a_parent_detaches_its_children(GameTestHelper h) {
        finish(h, () -> {
            Region parent = define(h, "t_rm_parent", new BlockPos(1, 1, 1), new BlockPos(8, 8, 8), 0);
            Region child = define(h, "t_rm_child", new BlockPos(2, 2, 2), new BlockPos(4, 4, 4), 0);
            child.setParentId(parent.id());
            store(h).touch(child);
            h.assertTrue(store(h).remove(parent.id()), "parent removed");
            h.assertTrue(child.parentId() == null, "child no longer points at the missing parent");
            h.assertFalse(store(h).at(h.absolutePos(new BlockPos(6, 6, 6))).contains(parent), "the removed region is out of the index");
        }, "t_rm_child", "t_rm_parent");
    }
}
