package com.mrgregles.bsp_region_guard.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mrgregles.bsp_region_guard.claim.PlotLook;
import com.mrgregles.bsp_region_guard.network.RegionActionPacket;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The PLOT LOOK window (owner decision D111, mock-up option C): a big preview of the plot's marker that turns, and on
 * the right the marker, the colour (eight colours and a hex box), the marker's and the name's size, and the height
 * above the ground. SAVE sends it; CANCEL or Esc goes back to where it was opened (the SALE tab or the region map).
 */
public final class PlotLookScreen extends HoloScreen {
    private static final int W = 372, H = 214, PV_X = 10, PV_Y = 24, PV_W = 200, PV_H = 150, RX = 220;
    private static final int TQ = RegionScreen.TQ, GOLD = RegionScreen.GOLD, BAD = RegionScreen.BAD, TEXT = RegionScreen.TEXT, MUTED = RegionScreen.MUTED;

    private final String id;
    private final Screen back;
    private final String name, price;
    private final int plotHeight;
    private PlotLook look;
    private float turn = -0.6f;
    private boolean dragging;
    @Nullable
    private EditBox hex;
    private final List<Hit> hits = new ArrayList<>();
    /** The hint under the settings, wrapped once. */
    @Nullable
    private List<net.minecraft.util.FormattedCharSequence> hint;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    public PlotLookScreen(String id, Screen back) {
        super(Component.literal(Tr.t("look.title", id)), W, H);
        this.id = id;
        this.back = back;
        RegionListPacket list = RegionClient.ownList() != null ? RegionClient.ownList() : RegionClient.list();
        RegionListPacket.Summary s = list == null ? null : list.find(id);
        this.look = s != null && s.look() != null ? s.look() : PlotLook.DEFAULT;
        this.name = s == null ? id : s.name();
        this.price = s == null || s.price().isEmpty() ? Tr.t("look.price_example") : s.price();
        this.plotHeight = s == null || s.bounds() == null ? 32 : s.bounds().sizeY();
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void layout() {
        hex = new EditBox(font, left() + RX, top() + 92, 62, 12, Component.literal(Tr.t("look.colour")));
        hex.setMaxLength(7);
        hex.setValue(look.hex());
        hex.setResponder(v -> {
            Integer c = PlotLook.parseColour(v);
            if (c != null) {
                look = look.withColour(c);
            }
        });
        addRenderableWidget(hex);
    }

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        hits.clear();
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, RegionScreen.BG);
        frame(g, x, y, W, H, TQ);
        RegionScreen.text(g, font, font.plainSubstrByWidth(Tr.t("look.title", id), W - 20), x + 10, y + 9, TQ, 1f);

        // the preview
        int px = x + PV_X, py = y + PV_Y;
        g.fill(px, py, px + PV_W, py + PV_H, RegionScreen.INSET);
        frame(g, px, py, PV_W, PV_H, RegionScreen.LINE);
        scissor(g, px + 1, py + 1, px + PV_W - 1, py + PV_H - 1);
        preview(g, px + PV_W / 2f, py + PV_H * 0.8f);
        g.disableScissor();
        chip(g, px, py + PV_H + 5, 40, 12, Tr.t("look.turn_left"), false, MUTED, () -> turn -= 0.4f);
        chip(g, px + 43, py + PV_H + 5, 40, 12, Tr.t("look.turn_right"), false, MUTED, () -> turn += 0.4f);
        RegionScreen.text(g, font, Tr.t("look.drag_to_turn"), px + 88, py + PV_H + 8, RegionScreen.MUTED, 0.6f);

