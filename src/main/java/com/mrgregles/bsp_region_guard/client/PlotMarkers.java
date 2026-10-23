package com.mrgregles.bsp_region_guard.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mrgregles.bsp_region_guard.claim.PlotLook;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * The markers over a plot for sale or an open area (owner decisions D105 to D110): the arrow, the orbit sphere, the
 * spire beacon and the for sale sign, or just the name. Drawn in the world by {@link RegionRenderer} and in the
 * PLOT LOOK window's preview, both with the pose's origin on the ground in the plot's middle and one block as one
 * unit. Everything is see-through and faces the viewer.
 */
final class PlotMarkers {
    private static final int WOOD = 0x6E4F2F, LIGHT = 0xF000F0;

    private PlotMarkers() {
    }

    /**
     * Draws one marker. {@code foot}: blocks from the ground up to the marker's lowest point (the look's height, kept
     * inside the plot by the caller); {@code yaw}: radians about the vertical axis that turn the marker's front to the
     * viewer; {@code millis}: the clock for bobbing, turning and pulsing; {@code lines}: the line type for edges
     * ({@code RenderType.lines()} in the world, {@link GuiRenderTypes#LINES} in a screen).
     */
    static void draw(PoseStack pose, MultiBufferSource buffers, Font font, PlotLook look, String name, String price, float foot, float yaw, long millis,
                     RenderType lines) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotation(yaw));
        float r = (look.colour() >> 16 & 255) / 255f, g = (look.colour() >> 8 & 255) / 255f, b = (look.colour() & 255) / 255f;
        switch (look.marker()) {
            case "arrow" -> arrow(pose, buffers, font, look, name, foot, millis, r, g, b, lines);
            case "sphere" -> sphere(pose, buffers, font, look, name, foot, millis, r, g, b);
            case "beacon" -> beacon(pose, buffers, font, look, name, foot, millis, r, g, b);
            case "sign" -> sign(pose, buffers, font, look, name, price, foot, millis, r, g, b, lines);
            default -> tag(pose, buffers, font, name, foot + 0.5f, look.text(), 0xFFFFFFFF);
        }
        pose.popPose();
    }

    /** A big arrow pointing down at the ground at 20% (D106), bobbing half a block, the name above it. */
    private static void arrow(PoseStack pose, MultiBufferSource buffers, Font font, PlotLook look, String name, float foot, long millis, float r, float g, float b,
                              RenderType lineType) {
        float s = look.size(), y0 = foot + 0.25f + (float) Math.sin(millis / 700.0) * 0.25f;
        float len = 4 * s, hw = 1.6f * s, sw = 0.55f * s, head = 1.5f * s;
        Matrix4f m = pose.last().pose();
        VertexConsumer fill = buffers.getBuffer(RenderType.debugQuads());
        quad(fill, m, -sw, y0 + head, 0, sw, y0 + head, 0, sw, y0 + len, 0, -sw, y0 + len, 0, r, g, b, 0.2f);
        quad(fill, m, -hw, y0 + head, 0, hw, y0 + head, 0, 0, y0, 0, 0, y0, 0, r, g, b, 0.2f);
        float[][] edge = {{-sw, y0 + len}, {sw, y0 + len}, {sw, y0 + head}, {hw, y0 + head}, {0, y0}, {-hw, y0 + head}, {-sw, y0 + head}};
        VertexConsumer lines = buffers.getBuffer(lineType);
        for (int i = 0; i < edge.length; i++) {
            float[] p = edge[i], q = edge[(i + 1) % edge.length];
            line(lines, pose.last(), p[0], p[1], 0, q[0], q[1], 0, r, g, b, 0.55f);
        }
        tag(pose, buffers, font, name, y0 + len + 0.3f, look.text(), 0xFFFFFFFF);
    }

    /** A glassy sphere with the name's letters circling it (idea 2). */
    private static void sphere(PoseStack pose, MultiBufferSource buffers, Font font, PlotLook look, String name, float foot, long millis, float r, float g, float b) {
        float rad = 1.3f * look.size(), cy = foot + rad + (float) Math.sin(millis / 900.0) * 0.1f;
        Matrix4f m = pose.last().pose();
        VertexConsumer fill = buffers.getBuffer(RenderType.debugQuads());
        for (int q = 0; q < SPHERE_QUADS; q++) {
            float shade = SPHERE_SHADE[q];
            int o = q * 12;
            float[] v = SPHERE;
            quad(fill, m, v[o] * rad, cy + v[o + 1] * rad, v[o + 2] * rad, v[o + 3] * rad, cy + v[o + 4] * rad, v[o + 5] * rad,
                    v[o + 6] * rad, cy + v[o + 7] * rad, v[o + 8] * rad, v[o + 9] * rad, cy + v[o + 10] * rad, v[o + 11] * rad,
                    r * shade, g * shade, b * shade, 0.28f);
        }
        String ring = (name.length() > 24 ? name.substring(0, 23) + "." : name) + "   "; // a longer ring would run its letters together
        // the ring is sized for the larger of the two sizes, so big letters keep their room
        double turn = millis / 1600.0, orbit = 1.3 * 1.6 * Math.max(look.size(), look.text());
        int argbBack = 0x59000000 | (int) (r * 255) << 16 | (int) (g * 255) << 8 | (int) (b * 255);
        for (int i = 0; i < ring.length(); i++) {
            double a = turn + 2 * Math.PI * i / ring.length();
            float x = (float) (Math.cos(a) * orbit), z = (float) (Math.sin(a) * orbit);
            String ch = String.valueOf(ring.charAt(i));
            if (ch.isBlank()) {
                continue;
            }
            pose.pushPose();
            pose.translate(x, cy, z);
            float k = 0.07f * look.text() * (z > 0 ? 1 : 0.85f);
            pose.scale(k, -k, k);
            font.drawInBatch(ch, -font.width(ch) / 2f, -font.lineHeight / 2f, z > 0 ? 0xFFFFFFFF : argbBack, false,
                    pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LIGHT);
            pose.popPose();
        }
    }

    /** A unit sphere as quads (10 rings of 16), worked out once: four corners of x, y, z per quad, and each quad's shade. */
    private static final int SPHERE_LAT = 10, SPHERE_LON = 16, SPHERE_QUADS = SPHERE_LAT * SPHERE_LON;
    private static final float[] SPHERE = new float[SPHERE_QUADS * 12], SPHERE_SHADE = new float[SPHERE_QUADS];

    static {
        float lx = -0.35f, ly = 0.8f, lz = 0.5f, ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        int q = 0;
        for (int i = 0; i < SPHERE_LAT; i++) {
            double t0 = Math.PI * i / SPHERE_LAT - Math.PI / 2, t1 = Math.PI * (i + 1) / SPHERE_LAT - Math.PI / 2;
            for (int j = 0; j < SPHERE_LON; j++, q++) {
                double p0 = 2 * Math.PI * j / SPHERE_LON, p1 = 2 * Math.PI * (j + 1) / SPHERE_LON;
                double[][] corners = {{t0, p0}, {t0, p1}, {t1, p1}, {t1, p0}};
                for (int c = 0; c < 4; c++) {
                    double t = corners[c][0], p = corners[c][1];
                    SPHERE[q * 12 + c * 3] = (float) (Math.cos(t) * Math.cos(p));
                    SPHERE[q * 12 + c * 3 + 1] = (float) Math.sin(t);
                    SPHERE[q * 12 + c * 3 + 2] = (float) (Math.cos(t) * Math.sin(p));
                }
                double tc = (t0 + t1) / 2, pc = (p0 + p1) / 2;
                float nx = (float) (Math.cos(tc) * Math.cos(pc)), ny = (float) Math.sin(tc), nz = (float) (Math.cos(tc) * Math.sin(pc));
                SPHERE_SHADE[q] = 0.55f + 0.45f * Math.max(0, (nx * lx + ny * ly + nz * lz) / ll);
            }
        }
    }

    /** A thin pulsing beam from the ground, a small turning crystal at the height, the name above it (idea 3). */
    private static void beacon(PoseStack pose, MultiBufferSource buffers, Font font, PlotLook look, String name, float foot, long millis, float r, float g, float b) {
        float s = look.size(), pulse = (float) (Math.sin(millis / 450.0) + 1) / 2, top = foot + 48;
        float bw = 0.25f * s, core = bw / 3;
        Matrix4f m = pose.last().pose();
        VertexConsumer fill = buffers.getBuffer(RenderType.debugQuads());
        gradient(fill, m, -bw, 0, bw, top, r, g, b, 0.15f + 0.3f * pulse);
        gradient(fill, m, -core, 0, core, top, 1, 1, 1, 0.25f + 0.35f * pulse);
        float k = 0.9f * s, cy = foot + k;
        pose.pushPose();
        pose.translate(0, cy, 0);
        pose.mulPose(Axis.YP.rotation((float) (millis / 1000.0)));
        Matrix4f c = pose.last().pose();
        float[][] ring = {{k * 0.7f, 0}, {0, k * 0.7f}, {-k * 0.7f, 0}, {0, -k * 0.7f}};
        float a = 0.5f + 0.3f * pulse;
        for (int i = 0; i < 4; i++) {
            float[] p = ring[i], q = ring[(i + 1) % 4];
            float shade = i % 2 == 0 ? 1f : 0.75f;
            quad(fill, c, p[0], 0, p[1], q[0], 0, q[1], 0, k, 0, 0, k, 0, r * shade, g * shade, b * shade, a);
            quad(fill, c, p[0], 0, p[1], q[0], 0, q[1], 0, -k, 0, 0, -k, 0, r * shade * 0.7f, g * shade * 0.7f, b * shade * 0.7f, a);
        }
        pose.popPose();
        tag(pose, buffers, font, name, cy + k + 0.3f, look.text(), 0xFFFFFFFF);
    }

    /** A big floating board: FOR SALE, the name and the price, framed in the plot's colour (idea 4, D108). */
    private static void sign(PoseStack pose, MultiBufferSource buffers, Font font, PlotLook look, String name, String price, float foot, long millis,
                             float r, float g, float b, RenderType lineType) {
        // the board grows with the name size too, so its three lines never run into each other
        float s = look.size(), w = 5.2f * s, h = 2.8f * s * Math.max(1f, look.text()), x0 = -w / 2 + (float) Math.sin(millis / 1100.0) * 0.05f;
        Matrix4f m = pose.last().pose();
        float wr = (WOOD >> 16 & 255) / 255f, wg = (WOOD >> 8 & 255) / 255f, wb = (WOOD & 255) / 255f;
        quad(buffers.getBuffer(RenderType.debugQuads()), m, x0, foot, 0, x0 + w, foot, 0, x0 + w, foot + h, 0, x0, foot + h, 0, wr, wg, wb, 0.4f);
        VertexConsumer lines = buffers.getBuffer(lineType);
        line(lines, pose.last(), x0, foot, 0, x0 + w, foot, 0, r, g, b, 1f);
        line(lines, pose.last(), x0 + w, foot, 0, x0 + w, foot + h, 0, r, g, b, 1f);
        line(lines, pose.last(), x0 + w, foot + h, 0, x0, foot + h, 0, r, g, b, 1f);
        line(lines, pose.last(), x0, foot + h, 0, x0, foot, 0, r, g, b, 1f);
        float t = look.text(), cx = x0 + w / 2;
        float room = w * 0.92f; // the board's inside, in blocks: longer lines are cut to fit
        int rgb = 0xFF000000 | (int) (r * 255) << 16 | (int) (g * 255) << 8 | (int) (b * 255);
        text(pose, buffers, font, fit(font, Tr.t("look.for_sale"), room, 0.055f * s * t), cx, foot + h * 0.72f, 0.055f * s * t, rgb);
        text(pose, buffers, font, fit(font, name, room, 0.04f * s * t), cx, foot + h * 0.45f, 0.04f * s * t, 0xFFFFFFFF);
        text(pose, buffers, font, fit(font, price, room, 0.033f * s * t), cx, foot + h * 0.2f, 0.033f * s * t, 0xFFE9D9B4);
    }

    /** {@code s} cut to {@code room} blocks when drawn at {@code k} blocks per font pixel. */
    private static String fit(Font font, String s, float room, float k) {
        int px = (int) (room / k);
        return font.width(s) <= px ? s : font.plainSubstrByWidth(s, px - font.width("..")) + "..";
    }

    /** The name floating at {@code y}, on a faint dark plate, like a name tag. */
    private static void tag(PoseStack pose, MultiBufferSource buffers, Font font, String text, float y, float size, int argb) {
        pose.pushPose();
        pose.translate(0, y, 0);
        float k = 0.06f * size;
        pose.scale(k, -k, k);
        font.drawInBatch(text, -font.width(text) / 2f, -font.lineHeight, argb, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0x40000000, LIGHT);
        pose.popPose();
    }

    /** A line of text centred on {@code x}, with its middle at {@code y}, a little in front of the board. */
    private static void text(PoseStack pose, MultiBufferSource buffers, Font font, String text, float x, float y, float k, int argb) {
        pose.pushPose();
        pose.translate(x, y, 0.02f);
        pose.scale(k, -k, k);
        font.drawInBatch(text, -font.width(text) / 2f, -font.lineHeight / 2f, argb, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LIGHT);
        pose.popPose();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4, float r, float g, float b, float a) {
        vc.vertex(m, x1, y1, z1).color(r, g, b, a).endVertex();
        vc.vertex(m, x2, y2, z2).color(r, g, b, a).endVertex();
        vc.vertex(m, x3, y3, z3).color(r, g, b, a).endVertex();
        vc.vertex(m, x4, y4, z4).color(r, g, b, a).endVertex();
    }

    /** An upright strip, {@code a} at the bottom fading to nothing at the top. */
    private static void gradient(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        vc.vertex(m, x1, y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(m, x2, y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(m, x2, y2, 0).color(r, g, b, 0).endVertex();
        vc.vertex(m, x1, y2, 0).color(r, g, b, 0).endVertex();
    }

    static void line(VertexConsumer vc, PoseStack.Pose p, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1, len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        vc.vertex(p.pose(), x1, y1, z1).color(r, g, b, a).normal(p.normal(), dx, dy, dz).endVertex();
        vc.vertex(p.pose(), x2, y2, z2).color(r, g, b, a).normal(p.normal(), dx, dy, dz).endVertex();
    }
}
