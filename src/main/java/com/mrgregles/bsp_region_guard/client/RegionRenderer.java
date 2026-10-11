package com.mrgregles.bsp_region_guard.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import com.mrgregles.bsp_region_guard.network.SelectionPacket;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Draws regions in the world: wire-edged boxes with faint faces in the type's colour and the id floating above the
 * centre, the way BSP Core draws its aura cubes. Also the wand selection, in gold. Only what the server sent is
 * drawn (admins get everything, players their own claims and plots for sale), and only while the toggle is on.
 */
public final class RegionRenderer {
    /** Regions whose nearest point is further than this from the camera are skipped. */
    private static final double MAX_DISTANCE = 256;
    private static final int TQ = 0x19D3B0, VIO = 0xB58CFF, GOLD = 0xFFD23A, MUTED = 0x9AA3B5;

    private RegionRenderer() {
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !RegionClient.showRegions()) {
            return;
        }
        RegionListPacket list = RegionClient.list();
        SelectionPacket sel = RegionClient.selection();
        boolean anyRegion = list != null && list.dimension().equals(mc.level.dimension().location());
        boolean anySelection = sel != null && sel.dimension() != null && sel.dimension().equals(mc.level.dimension().location()) && (sel.pos1() != null || sel.pos2() != null);
        if (!anyRegion && !anySelection) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        if (anyRegion) {
            for (RegionListPacket.Summary s : list.regions()) {
                Cuboid b = s.bounds();
                if (b == null) {
                    continue;
                }
                AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
                if (distanceTo(box, cam) > MAX_DISTANCE) {
                    continue;
                }
                int colour = colourOf(s.type());
                boolean near = box.inflate(0.1).contains(cam);
                drawBox(pose, buffers, box, colour, near ? 0.10f : 0.18f);
                label(pose, buffers, camera, mc.font, s.id(), new Vec3(box.getCenter().x, box.maxY + 0.6, box.getCenter().z), colour, cam);
            }
        }
        if (anySelection) {
            if (sel.pos1() != null && sel.pos2() != null) {
                Cuboid c = Cuboid.of(sel.pos1(), sel.pos2());
                drawBox(pose, buffers, new AABB(c.minX(), c.minY(), c.minZ(), c.maxX() + 1, c.maxY() + 1, c.maxZ() + 1), GOLD, 0.12f);
            }
            corner(pose, buffers, sel.pos1());
            corner(pose, buffers, sel.pos2());
        }
        buffers.endBatch(RenderType.debugFilledBox());
        buffers.endBatch(RenderType.lines());
        buffers.endBatch();
        pose.popPose();
    }

    static int colourOf(String type) {
        return switch (type) {
            case "claim" -> VIO;
            case "mine", "plot_for_sale", "open_for_buying" -> GOLD;
            case "global" -> MUTED;
            default -> TQ;
        };
    }

    private static double distanceTo(AABB box, Vec3 p) {
        double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void drawBox(PoseStack pose, MultiBufferSource buffers, AABB box, int rgb, float faceAlpha) {
        float r = (rgb >> 16 & 0xFF) / 255f, g = (rgb >> 8 & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        VertexConsumer faces = buffers.getBuffer(RenderType.debugFilledBox());
        LevelRenderer.addChainedFilledBoxVertices(pose, faces, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, r, g, b, faceAlpha);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 1f);
    }

    private static void corner(PoseStack pose, MultiBufferSource buffers, @Nullable BlockPos p) {
        if (p == null) {
            return;
        }
        AABB box = new AABB(p).inflate(0.02);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, box, 1f, 0.82f, 0.23f, 1f);
    }

    /** A name tag in the world, facing the camera, the way entity names are drawn. */
    private static void label(PoseStack pose, MultiBufferSource buffers, Camera camera, Font font, String text, Vec3 at, int rgb, Vec3 cam) {
        if (at.distanceTo(cam) > 96) {
            return;
        }
        pose.pushPose();
        pose.translate(at.x, at.y, at.z);
        pose.mulPose(camera.rotation());
        float scale = 0.025f * (float) Math.max(1, at.distanceTo(cam) / 12);
        pose.scale(-scale, -scale, scale);
        Matrix4f matrix = pose.last().pose();
        float x = -font.width(text) / 2f;
        font.drawInBatch(text, x, 0, 0xFF000000 | rgb, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH, 0x60000000, 0xF000F0);
        pose.popPose();
    }
}
