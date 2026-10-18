package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.BannedPopupPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The window a player sees when a region turns them away for carrying banned things (D54, mock-up
 * https://claude.ai/artifact/Mx5t8T5WmaHcMuww1mtZME): a picture and name of each banned thing they carry and where
 * it is, what to do, and OK. Esc closes it too.
 */
public final class BannedPopupScreen extends HoloScreen {
    private static final int W = 240, H = 126, CELL = 22, COL = 38, BTN_W = 60, BTN_H = 15;
    private final BannedPopupPacket popup;

    public BannedPopupScreen(BannedPopupPacket popup) {
        super(Component.literal(Tr.t("banned_popup_title", popup.regionId())), W, H);
        this.popup = popup;
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
        frame(g, x, y, W, H, RegionScreen.BAD);
        centred(g, Tr.t("banned_popup_title", popup.regionId()), y + 8, RegionScreen.BAD, 1f);

        int shown = 0;
        for (ItemStack s : popup.items()) {
            if (!s.isEmpty()) {
                shown++;
            }
        }
        int startX = x + (W - shown * COL) / 2;
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0, col = 0; i < popup.items().size(); i++) {
            ItemStack s = popup.items().get(i);
            if (s.isEmpty()) {
                continue;
            }
            int cx = startX + col * COL + (COL - CELL) / 2, cy = y + 24;
            g.fill(cx, cy, cx + CELL, cy + CELL, RegionScreen.INSET);
            frame(g, cx, cy, CELL, CELL, RegionScreen.BAD);
            g.renderItem(s, cx + 3, cy + 3);
            String name = font.plainSubstrByWidth(s.getHoverName().getString(), (int) ((COL - 2) / 0.55f));
            RegionScreen.text(g, font, name, startX + col * COL + (COL - (int) (font.width(name) * 0.55f)) / 2, cy + CELL + 3, RegionScreen.TEXT, 0.55f);
            String where = popup.where().get(i);
            where = font.plainSubstrByWidth(where, (int) ((COL - 2) / 0.5f));
            RegionScreen.text(g, font, where, startX + col * COL + (COL - (int) (font.width(where) * 0.5f)) / 2, cy + CELL + 9, 0xFF6F7788, 0.5f);
            if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
                hovered = s;
            }
            col++;
        }
        int more = popup.items().size() - shown;
        if (more > 0) {
            centred(g, Tr.t("banned_popup_more", more), y + 66, RegionScreen.MUTED, 0.6f);
        }
        centred(g, Tr.t("banned_popup_remove"), y + 76, RegionScreen.TEXT, 0.75f);
        centred(g, Tr.t("banned_popup_chest"), y + 86, RegionScreen.MUTED, 0.6f);

        int bx = x + (W - BTN_W) / 2, by = y + H - BTN_H - 8;
        boolean hover = mouseX >= bx && mouseX < bx + BTN_W && mouseY >= by && mouseY < by + BTN_H;
        g.fill(bx, by, bx + BTN_W, by + BTN_H, hover ? 0x3319D3B0 : 0x2219D3B0);
        frame(g, bx, by, BTN_W, BTN_H, RegionScreen.TQ);
        String ok = Tr.t("ok");
        RegionScreen.text(g, font, ok, bx + (BTN_W - font.width(ok)) / 2, by + 4, RegionScreen.TQ, 1f);
        super.draw(g, mouseX, mouseY, partial);
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    private void centred(GuiGraphics g, String s, int y, int colour, float scale) {
        int w = (int) (font.width(s) * scale);
        RegionScreen.text(g, font, s, left() + (W - w) / 2, y, colour, scale);
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        int bx = left() + (W - BTN_W) / 2, by = top() + H - BTN_H - 8;
        if (mx >= bx && mx < bx + BTN_W && my >= by && my < by + BTN_H) {
            onClose();
            return true;
        }
        return super.onClick(mx, my, button);
    }
}
