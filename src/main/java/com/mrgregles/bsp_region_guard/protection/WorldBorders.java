package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.Border;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The world border (owner decisions D36 to D39): a square per world, kept on that world's global region, so the
 * global region stands for the whole world and its edge. Players (not admins with bypass on) are warned near the
 * edge and pushed back at it; a player found outside (after logging in, a teleport or a smaller border) is moved
 * just inside. In the Overworld Minecraft's own border is set to match, so the wall shows.
 *
 * <p>The border travels with the global region through the shared storage, so other servers can read its size
 * and shape (for linking edges between servers later, D40).
 */
public final class WorldBorders {
    public static final String SIZE = "borderSize", CENTER_X = "borderX", CENTER_Z = "borderZ";
    /** Minecraft's own border when nothing limits it. */
    private static final double VANILLA_MAX = 5.9999968E7;
    /** How far inside the edge a player is put when moved in. */
    private static final double MARGIN = 1.5;

    private WorldBorders() {
    }

    // ------------------------------------------------------------------ reading and setting

    /** The world's border, or null when it has none. */
    @Nullable
    public static Border of(RegionStore store) {
        if (!store.hasGlobal()) {
            return null;
        }
        CompoundTag t = store.global().typeData();
        int size = t.getInt(SIZE);
        return size > 0 ? new Border(t.getDouble(CENTER_X), t.getDouble(CENTER_Z), size) : null;
    }

    @Nullable
    public static Border of(ServerLevel level) {
        return of(RegionStore.get(level));
    }

    /** Sets (or with null removes) the border of a world, saves it with the global region and updates the visible wall. */
    public static void set(ServerLevel level, @Nullable Border border) {
        RegionStore store = RegionStore.get(level);
        Region global = store.global();
        CompoundTag t = global.typeData();
        if (border == null) {
            t.remove(SIZE);
            t.remove(CENTER_X);
            t.remove(CENTER_Z);
        } else {
            t.putInt(SIZE, border.size());
            t.putDouble(CENTER_X, border.centerX());
            t.putDouble(CENTER_Z, border.centerZ());
        }
        store.touch(global);
        var mirror = com.mrgregles.bsp_region_guard.storage.RegionMirror.active();
        if (mirror != null) { // other servers re-read it: edge links arrive by this border's size
            mirror.publish(EdgeLinks.KIND_CHANGED, level.dimension().location().toString(), Region.GLOBAL_ID, "");
        }
        if (level.dimension() == Level.OVERWORLD) {
            syncVanilla(level.getServer(), border == null);
        }
    }

    /**
     * Minecraft's own border, when {@code border.vanillaWall} is on: while the Overworld has a Region Guard border,
     * Minecraft's Overworld border matches it and every other world's is set to unlimited (Minecraft copies the
     * Overworld's size into the other worlds, and Region Guard keeps a border per world). {@code cleared} is true right
     * after the Overworld's border was switched off, which lifts the wall again. Otherwise Minecraft's border is left
     * alone, so a server using only {@code /worldborder} keeps it.
     */
    public static void syncVanilla(MinecraftServer server, boolean cleared) {
        if (!RegionGuardConfig.getOr(RegionGuardConfig.BORDER_VANILLA_WALL, true)) {
            return;
        }
        ServerLevel overworld = server.overworld();
        Border b = of(overworld);
        if (b == null && !cleared) {
            return;
        }
        WorldBorder wall = overworld.getWorldBorder();
        wall.setCenter(b == null ? 0 : b.centerX(), b == null ? 0 : b.centerZ());
        wall.setSize(b == null ? VANILLA_MAX : b.size());
        for (ServerLevel level : server.getAllLevels()) {
            if (level != overworld) {
                level.getWorldBorder().setCenter(0, 0);
                level.getWorldBorder().setSize(VANILLA_MAX);
            }
        }
    }

