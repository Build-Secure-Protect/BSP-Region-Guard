package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for the mixin-enforced flags. Each test puts the same situation inside a denying region and outside it
 * and expects the world to change only outside. Random-tick behaviour is driven by calling the block's tick directly,
 * many times where the vanilla code rolls dice.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class MixinGameTests {
    private static final String EMPTY = "empty";
    /** The region covers x 2..9, z 2..9 (template-relative); "outside" positions use x 14..20. */
    private static final BlockPos IN = new BlockPos(5, 2, 5);
    private static final BlockPos OUT = new BlockPos(17, 2, 5);

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    private static Region define(GameTestHelper h, String id) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(9, 8, 9))), RegionType.PROTECTED);
        store(h).add(r);
        return r;
    }

    private static void cleanup(GameTestHelper h, String id) {
        store(h).remove(id);
    }

    private static BlockState at(GameTestHelper h, BlockPos rel) {
        return h.getLevel().getBlockState(h.absolutePos(rel));
    }

    @GameTest(template = EMPTY)
    public void fire_does_not_spread_or_burn_inside(GameTestHelper h) {
        Region r = define(h, "x_fire");
        r.setFlag(Flags.FIRE_SPREAD, State.DENY);
        r.setFlag(Flags.FIRE_BURN, State.DENY);
        store(h).touch(r);
        ServerLevel level = h.getLevel();
        // A fire with planks on both sides, inside and outside.
        for (BlockPos base : new BlockPos[]{IN, OUT}) {
            h.setBlock(base.below(), Blocks.STONE);
            h.setBlock(base.east(), Blocks.OAK_PLANKS);
            h.setBlock(base.west(), Blocks.OAK_PLANKS);
            h.setBlock(base.east().below(), Blocks.OAK_PLANKS);
            h.setBlock(base.west().below(), Blocks.OAK_PLANKS);
        }
        try {
            for (int i = 0; i < 400; i++) {
                for (BlockPos base : new BlockPos[]{IN, OUT}) {
                    BlockPos fire = h.absolutePos(base);
                    if (!level.getBlockState(fire).is(Blocks.FIRE)) {
                        level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 3);
                    }
                    Blocks.FIRE.tick(level.getBlockState(fire), level, fire, level.random);
                }
            }
            boolean insidePlanksIntact = at(h, IN.east()).is(Blocks.OAK_PLANKS) && at(h, IN.west()).is(Blocks.OAK_PLANKS)
                    && at(h, IN.east().below()).is(Blocks.OAK_PLANKS) && at(h, IN.west().below()).is(Blocks.OAK_PLANKS);
            h.assertTrue(insidePlanksIntact, "planks inside the region never burn");
            boolean outsideChanged = !at(h, OUT.east()).is(Blocks.OAK_PLANKS) || !at(h, OUT.west()).is(Blocks.OAK_PLANKS)
                    || !at(h, OUT.east().below()).is(Blocks.OAK_PLANKS) || !at(h, OUT.west().below()).is(Blocks.OAK_PLANKS);
            h.assertTrue(outsideChanged, "the same fire outside burns or spreads within 400 ticks");
        } finally {
            cleanup(h, "x_fire");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void water_does_not_flow_into_a_denying_region(GameTestHelper h) {
        Region r = define(h, "x_water");
        r.setFlag(Flags.WATER_FLOW, State.DENY);
        store(h).touch(r);
        // A flat stone floor (no holes to pull the water sideways) and a source at x=11: it should flow west only as far as x=10.
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 11; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        h.setBlock(new BlockPos(11, 2, 5), Blocks.WATER);
        ServerLevel level = h.getLevel();
        h.runAfterDelay(10, () -> {
            try {
                // Scheduled fluid ticks may not run in the test world, so tick every water block by hand, as the server would.
                for (int round = 0; round < 12; round++) {
                    for (int x = 7; x <= 14; x++) {
                        BlockPos p = h.absolutePos(new BlockPos(x, 2, 5));
                        var fs = level.getFluidState(p);
                        if (!fs.isEmpty()) {
                            fs.tick(level, p);
                        }
                    }
                }
                StringBuilder row = new StringBuilder();
                for (int x = 7; x <= 14; x++) {
                    var fs = at(h, new BlockPos(x, 2, 5)).getFluidState();
                    row.append(x).append('=').append(fs.isEmpty() ? "air" : fs.isSource() ? "src" : "flow" + fs.getAmount()).append(' ');
                }
                h.assertTrue(!at(h, new BlockPos(13, 2, 5)).getFluidState().isEmpty(), "water flowed east, outside the region: " + row);
                h.assertTrue(!at(h, new BlockPos(10, 2, 5)).getFluidState().isEmpty(), "water flowed west up to the border (x=10 is outside)");
                h.assertTrue(at(h, new BlockPos(9, 2, 5)).getFluidState().isEmpty(), "no water on the first block inside the region (x=9)");
                h.assertTrue(at(h, new BlockPos(8, 2, 5)).getFluidState().isEmpty(), "and none further in");
            } finally {
                cleanup(h, "x_water");
            }
            h.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void falling_sand_does_not_land_inside(GameTestHelper h) {
        Region r = define(h, "x_sand");
        r.setFlag(Flags.FALLING_BLOCKS, State.DENY);
        store(h).touch(r);
        ServerLevel level = h.getLevel();
        h.setBlock(IN.below(), Blocks.STONE);
        h.setBlock(OUT.below(), Blocks.STONE);
        FallingBlockEntity.fall(level, h.absolutePos(IN.above(3)), Blocks.SAND.defaultBlockState());
        FallingBlockEntity.fall(level, h.absolutePos(OUT.above(3)), Blocks.SAND.defaultBlockState());
        h.runAfterDelay(60, () -> {
            try {
                h.assertTrue(at(h, OUT).is(Blocks.SAND), "sand landed outside");
                h.assertFalse(at(h, IN).is(Blocks.SAND), "no sand block inside");
                boolean item = !level.getEntities(EntityType.ITEM, new net.minecraft.world.phys.AABB(h.absolutePos(IN)).inflate(2), e -> true).isEmpty();
                h.assertTrue(item, "the sand inside dropped as an item instead");
            } finally {
                cleanup(h, "x_sand");
            }
            h.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void dispenser_does_not_fire_into_a_denying_region(GameTestHelper h) {
        Region r = define(h, "x_disp");
        r.setFlag(Flags.DISPENSER_USE, State.DENY);
        store(h).touch(r);
        ServerLevel level = h.getLevel();
        // One dispenser just outside the west border facing in (x=1 -> fires at x=2, inside), one far outside facing east.
        BlockPos in = new BlockPos(1, 2, 5), out = new BlockPos(15, 2, 5);
        for (BlockPos p : new BlockPos[]{in, out}) {
            h.setBlock(p, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
            if (level.getBlockEntity(h.absolutePos(p)) instanceof DispenserBlockEntity be) {
                be.setItem(0, new ItemStack(Items.ARROW, 1));
            }
            level.scheduleTick(h.absolutePos(p), Blocks.DISPENSER, 1);
        }
        h.runAfterDelay(20, () -> {
            try {
                var outBe = (DispenserBlockEntity) level.getBlockEntity(h.absolutePos(out));
                h.assertTrue(outBe != null && outBe.getItem(0).isEmpty(), "the dispenser outside fired its arrow");
                var inBe = (DispenserBlockEntity) level.getBlockEntity(h.absolutePos(in));
                h.assertTrue(inBe != null && inBe.getItem(0).getCount() == 1, "the dispenser facing into the region still holds its arrow");
            } finally {
                cleanup(h, "x_disp");
            }
            h.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void grass_does_not_spread_and_leaves_do_not_decay_inside(GameTestHelper h) {
        Region r = define(h, "x_grow");
        r.setFlag(Flags.GRASS_GROWTH, State.DENY);
        r.setFlag(Flags.LEAF_DECAY, State.DENY);
        store(h).touch(r);
        ServerLevel level = h.getLevel();
        for (BlockPos base : new BlockPos[]{IN, OUT}) {
            h.setBlock(base.above().offset(1, 0, 1), Blocks.GLOWSTONE); // grass only spreads in light
        }
        h.runAfterDelay(30, () -> {
        try {
            for (BlockPos base : new BlockPos[]{IN, OUT}) {
                h.setBlock(base, Blocks.GRASS_BLOCK);
                h.setBlock(base.east(), Blocks.DIRT);
                h.setBlock(base.west(), Blocks.DIRT);
                h.setBlock(base.north(), Blocks.DIRT);
                h.setBlock(base.south(), Blocks.DIRT);
                h.setBlock(base.above(2), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 7).setValue(LeavesBlock.PERSISTENT, false));
            }
            for (int i = 0; i < 800; i++) {
                for (BlockPos base : new BlockPos[]{IN, OUT}) {
                    BlockPos p = h.absolutePos(base);
                    if (level.getBlockState(p).is(Blocks.GRASS_BLOCK)) {
                        Blocks.GRASS_BLOCK.randomTick(level.getBlockState(p), level, p, level.random);
                    }
                    BlockPos leaves = h.absolutePos(base.above(2));
                    if (level.getBlockState(leaves).is(Blocks.OAK_LEAVES)) {
                        Blocks.OAK_LEAVES.randomTick(level.getBlockState(leaves), level, leaves, level.random);
                    }
                }
            }
            boolean insideDirt = at(h, IN.east()).is(Blocks.DIRT) && at(h, IN.west()).is(Blocks.DIRT) && at(h, IN.north()).is(Blocks.DIRT) && at(h, IN.south()).is(Blocks.DIRT);
            h.assertTrue(insideDirt, "dirt inside stays dirt");
            boolean outsideGrass = at(h, OUT.east()).is(Blocks.GRASS_BLOCK) || at(h, OUT.west()).is(Blocks.GRASS_BLOCK) || at(h, OUT.north()).is(Blocks.GRASS_BLOCK) || at(h, OUT.south()).is(Blocks.GRASS_BLOCK);
            int light = level.getMaxLocalRawBrightness(h.absolutePos(OUT).above());
            h.assertTrue(outsideGrass, "grass spreads outside within 800 ticks (light above the grass: " + light + ", grass still there: " + at(h, OUT).is(Blocks.GRASS_BLOCK) + ")");
            h.assertTrue(at(h, IN.above(2)).is(Blocks.OAK_LEAVES), "leaves inside do not decay");
            h.assertFalse(at(h, OUT.above(2)).is(Blocks.OAK_LEAVES), "leaves outside decay");
        } finally {
            cleanup(h, "x_grow");
        }
        h.succeed();
        });
    }

    @GameTest(template = EMPTY)
    public void coral_does_not_fade_inside(GameTestHelper h) {
        Region r = define(h, "x_coral");
        r.setFlag(Flags.CORAL_FADE, State.DENY);
        store(h).touch(r);
        ServerLevel level = h.getLevel();
        try {
            h.setBlock(IN, Blocks.BRAIN_CORAL_BLOCK);
            h.setBlock(OUT, Blocks.BRAIN_CORAL_BLOCK);
            for (BlockPos base : new BlockPos[]{IN, OUT}) {
                BlockPos p = h.absolutePos(base);
                Blocks.BRAIN_CORAL_BLOCK.tick(level.getBlockState(p), level, p, level.random);
            }
            h.assertTrue(at(h, IN).is(Blocks.BRAIN_CORAL_BLOCK), "coral inside stays alive out of water");
            h.assertTrue(at(h, OUT).is(Blocks.DEAD_BRAIN_CORAL_BLOCK), "coral outside dies");
        } finally {
            cleanup(h, "x_coral");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void hunger_does_not_drain_inside(GameTestHelper h) {
        Region r = define(h, "x_hunger");
        r.setFlag(Flags.NATURAL_HUNGER_DRAIN, State.DENY);
        store(h).touch(r);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        try {
            p.setGameMode(GameType.SURVIVAL);
            BlockPos in = h.absolutePos(IN);
            p.setPos(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
            p.getFoodData().setExhaustion(0);
            p.causeFoodExhaustion(2.5f);
            h.assertTrue(p.getFoodData().getExhaustionLevel() == 0f, "no exhaustion added inside");
            BlockPos out = h.absolutePos(OUT);
            p.setPos(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
            p.causeFoodExhaustion(2.5f);
            h.assertTrue(p.getFoodData().getExhaustionLevel() == 2.5f, "exhaustion added outside");
        } finally {
            cleanup(h, "x_hunger");
            p.discard();
        }
        h.succeed();
    }
}
