package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.level.Explosion;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityMobGriefingEvent;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.event.level.SaplingGrowTreeEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** Things the world and its creatures do: explosions, mob griefing, spawning, lightning, growth, pistons. */
public final class WorldProtection {
    private WorldProtection() {
    }

    // ------------------------------------------------------------------ explosions

    /** The flag that governs an explosion, from what caused it. */
    static StateFlag explosionFlag(@Nullable Entity source) {
        if (source instanceof Creeper) {
            return Flags.CREEPER_EXPLOSION;
        }
        if (source instanceof PrimedTnt || source instanceof MinecartTNT) {
            return Flags.TNT;
        }
        if (source instanceof WitherBoss) {
            return Flags.WITHER_DAMAGE;
        }
        if (source instanceof WitherSkull || source instanceof AbstractHurtingProjectile) {
            return Flags.GHAST_FIREBALL;
        }
        return Flags.OTHER_EXPLOSION;
    }

    /** An explosion that starts inside a region that forbids it does nothing at all. */
    public static void onExplosionStart(ExplosionEvent.Start event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        Explosion explosion = event.getExplosion();
        BlockPos at = BlockPos.containing(explosion.getPosition());
        if (!Protection.mayWorld(level, at, explosionFlag(explosion.getExploder()))) {
            event.setCanceled(true);
        }
    }

    /** An explosion reaching into a region that forbids it leaves that region's blocks and entities alone. */
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        StateFlag flag = explosionFlag(event.getExplosion().getExploder());
        event.getAffectedBlocks().removeIf(pos -> !Protection.mayWorld(level, pos, flag));
        event.getAffectedEntities().removeIf(entity -> !Protection.mayWorld(level, entity.blockPosition(), flag));
    }

    // ------------------------------------------------------------------ mob griefing

    @Nullable
    static StateFlag griefFlag(Entity entity) {
        if (entity instanceof EnderMan) {
            return Flags.ENDERMAN_GRIEF;
        }
        if (entity instanceof SnowGolem) {
            return Flags.SNOWMAN_TRAILS;
        }
        if (entity instanceof Ravager) {
            return Flags.RAVAGER_GRIEF;
        }
        if (entity instanceof EnderDragon) {
            return Flags.ENDERDRAGON_BLOCK_DAMAGE;
        }
        if (entity instanceof WitherBoss) {
            return Flags.WITHER_DAMAGE;
        }
        return null;
    }

    public static void onMobGriefing(EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        ServerLevel level = Protection.server(entity.level());
        if (level == null) {
            return;
        }
        StateFlag flag = griefFlag(entity);
        if (flag != null && !Protection.mayWorld(level, entity.blockPosition(), flag)) {
            event.setResult(Event.Result.DENY);
        }
    }

    public static void onDestroyBlock(LivingDestroyBlockEvent event) {
        ServerLevel level = Protection.server(event.getEntity().level());
        if (level == null) {
            return;
        }
        StateFlag flag = griefFlag(event.getEntity());
        if (flag != null && !Protection.mayWorld(level, event.getPos(), flag)) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ spawning and lightning

    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        BlockPos at = BlockPos.containing(event.getX(), event.getY(), event.getZ());
        if (!Protection.mayWorld(level, at, Flags.MOB_SPAWNING)) {
            event.setSpawnCancelled(true);
            return;
        }
        Set<String> denied = RegionQuery.value(level, at, Flags.DENY_SPAWN);
        if (denied != null && !denied.isEmpty()) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(event.getEntity().getType());
            if (id != null && (denied.contains(id.toString()) || denied.contains(id.getPath()))) {
                event.setSpawnCancelled(true);
            }
        }
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null || !(event.getEntity() instanceof LightningBolt bolt)) {
            return;
        }
        if (!Protection.mayWorld(level, bolt.blockPosition(), Flags.LIGHTNING)) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ growth

    public static void onCropGrow(BlockEvent.CropGrowEvent.Pre event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        if (!Protection.mayWorld(level, event.getPos(), Flags.CROP_GROWTH)) {
            event.setResult(Event.Result.DENY);
        }
    }

    public static void onSaplingGrow(SaplingGrowTreeEvent event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        if (!Protection.mayWorld(level, event.getPos(), Flags.CROP_GROWTH)) {
            event.setResult(Event.Result.DENY);
        }
    }

    // ------------------------------------------------------------------ pistons

    /** A piston may not move if it, any block it moves, or any place a block lands is in a region that forbids pistons. */
    public static void onPiston(PistonEvent.Pre event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        if (!Protection.mayWorld(level, event.getPos(), Flags.PISTONS)) {
            event.setCanceled(true);
            return;
        }
        var helper = event.getStructureHelper();
        if (helper == null) {
            return;
        }
        helper.resolve();
        Direction move = event.getPistonMoveType().isExtend ? event.getDirection() : event.getDirection().getOpposite();
        for (BlockPos pos : helper.getToPush()) {
            if (!Protection.mayWorld(level, pos, Flags.PISTONS) || !Protection.mayWorld(level, pos.relative(move), Flags.PISTONS)) {
                event.setCanceled(true);
                return;
            }
        }
        for (BlockPos pos : helper.getToDestroy()) {
            if (!Protection.mayWorld(level, pos, Flags.PISTONS)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** For the spawn rules: the registry id of an entity type, or {@code null}. */
    @Nullable
    static ResourceLocation idOf(EntityType<?> type) {
        return ForgeRegistries.ENTITY_TYPES.getKey(type);
    }
}