        // the settings
        int rx = x + RX, ry = y + PV_Y;
        RegionScreen.text(g, font, Tr.t("look.marker"), rx, ry, MUTED, 0.75f);
        int cx = rx, row = ry + 9;
        for (String m : PlotLook.MARKERS) {
            String label = Tr.t("look_marker." + m);
            int cw = (int) (font.width(label) * 0.75f) + 8;
            if (cx + cw > x + W - 8) {
                cx = rx;
                row += 14;
            }
            chip(g, cx, row, cw, 12, label, look.marker().equals(m), TQ, () -> look = look.withMarker(m));
            cx += cw + 3;
        }
        ry = row + 18;
        RegionScreen.text(g, font, Tr.t("look.colour"), rx, ry, MUTED, 0.75f);
        int sx = rx;
        for (int c : PlotLook.COLOURS) {
            boolean on = (look.colour() & 0xFFFFFF) == c;
            g.fill(sx, ry + 9, sx + 12, ry + 21, 0xFF000000 | c);
            frame(g, sx - 1, ry + 8, 14, 14, on ? 0xFFFFFFFF : RegionScreen.LINE);
            hits.add(new Hit(sx, ry + 9, 12, 12, () -> {
                look = look.withColour(c);
                if (hex != null) {
                    hex.setValue(look.hex());
                }
            }));
            sx += 15;
        }
        if (hex != null) {
            hex.setX(rx);
            hex.setY(ry + 26);
        }
        g.fill(rx + 66, ry + 26, rx + 78, ry + 38, 0xFF000000 | look.colour());
        ry += 46;
        ry = stepper(g, rx, ry, Tr.t("look.size"), String.format(Locale.ROOT, "%.2fx", look.size()),
                () -> look = look.withSize(look.size() - 0.25f), () -> look = look.withSize(look.size() + 0.25f));
        ry = stepper(g, rx, ry, Tr.t("look.name_size"), String.format(Locale.ROOT, "%.2fx", look.text()),
                () -> look = look.withText(look.text() - 0.25f), () -> look = look.withText(look.text() + 0.25f));
        int maxHeight = Math.max(0, Math.min(PlotLook.MAX_HEIGHT, plotHeight - 1));
        ry = stepper(g, rx, ry, Tr.t("look.height"), look.height() == 1 ? Tr.t("look.block") : Tr.t("look.blocks", look.height()),
                () -> look = look.withHeight(look.height() - 1), () -> look = look.withHeight(Math.min(maxHeight, look.height() + 1)));
        int hy = ry + 2;
        if (hint == null) {
            hint = font.split(Component.literal(Tr.t("look.shows_while_for_sale")), (int) ((x + W - 8 - rx) / 0.6f));
        }
        for (var line : hint) {
            g.pose().pushPose();
            g.pose().translate(rx, hy, 0);
            g.pose().scale(0.6f, 0.6f, 1f);
            g.drawString(font, line, 0, 0, RegionScreen.MUTED, false);
            g.pose().popPose();
            hy += 6;
        }

