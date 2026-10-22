package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.RegionGuardClientConfig;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Vector4f;

/**
 * A Region Guard screen drawn larger than Minecraft's GUI scale (client setting {@code ui.scale}, default 2, owner
 * decision D50): as large as asked or as fits the window, whichever is smaller, never below the original size.
 * Subclasses lay out in their own units ({@code width} and {@code height} are the window in those units), draw in
 * {@link #draw} and take clicks in {@link #onClick} and {@link #onScroll}, all with the mouse already converted.
 */
abstract class HoloScreen extends Screen {
    /** Room left around the screen when it is fitted to the window. */
    private static final float MARGIN = 8;
    private final int baseW, baseH;
    private float scale = 1;
    private int windowW, windowH, unitsW = -1, unitsH = -1;

    protected HoloScreen(Component title, int baseW, int baseH) {
        super(title);
        this.baseW = baseW;
        this.baseH = baseH;
    }

    /** The scale for a window of {@code w} by {@code h} GUI units. */
    static float scaleFor(int w, int h, int baseW, int baseH) {
        return scaleFor(w, h, baseW, baseH, 1);
    }

    /** As {@link #scaleFor(int, int, int, int)} for a screen drawn {@code factor} times {@code ui.scale}. */
    static float scaleFor(int w, int h, int baseW, int baseH, float factor) {
        return RegionGuardClientConfig.screenScale(RegionGuardConfig.getOr(RegionGuardClientConfig.UI_SCALE, 2.0) * factor, w, h, baseW, baseH, MARGIN);
    }

    /** How large this screen is drawn compared with {@code ui.scale}: 1, or less for a screen that fills the window (D80). */
    protected float sizeFactor() {
        return 1;
    }

    /**
     * Minecraft sets {@code width} and {@code height} to the window and calls this (on opening and on every resize);
     * they are turned into the screen's units here before {@link #layout} runs. A rebuild that calls this again with
     * the units already set keeps the window size from before.
     */
    @Override
    protected final void init() {
        if (width != unitsW || height != unitsH) {
            windowW = width;
            windowH = height;
        }
        scale = scaleFor(windowW, windowH, baseW, baseH, sizeFactor());
        width = unitsW = (int) (windowW / scale);
        height = unitsH = (int) (windowH / scale);
        layout();
    }

    /** Places the widgets, in the screen's units. */
    protected void layout() {
    }

    @Override
    public final void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1);
        draw(g, (int) (mouseX / scale), (int) (mouseY / scale), partial);
        g.pose().popPose();
    }

    /** Draws the screen in its own units; the default draws the widgets. */
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public final boolean mouseClicked(double mx, double my, int button) {
        return onClick(mx / scale, my / scale, button);
    }

    /** A click in the screen's units; the default passes it to the widgets. */
    protected boolean onClick(double mx, double my, int button) {
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public final boolean mouseScrolled(double mx, double my, double delta) {
        return onScroll(mx / scale, my / scale, delta);
    }

    /** A wheel turn in the screen's units; the default passes it to the widgets. */
    protected boolean onScroll(double mx, double my, double delta) {
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public final boolean mouseReleased(double mx, double my, int button) {
        return onRelease(mx / scale, my / scale, button);
    }

    /** A mouse button let go, in the screen's units; the default passes it to the widgets. */
    protected boolean onRelease(double mx, double my, int button) {
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return super.mouseDragged(mx / scale, my / scale, button, dx / scale, dy / scale);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        super.mouseMoved(mx / scale, my / scale);
    }

    /**
     * Clips drawing to a box given in the current drawing units. Minecraft's scissor ignores the drawing scale, so the
     * corners are carried through it here.
     */
    static void scissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        var m = g.pose().last().pose();
        Vector4f a = m.transform(new Vector4f(x1, y1, 0, 1)), b = m.transform(new Vector4f(x2, y2, 0, 1));
        g.enableScissor((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.ceil(b.x()), (int) Math.ceil(b.y()));
    }
}
