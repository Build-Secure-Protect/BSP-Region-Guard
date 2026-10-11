package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import org.jetbrains.annotations.Nullable;

/** Damage, PvP, animals, vehicles, hanging entities, riding, sleeping, pearls, items and experience. */
public final class EntityProtection {
    private EntityProtection() {
    }

    // ------------------------------------------------------------------ damage

    public static void onLivingAttack(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        ServerLevel level = Protection.server(victim.level());
        if (level == null) {
            return;
        }
        DamageSource source = event.getSource();
        BlockPos at = victim.blockPosition();
        Player attacker = Protection.playerBehind(source.getEntity());

        if (victim instanceof Player target) {
            if (RegionQuery.explicit(level, at, Flags.INVINCIBLE).orElse(State.DENY) == State.ALLOW) {
                event.setCanceled(true);
                return;
            }
            if (source.is(DamageTypeTags.IS_FALL) && !Protection.mayWorld(level, at, Flags.FALL_DAMAGE)) {
                event.setCanceled(true);
                return;
            }
            if (attacker != null && attacker != target) {
                // PvP is checked where the victim stands and where the attacker stands: either may forbid it.
                if (!Protection.mayWorld(level, at, Flags.PVP) || !Protection.mayWorld(level, attacker.blockPosition(), Flags.PVP)) {
                    event.setCanceled(true);
                    Protection.tell(attacker, level, at);
                    return;
                }
            }
            if (source.getDirectEntity() instanceof FireworkRocketEntity && !Protection.mayWorld(level, at, Flags.FIREWORK_DAMAGE)) {
                event.setCanceled(true);
                return;
            }
            if (source.getDirectEntity() instanceof AbstractHurtingProjectile && !Protection.mayWorld(level, at, Flags.GHAST_FIREBALL)) {
                event.setCanceled(true);
                return;
            }
            if (attacker == null && source.getEntity() instanceof Mob && !Protection.mayWorld(level, at, Flags.MOB_DAMAGE)) {
                event.setCanceled(true);
            }
            return;
        }

        if (attacker != null && isAnimal(victim) && !Protection.checkPlayer(level, at, Flags.DAMAGE_ANIMALS, attacker)) {
            event.setCanceled(true);
        }
    }

    static boolean isAnimal(Entity entity) {
        return entity instanceof Animal || entity instanceof WaterAnimal || entity instanceof AbstractVillager || entity instanceof Allay
                || entity instanceof net.minecraft.world.entity.ambient.AmbientCreature || entity instanceof net.minecraft.world.entity.animal.AbstractGolem;
    }

    /** Melee on things that are not living: frames, paintings, armour stands, boats, minecarts. */
    public static void onAttackEntity(AttackEntityEvent event) {
        Entity target = event.getTarget();
        ServerLevel level = Protection.server(target.level());
        if (level == null) {
            return;
        }
        Player player = event.getEntity();
        StateFlag flag = nonLivingFlag(target);
        if (flag != null && !Protection.checkPlayer(level, target.blockPosition(), flag, player)) {
            event.setCanceled(true);
        }
    }

    @Nullable
    private static StateFlag nonLivingFlag(Entity target) {
        if (target instanceof Boat || target instanceof AbstractMinecart) {
            return Flags.VEHICLE_DESTROY;
        }
        if (target instanceof HangingEntity || target instanceof ArmorStand) {
            return Flags.BUILD;
        }
        return null;
    }