    /** Every world has its global region from the start (D36), and the visible wall matches the saved border. */
    public static void onServerStarted(ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            RegionStore.get(level).global();
        }
        syncVanilla(event.getServer(), false);
    }

    /** For teleport and home checks: true when the spot lies outside this world's border for this player. */
    public static boolean keepsOut(ServerLevel level, BlockPos pos, @Nullable net.minecraft.world.entity.player.Player player) {
        Border b = of(level);
        return b != null && !b.contains(pos.getX() + 0.5, pos.getZ() + 0.5) && !(player != null && RegionQuery.bypasses(player));
    }

    // ------------------------------------------------------------------ enforcement (from MovementTracker)

    /**
     * One tick of the border for one player. Returns true when the player was moved (the caller then skips the rest of
     * the tick). {@code before} is where they stood last tick in this world, or null after a teleport, a login or a
     * world change.
     */
    static boolean tick(ServerPlayer player, MovementTracker.Tracked t, @Nullable Vec3 before) {
        ServerLevel level = player.serverLevel();
        Border b = of(level);
        if (b == null || RegionQuery.bypasses(player)) {
            t.borderWarned = false;
            t.edgeAsked = false;
            return false;
        }
        Vec3 pos = player.position();
        double toEdge = b.toEdge(pos.x, pos.z);
        int warnAt = RegionGuardConfig.getOr(RegionGuardConfig.BORDER_WARN_DISTANCE, 10);
        if (toEdge >= 0) {
            if (toEdge <= warnAt && !t.borderWarned) {
                t.borderWarned = true;
                String m = RegionQuery.value(level, player.blockPosition(), Flags.BORDER_WARNING);
                player.displayClientMessage(Component.literal(m == null ? "The edge of the world is " + Math.max(1, (int) Math.ceil(toEdge)) + " blocks away." : m), true);
            } else if (toEdge > warnAt + 3) {
                t.borderWarned = false; // re-armed once they have moved well away
                t.edgeAsked = false;
            }
            return false;
        }
        boolean linked = EdgeLinks.atEdge(player, t, b, pos); // a linked edge asks whether to travel instead
        Vec3 target = before != null && b.contains(before.x, before.z) ? before : safeInside(level, b, pos);
        move(player, level, target);
        t.borderWarned = true;
        if (!linked) {
            String m = RegionQuery.value(level, player.blockPosition(), Flags.BORDER_MESSAGE);
            player.displayClientMessage(Component.literal(m == null ? "You have reached the edge of the world." : m), true);
        }
        return true;
    }

    private static void move(ServerPlayer player, ServerLevel level, Vec3 to) {
        Entity ridden = player.getRootVehicle();
        if (ridden != player) {
            ridden.teleportTo(to.x, to.y, to.z); // carries the rider along
            ridden.setDeltaMovement(Vec3.ZERO);
        } else {
            player.teleportTo(level, to.x, to.y, to.z, player.getYRot(), player.getXRot());
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
    }

    /** Just inside the border, at a height where the player can stand (the chunk is loaded if needed). */
    static Vec3 safeInside(ServerLevel level, Border b, Vec3 from) {
        Vec3 in = b.clampInside(from, MARGIN);
        return standingSpot(level, in.x, in.z, from.y);
    }

    /**
     * A spot in the column at {@code (x, z)} where a player can stand: solid ground below, two free blocks above, no
     * fluid. Open-sky worlds take the surface; worlds with a ceiling search up and down from {@code preferY}. Loads
     * the chunk if needed.
     */
    public static Vec3 standingSpot(ServerLevel level, double xd, double zd, double preferYd) {
        int x = Mth.floor(xd), z = Mth.floor(zd);
        level.getChunk(x >> 4, z >> 4);
        int prefer = Mth.clamp(Mth.floor(preferYd), level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
        if (!level.dimensionType().hasCeiling()) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (standable(level, x, top, z)) {
                return new Vec3(x + 0.5, top, z + 0.5);
            }
        }
        for (int d = 0; d < level.getHeight(); d++) {
            for (int y : new int[]{prefer - d, prefer + d}) {
                if (y > level.getMinBuildHeight() && y < level.getMaxBuildHeight() - 1 && standable(level, x, y, z)) {
                    return new Vec3(x + 0.5, y, z + 0.5);
                }
            }
        }
        BSPRegionGuard.LOGGER.debug("No standing room at {}, {}; using the surface", x, z);
        return new Vec3(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + 0.5);
    }

    private static boolean standable(Level level, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockState below = level.getBlockState(feet.below());
        return below.isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP) && below.getFluidState().isEmpty()
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet).getFluidState().isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }
}
