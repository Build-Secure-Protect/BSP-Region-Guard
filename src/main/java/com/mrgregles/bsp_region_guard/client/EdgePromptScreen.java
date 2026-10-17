package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.EdgeAnswerPacket;
import com.mrgregles.bsp_region_guard.network.EdgePromptPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The travel pop-up at a world edge linked to another server (owner decision D42: mock-up option A's centred window).
 * TRAVEL sends the player on; STAY or Esc closes it, and the server does not ask again until they walk away from
 * the edge and come back.
 */
public final class EdgePromptScreen extends HoloScreen {
    private static final int W = 220, H = 112, BTN_W = 70, BTN_H = 16;
    private final EdgePromptPacket prompt;
    private boolean answered;

    public EdgePromptScreen(EdgePromptPacket prompt) {
        super(Component.literal(Tr.t("edge.title")), W, H);
        this.prompt = prompt;
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
        RegionScreen.text(g, font, Tr.t("edge.title"), x + 10, y + 9, RegionScreen.TQ, 1f);
        String beyond = Tr.t("edge.beyond") + " ";
        RegionScreen.text(g, font, beyond, x + 10, y + 28, RegionScreen.TEXT, 1f);
        RegionScreen.text(g, font, prompt.server() + ".", x + 10 + font.width(beyond), y + 28, RegionScreen.VIO, 1f);
        RegionScreen.text(g, font, Tr.t("edge.arrive_in", worldName(prompt.world())), x + 10, y + 44, RegionScreen.MUTED, 0.85f);
        RegionScreen.text(g, font, Tr.t("edge.arrive_at", Math.round(prompt.x()), Math.round(prompt.z())), x + 10, y + 54, RegionScreen.MUTED, 0.85f);
        button(g, x + 10, y + H - BTN_H - 12, Tr.t("travel"), RegionScreen.TQ, mouseX, mouseY);
        button(g, x + 10 + BTN_W + 8, y + H - BTN_H - 12, Tr.t("stay"), RegionScreen.MUTED, mouseX, mouseY);
        RegionScreen.text(g, font, Tr.t("esc_stay"), x + W - 10 - (int) (font.width(Tr.t("esc_stay")) * 0.7f), y + H - 18, RegionScreen.MUTED, 0.7f);
        super.draw(g, mouseX, mouseY, partial);
    }

    private static String worldName(String id) {
        return switch (id) {
            case "minecraft:overworld" -> Tr.t("edge.its_overworld");
            case "minecraft:the_nether" -> Tr.t("edge.its_nether");
            case "minecraft:the_end" -> Tr.t("edge.its_end");
            default -> id;
        };
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
                answer(true);
                return true;
            }
            if (mx >= bx + BTN_W + 8 && mx < bx + 2 * BTN_W + 8) {
                answer(false);
                return true;
            }
        }
        return super.onClick(mx, my, button);
    }

    private void answer(boolean travel) {
        if (!answered) {
            answered = true;
            RegionNetwork.CHANNEL.sendToServer(new EdgeAnswerPacket(prompt.token(), travel));
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (!answered) {
            answered = true;
            RegionNetwork.CHANNEL.sendToServer(new EdgeAnswerPacket(prompt.token(), false));
        }
        super.onClose();
    }
}
