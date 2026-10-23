package com.mrgregles.bsp_region_guard.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import com.mrgregles.bsp_region_guard.network.SelectionPacket;
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
 *
 * <p>Plots for sale and open areas are the exception (D105): every player sees them in their own colour with their
 * marker ({@link PlotMarkers}), toggle or not, until they are bought.
 */
public final class RegionRenderer {
    /** Regions whose nearest point is further than this from the camera are skipped. */
    private static final double MAX_DISTANCE = 256;
    /** Markers over plots for sale show within this many blocks (D109). */
    private static final double MARKER_DISTANCE = 96;
    private static final int TQ = 0x19D3B0, VIO = 0xB58CFF, GOLD = 0xFFD23A, MUTED = 0x9AA3B5;

    /** The list the boxes below were built for, and its boxes in the same order (null: nothing to draw). */
    @Nullable
    private static RegionListPacket boxesFor;
    private static AABB[] boxes = new AABB[0];
    /** For plots for sale: the box plus the room a marker can take above it, for the view check. */
    private static AABB[] reach = new AABB[0];
    /** The regions shown this frame, as positions in the list; kept to spare a new list every frame. */
    private static final java.util.List<Integer> SHOWN = new java.util.ArrayList<>();

    private RegionRenderer() {
    }

    private static AABB[] boxesOf(RegionListPacket list) {
        if (list != boxesFor) {
            AABB[] out = new AABB[list.regions().size()], up = new AABB[out.length];
            for (int i = 0; i < out.length; i++) {
                RegionListPacket.Summary s = list.regions().get(i);
                Cuboid b = s.bounds();
                out[i] = b == null || com.mrgregles.bsp_region_guard.region.Region.noCorners(b) ? null
                        : new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
                up[i] = out[i] == null || s.look() == null ? out[i] : out[i].inflate(4).expandTowards(0, 52, 0); // a beacon's beam reaches highest
            }
            boxes = out;
            reach = up;
            boxesFor = list;
        }
        return boxes;
    }

    private static float red(int rgb) {
        return (rgb >> 16 & 0xFF) / 255f;
    }

    private static float green(int rgb) {
        return (rgb >> 8 & 0xFF) / 255f;
    }

