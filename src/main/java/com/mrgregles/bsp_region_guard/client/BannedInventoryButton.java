package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardClientConfig;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.network.BannedListPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * The BANNED button in the top left corner of the survival and creative inventory (owner decisions D136 to D138): shown
 * while the player stands where something is banned, it opens BANNED HERE. It sits in BSP-Core's row, right of the TEAM
 * and KITS buttons, in their look, and never on top of them: each frame it stands right of the last BSP-Core corner
 * button and steps past any other widget and the inventory panel. BSP-Core's buttons step past other widgets too, so
 * the two never fight over a place. Hidden with {@code ui.bannedButton = false} in the client config.
 */
@Mod.EventBusSubscriber(modid = BSPRegionGuard.MODID, value = Dist.CLIENT)
public final class BannedInventoryButton {
    /** BSP-Core's TEAM and KITS buttons (client/TeamInventoryButton, read by class name: BSP-Core gives no api for it). */
    static final String BSP_CORNER = com.mrgregles.bsp_region_guard.compat.BspCore.TEAM_BUTTON_CLASS; // a constant, inlined: the client never loads BspCore
    static final int W = 40, H = 14, GAP = 2, MARGIN = 2;
    /** Room kept free above and below the inventory panel for tab rows, as BSP-Core keeps. */
    private static final int TAB_ROWS = 32;

    private BannedInventoryButton() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!RegionGuardConfig.getOr(RegionGuardClientConfig.BANNED_BUTTON, true)) {
            return;
        }
        if (event.getScreen() instanceof InventoryScreen || event.getScreen() instanceof CreativeModeInventoryScreen) {
            AbstractContainerScreen<?> inv = (AbstractContainerScreen<?>) event.getScreen();
            BannedHereClient.askHere();
            event.addListener(new Corner(inv));
        }
    }

    /**
     * Where the button stands: right of the rightmost BSP-Core corner button on its row (or the window's corner when
     * there is none), stepping right past anything in the way, and down a row when the left half of the window is used.
     */
    static int[] place(AbstractContainerScreen<?> screen, AbstractWidget self) {
        List<int[]> blocked = new ArrayList<>();
        List<AbstractWidget> corners = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w && w != self && w.visible) {
                blocked.add(new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()});
                if (w.getClass().getName().equals(BSP_CORNER)) {
                    corners.add(w);
                }
            }
        }
        int x = MARGIN, y = MARGIN;
        if (!corners.isEmpty()) {
            y = corners.stream().mapToInt(AbstractWidget::getY).min().getAsInt();
            for (AbstractWidget c : corners) {
                if (c.getY() == y) {
                    x = Math.max(x, c.getX() + c.getWidth() + GAP);
                }
            }
        }
        blocked.add(new int[]{screen.getGuiLeft(), screen.getGuiTop() - TAB_ROWS, screen.getXSize(), screen.getYSize() + 2 * TAB_ROWS});
        for (int tries = 0; tries < 64; tries++) {
            int[] hit = overlap(blocked, x, y);
            if (hit == null) {
                return new int[]{x, y};
            }
            x = hit[0] + hit[2] + GAP;
            if (x + W > screen.width / 2) {
                x = MARGIN;
                y += H + GAP;
            }
        }
        return new int[]{x, y};
    }

    private static int[] overlap(List<int[]> blocked, int x, int y) {
        for (int[] b : blocked) {
            if (x < b[0] + b[2] && x + W > b[0] && y < b[1] + b[3] && y + H > b[1]) {
                return b;
            }
        }
        return null;
    }

    /** The button, placed afresh every frame and shown only while bans apply where the player stands. */
    static final class Corner extends Button {
        private static final int BG = 0xF010151C, TQ = 0xFF19D3B0, DIM = 0xFF2A2F3A, MUTED = 0x9AA3B5;
        private final AbstractContainerScreen<?> screen;
        private BannedListPacket tipFor;

        Corner(AbstractContainerScreen<?> screen) {
            super(MARGIN, MARGIN, W, H, Component.literal(Tr.t("banned_button")), b -> open(screen), DEFAULT_NARRATION);
            this.screen = screen;
            this.visible = false;
        }

        private static void open(AbstractContainerScreen<?> screen) {
            BannedListPacket here = BannedHereClient.here();
            if (here != null && here.any()) {
                Minecraft.getInstance().setScreen(new BannedHereScreen(screen, here));
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            BannedListPacket here = BannedHereClient.here();
            visible = here != null && here.any();
            if (visible && here != tipFor) {
                tipFor = here;
                String where = here.regionName().isEmpty() ? Tr.t("banned_here_world") : here.regionName();
                setTooltip(Tooltip.create(Component.literal(Tr.t("banned_button_tip", where))));
            }
            super.render(g, mouseX, mouseY, partialTick);
        }

        /** Drawn as BSP-Core draws TEAM: a dark box with a dim edge that turns teal under the mouse. */
        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int[] at = place(screen, this);
            setX(at[0]);
            setY(at[1]);
            int x = getX(), y = getY();
            boolean hot = isHoveredOrFocused();
            g.fill(x, y, x + W, y + H, hot ? TQ : DIM);
            g.fill(x + 1, y + 1, x + W - 1, y + H - 1, BG);
            var font = Minecraft.getInstance().font;
            String label = getMessage().getString();
            g.pose().pushPose();
            g.pose().translate(x + W / 2f - font.width(label) * 3 / 8f, y + 4, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(font, label, 0, 0, hot ? TQ & 0xFFFFFF : MUTED, false);
            g.pose().popPose();
        }
    }
}
