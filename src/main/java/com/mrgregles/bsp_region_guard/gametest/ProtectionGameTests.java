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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Set;

/**
 * GameTests for the event-driven protections. Most tests post the Forge event themselves (the way the game would)
 * and check whether the handlers refused it; the spawn test spawns a real mob. Ids are unique per test.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class ProtectionGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    private static Region define(GameTestHelper h, String id) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(9, 8, 9))), RegionType.PROTECTED);
        store(h).add(r);
        return r;
    }

    private static void finish(GameTestHelper h, Runnable body, String... ids) {
        try {
            body.run();
        } finally {
            for (String id : ids) {
                store(h).remove(id);
            }
        }
        h.succeed();
    }

    private static BlockPos inside(GameTestHelper h) {
        return h.absolutePos(new BlockPos(5, 3, 5));
    }

    private static BlockPos outside(GameTestHelper h) {
        return h.absolutePos(new BlockPos(15, 3, 15));
    }

    @GameTest(template = EMPTY)
    public void stranger_cannot_break_but_member_can(GameTestHelper h) {
        Player member = h.makeMockPlayer();
        Player stranger = h.makeMockPlayer();
        finish(h, () -> {
            Region r = define(h, "p_break");
            r.addMember(member.getUUID());
            store(h).touch(r);
            h.setBlock(new BlockPos(5, 3, 5), Blocks.STONE);
            ServerLevel level = h.getLevel();
            BlockEvent.BreakEvent byStranger = new BlockEvent.BreakEvent(level, inside(h), level.getBlockState(inside(h)), stranger);
            MinecraftForge.EVENT_BUS.post(byStranger);
            h.assertTrue(byStranger.isCanceled(), "a stranger's break is refused");
            BlockEvent.BreakEvent byMember = new BlockEvent.BreakEvent(level, inside(h), level.getBlockState(inside(h)), member);
            MinecraftForge.EVENT_BUS.post(byMember);
            h.assertFalse(byMember.isCanceled(), "a member's break goes ahead");
            BlockEvent.BreakEvent farAway = new BlockEvent.BreakEvent(level, outside(h), level.getBlockState(outside(h)), stranger);
            MinecraftForge.EVENT_BUS.post(farAway);
            h.assertFalse(farAway.isCanceled(), "outside every region anyone may break");
        }, "p_break");
    }

    @GameTest(template = EMPTY)
    public void explosion_leaves_a_protected_region_alone(GameTestHelper h) {
        finish(h, () -> {
            Region r = define(h, "p_boom");
            r.setFlag(Flags.OTHER_EXPLOSION, State.DENY);
            store(h).touch(r);
            ServerLevel level = h.getLevel();
            BlockPos in = inside(h), out = outside(h);
            Explosion explosion = new Explosion(level, null, out.getX(), out.getY(), out.getZ(), 4f, false, Explosion.BlockInteraction.DESTROY);
            explosion.getToBlow().add(in);
            explosion.getToBlow().add(out);
            ExplosionEvent.Detonate detonate = new ExplosionEvent.Detonate(level, explosion, new ArrayList<>());
            MinecraftForge.EVENT_BUS.post(detonate);
            h.assertTrue(!detonate.getAffectedBlocks().contains(in) && detonate.getAffectedBlocks().contains(out),
                    "the block inside the region is spared, the one outside is not");

            Explosion insideStart = new Explosion(level, null, in.getX(), in.getY(), in.getZ(), 4f, false, Explosion.BlockInteraction.DESTROY);
            ExplosionEvent.Start start = new ExplosionEvent.Start(level, insideStart);
            MinecraftForge.EVENT_BUS.post(start);
            h.assertTrue(start.isCanceled(), "an explosion starting inside is cancelled outright");
            r.setFlag(Flags.OTHER_EXPLOSION, null);
            r.setFlag(Flags.TNT, State.DENY);
            store(h).touch(r);
            ExplosionEvent.Start other = new ExplosionEvent.Start(level, insideStart);
            MinecraftForge.EVENT_BUS.post(other);
            h.assertFalse(other.isCanceled(), "a tnt deny does not stop an explosion with no source");
        }, "p_boom");
    }

    /** A cancelled spawn is never added to the world (Forge drops it on join), so the world is what we check. */
    private static boolean present(ServerLevel level, EntityType<?> type, BlockPos at) {
        return !level.getEntities(type, new net.minecraft.world.phys.AABB(at).inflate(2), e -> true).isEmpty();
    }

    private static void clear(ServerLevel level, EntityType<?> type, BlockPos at) {
        level.getEntities(type, new net.minecraft.world.phys.AABB(at).inflate(2), e -> true).forEach(net.minecraft.world.entity.Entity::discard);
    }

    @GameTest(template = EMPTY)
    public void mob_spawning_is_refused_where_denied(GameTestHelper h) {
        finish(h, () -> {
            Region r = define(h, "p_spawn");
            r.setFlag(Flags.MOB_SPAWNING, State.DENY);
            store(h).touch(r);
            ServerLevel level = h.getLevel();
            EntityType.ZOMBIE.spawn(level, inside(h), MobSpawnType.SPAWN_EGG);
            h.assertFalse(present(level, EntityType.ZOMBIE, inside(h)), "no zombie inside the region");
            EntityType.ZOMBIE.spawn(level, outside(h), MobSpawnType.SPAWN_EGG);
            h.assertTrue(present(level, EntityType.ZOMBIE, outside(h)), "a zombie spawns outside");
            clear(level, EntityType.ZOMBIE, outside(h));
            r.setFlag(Flags.MOB_SPAWNING, null);
            r.setFlag(Flags.DENY_SPAWN, Set.of("minecraft:zombie"));
            store(h).touch(r);
            EntityType.ZOMBIE.spawn(level, inside(h), MobSpawnType.SPAWN_EGG);
            h.assertFalse(present(level, EntityType.ZOMBIE, inside(h)), "deny-spawn keeps zombies out");
            EntityType.COW.spawn(level, inside(h), MobSpawnType.SPAWN_EGG);
            h.assertTrue(present(level, EntityType.COW, inside(h)), "but lets cows in");
            clear(level, EntityType.COW, inside(h));
        }, "p_spawn");
    }

    @GameTest(template = EMPTY)
    public void pvp_is_refused_where_either_player_stands(GameTestHelper h) {
        Player attacker = h.makeMockPlayer();
        Player victim = h.makeMockPlayer();
        finish(h, () -> {
            Region r = define(h, "p_pvp");
            r.setFlag(Flags.PVP, State.DENY);
            store(h).touch(r);
            ServerLevel level = h.getLevel();
            BlockPos in = inside(h), out = outside(h);
            victim.setPos(in.getX(), in.getY(), in.getZ());
            attacker.setPos(out.getX(), out.getY(), out.getZ());
            LivingAttackEvent hit = new LivingAttackEvent(victim, level.damageSources().playerAttack(attacker), 1f);
            MinecraftForge.EVENT_BUS.post(hit);
            h.assertTrue(hit.isCanceled(), "victim inside: refused");
            victim.setPos(out.getX(), out.getY(), out.getZ());
            attacker.setPos(in.getX(), in.getY(), in.getZ());
            LivingAttackEvent hit2 = new LivingAttackEvent(victim, level.damageSources().playerAttack(attacker), 1f);
            MinecraftForge.EVENT_BUS.post(hit2);
            h.assertTrue(hit2.isCanceled(), "attacker inside: refused");
            attacker.setPos(out.getX() + 1, out.getY(), out.getZ());
            LivingAttackEvent hit3 = new LivingAttackEvent(victim, level.damageSources().playerAttack(attacker), 1f);
            MinecraftForge.EVENT_BUS.post(hit3);
            h.assertFalse(hit3.isCanceled(), "both outside: allowed");
            LivingAttackEvent mob = new LivingAttackEvent(victim, level.damageSources().generic(), 1f);
            MinecraftForge.EVENT_BUS.post(mob);
            h.assertFalse(mob.isCanceled(), "other damage is not pvp");
        }, "p_pvp");
    }

    @GameTest(template = EMPTY)
    public void item_drop_and_pickup_follow_their_flags(GameTestHelper h) {
        Player player = h.makeMockPlayer();
        finish(h, () -> {
            Region r = define(h, "p_items");
            r.setFlag(Flags.ITEM_DROP, State.DENY);
            r.setFlag(Flags.ITEM_PICKUP, State.DENY);
            store(h).touch(r);
            ServerLevel level = h.getLevel();
            BlockPos in = inside(h);
            player.setPos(in.getX(), in.getY(), in.getZ());
            ItemEntity item = new ItemEntity(level, in.getX(), in.getY(), in.getZ(), new ItemStack(Items.APPLE, 3));
            ItemTossEvent toss = new ItemTossEvent(item, player);
            MinecraftForge.EVENT_BUS.post(toss);
            h.assertTrue(toss.isCanceled(), "dropping is refused");
            h.assertTrue(player.getInventory().countItem(Items.APPLE) == 3, "the stack went back into the inventory");
            EntityItemPickupEvent pickup = new EntityItemPickupEvent(player, item);
            MinecraftForge.EVENT_BUS.post(pickup);
            h.assertTrue(pickup.isCanceled(), "picking up is refused");
        }, "p_items");
    }

    @GameTest(template = EMPTY)
    public void pistons_are_stopped_where_denied(GameTestHelper h) {
        finish(h, () -> {
            Region r = define(h, "p_piston");
            r.setFlag(Flags.PISTONS, State.DENY);
            store(h).touch(r);
            ServerLevel level = h.getLevel();
            BlockPos in = inside(h);
            PistonEvent.Pre insideEvent = new PistonEvent.Pre(level, in, Direction.UP, PistonEvent.PistonMoveType.EXTEND);
            MinecraftForge.EVENT_BUS.post(insideEvent);
            h.assertTrue(insideEvent.isCanceled(), "a piston inside is stopped");
            // A piston just outside pushing a block into the region.
            BlockPos edge = h.absolutePos(new BlockPos(1, 3, 5));
            h.setBlock(new BlockPos(1, 3, 5), Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.EAST));
            h.setBlock(new BlockPos(2, 3, 5), Blocks.STONE);
            PistonEvent.Pre pushIn = new PistonEvent.Pre(level, edge, Direction.EAST, PistonEvent.PistonMoveType.EXTEND);
            MinecraftForge.EVENT_BUS.post(pushIn);
            h.assertTrue(pushIn.isCanceled(), "pushing a block that sits in the region is stopped");
            PistonStructureResolver unused = pushIn.getStructureHelper();
            h.assertTrue(unused != null, "the event offered a structure helper");
        }, "p_piston");
    }
}
