package com.mrgregles.bsp_region_guard.event;

import com.mrgregles.bsp_region_guard.item.RegionWandItem;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.registry.ModItems;
import com.mrgregles.bsp_region_guard.selection.Selection;
import com.mrgregles.bsp_region_guard.selection.Selections;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.joml.Vector3f;

/** Left-click with the wand sets the first corner; a held wand shows the selection as a particle outline. */
public final class WandHandler {
    private static final DustParticleOptions OUTLINE = new DustParticleOptions(new Vector3f(0.10f, 0.83f, 0.69f), 1.0f);
    private static final DustParticleOptions CORNER = new DustParticleOptions(new Vector3f(1.0f, 0.82f, 0.23f), 1.5f);
    /** Particles per edge are capped so a huge selection does not flood the client. */
    private static final int MAX_POINTS_PER_EDGE = 48;

    private WandHandler() {
    }

    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getItemStack().is(ModItems.REGION_WAND.get())) {
            return;
        }
        event.setCanceled(true); // never break the block
        if (event.getEntity() instanceof ServerPlayer player) {
            Selection sel = Selections.of(player);
            sel.setPos1(player.serverLevel().dimension(), event.getPos());
            RegionWandItem.tell(player, sel, "First corner", event.getPos());
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Selections.forget(event.getEntity().getUUID());
        com.mrgregles.bsp_region_guard.protection.Protection.forget(event.getEntity().getUUID());
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 10 != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.getMainHandItem().is(ModItems.REGION_WAND.get()) && !player.getOffhandItem().is(ModItems.REGION_WAND.get())) {
                continue;
            }
            Selection sel = Selections.of(player);
            if (!player.serverLevel().dimension().equals(sel.dimension())) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            if (sel.pos1() != null) {
                level.sendParticles(player, CORNER, true, sel.pos1().getX() + 0.5, sel.pos1().getY() + 0.5, sel.pos1().getZ() + 0.5, 1, 0, 0, 0, 0);
            }
            if (sel.pos2() != null) {
                level.sendParticles(player, CORNER, true, sel.pos2().getX() + 0.5, sel.pos2().getY() + 0.5, sel.pos2().getZ() + 0.5, 1, 0, 0, 0, 0);
            }
            Cuboid box = sel.cuboid();
            if (box != null) {
                drawBox(level, player, box, OUTLINE);
            }
        }
    }

    /** Draws the twelve edges of a box (block-aligned, so the outline sits on the outside faces). */
    public static void drawBox(ServerLevel level, ServerPlayer player, Cuboid box, DustParticleOptions colour) {
        double x0 = box.minX(), y0 = box.minY(), z0 = box.minZ();
        double x1 = box.maxX() + 1, y1 = box.maxY() + 1, z1 = box.maxZ() + 1;
        for (double y : new double[]{y0, y1}) {
            for (double z : new double[]{z0, z1}) {
                edge(level, player, colour, x0, y, z, x1, y, z);
            }
            for (double x : new double[]{x0, x1}) {
                edge(level, player, colour, x, y, z0, x, y, z1);
            }
        }
        for (double x : new double[]{x0, x1}) {
            for (double z : new double[]{z0, z1}) {
                edge(level, player, colour, x, y0, z, x, y1, z);
            }
        }
    }

    private static void edge(ServerLevel level, ServerPlayer player, DustParticleOptions colour,
                             double ax, double ay, double az, double bx, double by, double bz) {
        double length = Math.abs(bx - ax) + Math.abs(by - ay) + Math.abs(bz - az);
        int points = (int) Math.min(MAX_POINTS_PER_EDGE, Math.max(2, Math.ceil(length)));
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            level.sendParticles(player, colour, true, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, 1, 0, 0, 0, 0);
        }
    }
}
