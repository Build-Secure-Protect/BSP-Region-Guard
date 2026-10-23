package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.ClaimActionPacket;
import com.mrgregles.bsp_region_guard.network.PlotBoughtPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * After buying a plot (owner decision D113): the edge-travel pop-up's layout, asking whether to go there. TELEPORT
 * sends the claim screen's teleport (the middle of the plot on the ground, or its teleport point); STAY or Esc closes.
 */
public final class PlotBoughtScreen extends HoloScreen {
    private static final int W = 220, H = 96, BTN_W = 70, BTN_H = 16;
    private final PlotBoughtPacket bought;

    public PlotBoughtScreen(PlotBoughtPacket bought) {
        super(Component.literal(Tr.t("bought.title", bought.name())), W, H);
        this.bought = bought;
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
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, RegionScreen.BG);
        frame(g, x, y, W, H, RegionScreen.TQ);
        RegionScreen.text(g, font, font.plainSubstrByWidth(Tr.t("bought.title", bought.name()), W - 20), x + 10, y + 9, RegionScreen.TQ, 1f);
        int qy = y + 26;
        for (var line : font.split(Component.literal(Tr.t("bought.question")), W - 20)) {
            g.drawString(font, line, x + 10, qy, RegionScreen.TEXT, false);
            qy += 10;
        }
        button(g, x + 10, y + H - BTN_H - 12, Tr.t("bought.teleport"), RegionScreen.TQ, mouseX, mouseY);
        button(g, x + 10 + BTN_W + 8, y + H - BTN_H - 12, Tr.t("stay"), RegionScreen.MUTED, mouseX, mouseY);
        RegionScreen.text(g, font, Tr.t("esc_stay"), x + W - 10 - (int) (font.width(Tr.t("esc_stay")) * 0.7f), y + H - 18, RegionScreen.MUTED, 0.7f);
        super.draw(g, mouseX, mouseY, partial);
    }

    private void button(GuiGraphics g, int x, int y, String label, int colour, int mx, int my) {
        boolean hover = mx >= x && mx < x + BTN_W && my >= y && my < y + BTN_H;
        g.fill(x, y, x + BTN_W, y + BTN_H, hover ? (colour & 0xFFFFFF) | 0x33000000 : RegionScreen.INSET);
        frame(g, x, y, BTN_W, BTN_H, colour);
        RegionScreen.text(g, font, label, x + (BTN_W - font.width(label)) / 2, y + 4, colour, 1f);
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        int bx = left() + 10, by = top() + H - BTN_H - 12;
        if (my >= by && my < by + BTN_H) {
            if (mx >= bx && mx < bx + BTN_W) {
                RegionNetwork.CHANNEL.sendToServer(ClaimActionPacket.of(ClaimActionPacket.TELEPORT, bought.claimId()));
                onClose();
                return true;
            }
            if (mx >= bx + BTN_W + 8 && mx < bx + 2 * BTN_W + 8) {
                onClose();
                return true;
            }
        }
        return super.onClick(mx, my, button);
    }
}