    // ------------------------------------------------------------------ interacting with entities

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        Entity target = event.getTarget();
        Player player = event.getEntity();
        StateFlag flag;
        if (target instanceof ItemFrame frame && !frame.getItem().isEmpty()) {
            flag = Flags.ITEM_FRAME_ROTATION;
        } else if (target instanceof Container) {
            flag = Flags.CHEST_ACCESS; // chest boats, chest and hopper minecarts
        } else if (target instanceof Boat || target instanceof AbstractMinecart) {
            flag = Flags.RIDE;
        } else {
            flag = Flags.INTERACT;
        }
        if (!Protection.checkPlayer(level, target.blockPosition(), flag, player)) {
            event.setCanceled(true);
        }
    }

    public static void onMount(EntityMountEvent event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null || !event.isMounting() || !(event.getEntityMounting() instanceof Player player)) {
            return;
        }
        if (!Protection.checkPlayer(level, event.getEntityBeingMounted().blockPosition(), Flags.RIDE, player)) {
            event.setCanceled(true);
        }
    }

    public static void onSleep(PlayerSleepInBedEvent event) {
        ServerLevel level = Protection.server(event.getEntity().level());
        if (level == null || event.getPos() == null) {
            return;
        }
        if (!Protection.checkPlayer(level, event.getPos(), Flags.SLEEP, event.getEntity())) {
            event.setResult(Player.BedSleepingProblem.NOT_POSSIBLE_HERE);
        }
    }

    // ------------------------------------------------------------------ projectiles

    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        ServerLevel level = Protection.server(projectile.level());
        if (level == null) {
            return;
        }
        BlockPos at = BlockPos.containing(event.getRayTraceResult().getLocation());
        if (projectile instanceof ThrownPotion && !Protection.mayWorld(level, at, Flags.POTION_SPLASH)) {
            event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
            projectile.discard();
            return;
        }
        if (projectile instanceof AbstractHurtingProjectile && !Protection.mayWorld(level, at, Flags.GHAST_FIREBALL)) {
            event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
            projectile.discard();
            return;
        }
        if (event.getRayTraceResult() instanceof EntityHitResult hit && hit.getEntity() instanceof HangingEntity hanging) {
            Player owner = Protection.playerBehind(projectile);
            boolean ok;
            if (owner != null) {
                ok = Protection.checkPlayer(level, hanging.blockPosition(), Flags.BUILD, owner);
            } else {
                ok = Protection.mayWorld(level, hanging.blockPosition(),
                        hanging instanceof Painting ? Flags.ENTITY_PAINTING_DESTROY : Flags.ENTITY_ITEM_FRAME_DESTROY);
            }
            if (!ok) {
                event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
                projectile.discard();
            }
        }
    }

    // ------------------------------------------------------------------ teleports

    public static void onEnderPearl(EntityTeleportEvent.EnderPearl event) {
        teleport(event, event.getPlayer(), Flags.ENDERPEARL);
    }

    public static void onChorusFruit(EntityTeleportEvent.ChorusFruit event) {
        teleport(event, event.getEntity() instanceof Player p ? p : null, Flags.CHORUS_FRUIT_TELEPORT);
    }

    private static void teleport(EntityTeleportEvent event, @Nullable Player player, StateFlag flag) {
        ServerLevel level = Protection.server(event.getEntity().level());
        if (level == null) {
            return;
        }
        BlockPos from = event.getEntity().blockPosition();
        BlockPos to = BlockPos.containing(event.getTargetX(), event.getTargetY(), event.getTargetZ());
        boolean ok = player != null
                ? Protection.mayPlayer(level, from, flag, player) && Protection.mayPlayer(level, to, flag, player)
                : Protection.mayWorld(level, from, flag) && Protection.mayWorld(level, to, flag);
        if (!ok) {
            event.setCanceled(true);
            if (player != null) {
                Protection.tell(player, level, to);
            }
        }
    }

    // ------------------------------------------------------------------ items and experience

    public static void onItemToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        ServerLevel level = Protection.server(player.level());
        if (level == null) {
            return;
        }
        if (!Protection.checkPlayer(level, player.blockPosition(), Flags.ITEM_DROP, player)) {
            event.setCanceled(true);
            // Forge removes the stack from the inventory before the event; give it back.
            if (!player.getInventory().add(event.getEntity().getItem())) {
                player.drop(event.getEntity().getItem(), false, false);
            }
        }
    }

    public static void onItemPickup(EntityItemPickupEvent event) {
        Player player = event.getEntity();
        ServerLevel level = Protection.server(player.level());
        if (level == null) {
            return;
        }
        if (!Protection.mayPlayer(level, event.getItem().blockPosition(), Flags.ITEM_PICKUP, player)) {
            event.setCanceled(true);
        }
    }

    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        ServerLevel level = Protection.server(event.getEntity().level());
        if (level == null) {
            return;
        }
        if (!Protection.mayWorld(level, event.getEntity().blockPosition(), Flags.EXP_DROPS)) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ natural regeneration (with FoodDataMixin)

    private static final ThreadLocal<Player> FOOD_TICK = new ThreadLocal<>();

    public static void beginFoodTick(Player player) {
        FOOD_TICK.set(player);
    }

    public static void endFoodTick() {
        FOOD_TICK.remove();
    }

    /** A heal during the player's own food tick is natural regeneration; {@code natural-health-regen} may stop it. */
    public static void onLivingHeal(net.minecraftforge.event.entity.living.LivingHealEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || FOOD_TICK.get() != player) {
            return;
        }
        if (!com.mrgregles.bsp_region_guard.protection.WorldFlags.allows(player.serverLevel(), player.blockPosition(), Flags.NATURAL_HEALTH_REGEN,
                com.mrgregles.bsp_region_guard.RegionGuardConfig.MIXIN_HUNGER)) {
            event.setCanceled(true);
        }
    }

    /** Whether a damage source counts as "the world" rather than a creature (used by the explosion rules). */
    static boolean isExplosion(DamageSource source) {
        return source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION);
    }
}