    private static float blue(int rgb) {
        return (rgb & 0xFF) / 255f;
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        boolean show = RegionClient.showRegions();
        RegionListPacket list = RegionClient.list();
        if (list != null && list.remote()) {
            list = RegionClient.ownList(); // another server's regions are not in this world
        }
        SelectionPacket sel = RegionClient.selection();
        boolean anyRegion = list != null && list.dimension().equals(mc.level.dimension().location());
        boolean anySelection = show && sel != null && sel.dimension() != null && sel.dimension().equals(mc.level.dimension().location()) && (sel.pos1() != null || sel.pos2() != null);
        if (!anyRegion && !anySelection) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        // which regions show this frame: near enough and in view (boxes are built once per list, not every frame)
        SHOWN.clear();
        AABB[] boxes = anyRegion ? boxesOf(list) : new AABB[0];
        for (int i = 0; i < boxes.length; i++) {
            RegionListPacket.Summary s = list.regions().get(i);
            AABB box = boxes[i];
            boolean forSale = s.look() != null;
            if (box == null || !forSale && !show || distanceTo(box, cam) > MAX_DISTANCE) {
                continue;
            }
            if (event.getFrustum().isVisible(reach[i])) {
                SHOWN.add(i);
            }
        }
        AABB selBox = null;
        if (anySelection && sel.pos1() != null && sel.pos2() != null) {
            Cuboid c = Cuboid.of(sel.pos1(), sel.pos2());
            selBox = new AABB(c.minX(), c.minY(), c.minZ(), c.maxX() + 1, c.maxY() + 1, c.maxZ() + 1);
        }
        // 1. markers first, so the plots' faint faces drawn next blend over them instead of hiding them
        for (int i : SHOWN) {
            RegionListPacket.Summary s = list.regions().get(i);
            if (s.look() != null) {
                marker(pose, buffers, mc, s, boxes[i], cam);
            }
        }
        buffers.endBatch(RenderType.debugQuads());
        buffers.endBatch(RenderType.lines());
        buffers.endBatch();
        // 2. every face in one batch, 3. every edge in one batch, 4. the ids of regions that are not for sale
        VertexConsumer faces = buffers.getBuffer(RenderType.debugFilledBox());
        for (int i : SHOWN) {
            RegionListPacket.Summary s = list.regions().get(i);
            AABB box = boxes[i];
            int c = s.look() != null ? s.look().colour() : colourOf(s.type());
            boolean inside = cam.x > box.minX - 0.1 && cam.x < box.maxX + 0.1 && cam.y > box.minY - 0.1 && cam.y < box.maxY + 0.1 && cam.z > box.minZ - 0.1 && cam.z < box.maxZ + 0.1;
            LevelRenderer.addChainedFilledBoxVertices(pose, faces, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, red(c), green(c), blue(c), inside ? 0.10f : 0.18f);
        }
        if (selBox != null) {
            LevelRenderer.addChainedFilledBoxVertices(pose, faces, selBox.minX, selBox.minY, selBox.minZ, selBox.maxX, selBox.maxY, selBox.maxZ, red(GOLD), green(GOLD), blue(GOLD), 0.12f);
        }
        buffers.endBatch(RenderType.debugFilledBox());
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (int i : SHOWN) {
            RegionListPacket.Summary s = list.regions().get(i);
            int c = s.look() != null ? s.look().colour() : colourOf(s.type());
            LevelRenderer.renderLineBox(pose, lines, boxes[i], red(c), green(c), blue(c), 1f);
        }
        if (anySelection) {
            if (selBox != null) {
                LevelRenderer.renderLineBox(pose, lines, selBox, red(GOLD), green(GOLD), blue(GOLD), 1f);
            }
            corner(pose, lines, sel.pos1());
            corner(pose, lines, sel.pos2());
        }
        buffers.endBatch(RenderType.lines());
        for (int i : SHOWN) {
            RegionListPacket.Summary s = list.regions().get(i);
            if (s.look() == null) {
                AABB box = boxes[i];
                label(pose, buffers, camera, mc.font, s.id(), new Vec3(box.getCenter().x, box.maxY + 0.6, box.getCenter().z), colourOf(s.type()), cam);
            }
        }
        buffers.endBatch();
        pose.popPose();
    }

    /**
     * A plot's marker over its middle, standing on the ground there (the top of the plot's middle column, kept inside the
     * plot) plus the look's height, never above the plot's top (D107).
     */
    private static void marker(PoseStack pose, MultiBufferSource buffers, Minecraft mc, RegionListPacket.Summary s, AABB box, Vec3 cam) {
        double cx = box.getCenter().x, cz = box.getCenter().z;
        if (Math.hypot(cx - cam.x, cz - cam.z) > MARKER_DISTANCE) {
            return;
        }
        int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        double ground = mc.level.hasChunk(bx >> 4, bz >> 4) ? mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz) : box.minY;
        ground = Math.max(box.minY, Math.min(box.maxY - 1, ground));
        float foot = (float) Math.max(0, Math.min(s.look().height(), box.maxY - 1 - ground));
        pose.pushPose();
        pose.translate(cx, ground, cz);
        float yaw = (float) Math.atan2(cam.x - cx, cam.z - cz);
        PlotMarkers.draw(pose, buffers, mc.font, s.look(), s.name(), s.price(), foot, yaw, net.minecraft.Util.getMillis(), RenderType.lines());
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

    private static void corner(PoseStack pose, VertexConsumer lines, @Nullable BlockPos p) {
        if (p == null) {
            return;
        }
        AABB box = new AABB(p).inflate(0.02);
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