        chip(g, x + W - 128, y + H - 22, 56, 14, Tr.t("cancel"), false, MUTED, this::onClose);
        chip(g, x + W - 66, y + H - 22, 56, 14, Tr.t("look.save"), true, TQ, this::save);
        super.draw(g, mouseX, mouseY, partial);
    }

    /** A label, a minus, the value and a plus; returns the next row's y. */
    private int stepper(GuiGraphics g, int x, int y, String label, String value, Runnable down, Runnable up) {
        RegionScreen.text(g, font, label, x, y + 3, MUTED, 0.75f);
        chip(g, x + 62, y, 12, 12, "-", false, TEXT, down);
        RegionScreen.text(g, font, value, x + 78, y + 3, TEXT, 0.75f);
        chip(g, x + 122, y, 12, 12, "+", false, TEXT, up);
        return y + 16;
    }

    /**
     * The plot in small: a patch of grass, the outline in the plot's colour and the marker over the middle, seen from
     * a little above and turned by {@link #turn}. Not to scale: the patch is always 10 blocks across.
     */
    private void preview(GuiGraphics g, float cx, float cy) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 150);
        float k = PV_H / (7f + 4.5f * look.size() + Math.min(look.height(), 6));
        pose.scale(k, -k, k);
        pose.mulPose(Axis.XP.rotationDegrees(22));
        pose.mulPose(Axis.YP.rotation(turn));
        MultiBufferSource.BufferSource buffers = g.bufferSource();
        Matrix4f m = pose.last().pose();
        VertexConsumer fill = buffers.getBuffer(RenderType.debugQuads());
        for (int i = -6; i < 6; i++) {
            for (int j = -6; j < 6; j++) {
                boolean inside = i >= -5 && i < 5 && j >= -5 && j < 5;
                float shade = ((i * 7 + j * 13) & 3) * 0.04f + (inside ? 0.62f : 0.5f);
                fill.vertex(m, i, 0, j).color(0.33f * shade, 0.62f * shade, 0.24f * shade, 1f).endVertex();
                fill.vertex(m, i + 1, 0, j).color(0.33f * shade, 0.62f * shade, 0.24f * shade, 1f).endVertex();
                fill.vertex(m, i + 1, 0, j + 1).color(0.33f * shade, 0.62f * shade, 0.24f * shade, 1f).endVertex();
                fill.vertex(m, i, 0, j + 1).color(0.33f * shade, 0.62f * shade, 0.24f * shade, 1f).endVertex();
            }
        }
        float r = (look.colour() >> 16 & 255) / 255f, gr = (look.colour() >> 8 & 255) / 255f, b = (look.colour() & 255) / 255f;
        float top = Math.min(10, plotHeight);
        VertexConsumer lines = buffers.getBuffer(GuiRenderTypes.LINES);
        float[][] c = {{-5, -5}, {5, -5}, {5, 5}, {-5, 5}};
        for (int i = 0; i < 4; i++) {
            float[] p = c[i], q = c[(i + 1) % 4];
            PlotMarkers.line(lines, pose.last(), p[0], 0.02f, p[1], q[0], 0.02f, q[1], r, gr, b, 1f);
            PlotMarkers.line(lines, pose.last(), p[0], top, p[1], q[0], top, q[1], r, gr, b, 0.45f);
            PlotMarkers.line(lines, pose.last(), p[0], 0, p[1], p[0], top, p[1], r, gr, b, 0.45f);
        }
        float foot = Math.min(look.height(), top - 1);
        PlotMarkers.draw(pose, buffers, font, look, name, price, Math.max(0, foot), -turn, net.minecraft.Util.getMillis(), GuiRenderTypes.LINES);
        buffers.endBatch(RenderType.debugQuads());
        buffers.endBatch(GuiRenderTypes.LINES);
        g.flush();
        pose.popPose();
    }

    private void chip(GuiGraphics g, int x, int y, int w, int h, String label, boolean on, int colour, @Nullable Runnable action) {
        g.fill(x, y, x + w, y + h, on ? (colour & 0xFFFFFF) | 0x22000000 : RegionScreen.INSET);
        frame(g, x, y, w, h, on ? colour : RegionScreen.LINE);
        RegionScreen.text(g, font, label, x + (w - (int) (font.width(label) * 0.75f)) / 2, y + (h - 6) / 2, on ? colour : MUTED, 0.75f);
        if (action != null) {
            hits.add(new Hit(x, y, w, h, action));
        }
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        for (Hit h : List.copyOf(hits)) {
            if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                h.action.run();
                return true;
            }
        }
        int px = left() + PV_X, py = top() + PV_Y;
        if (mx >= px && mx < px + PV_W && my >= py && my < py + PV_H) {
            dragging = true;
            return true;
        }
        return super.onClick(mx, my, button);
    }

    @Override
    protected boolean onRelease(double mx, double my, int button) {
        dragging = false;
        return super.onRelease(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            turn += (float) dx * 0.01f;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    private void save() {
        RegionNetwork.CHANNEL.sendToServer(new RegionActionPacket(RegionActionPacket.SET_DATA, id, "look", look.line()));
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(back);
        }
    }
}
