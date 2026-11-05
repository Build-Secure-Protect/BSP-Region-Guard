package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.BannedListPacket;
import com.mrgregles.bsp_region_guard.protection.BannedBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BANNED HERE (owner decisions D133 to D139, mock-up layout A): every item a region bans, read-only, in one grid grouped
 * by mod. A search as JEI's ({@code @mod}), chips that show one rule at a time with their counts, and a card for the
 * item under the mouse with the rules that stop it. Opened from the inventory's BANNED button, {@code /claim banned},
 * the bought-plot pop-up and the Land Office; Esc returns to where it was opened from.
 */
public class BannedHereScreen extends HoloScreen {
    private static final int W = RegionScreen.W, H = RegionScreen.H;
    private static final int BG = RegionScreen.BG, INSET = RegionScreen.INSET, LINE = RegionScreen.LINE, TQ = RegionScreen.TQ,
            VIO = RegionScreen.VIO, GOLD = RegionScreen.GOLD, BAD = RegionScreen.BAD, TEXT = RegionScreen.TEXT, MUTED = RegionScreen.MUTED;
    private static final int HINT = 0xFF6F7788, SLOT = 0xFF171B22, SLOT_LINE = 0xFF262B35;
    private static final int CELL = 17, GRID_X = 12, GRID_Y = 42, COLS = 23, ROWS = 9;

    /** One line of the grid: a mod's name, or up to {@link #COLS} of its items. */
    private record Line(@Nullable String mod, List<BannedHereClient.Banned> items) {
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    @Nullable
    private final Screen parent;
    private final BannedListPacket list;
    private final List<BannedHereClient.Banned> all;
    private final int[] counts = new int[BannedBlocks.Rule.values().length];
    private final List<Hit> hits = new ArrayList<>();
    private List<Line> lines = List.of();
    /** The rule shown, or null for every banned item. */
    @Nullable
    private BannedBlocks.Rule only;
    private int scroll;
    @Nullable
    private BannedHereClient.Banned hover;
    private EditBox search;

    public BannedHereScreen(@Nullable Screen parent, BannedListPacket list) {
        super(Component.literal(Tr.t("banned_here")), W, H);
        this.parent = parent;
        this.list = list;
        this.all = BannedHereClient.resolve(list);
        for (BannedHereClient.Banned b : all) {
            for (BannedBlocks.Rule r : b.rules()) {
                counts[r.ordinal()]++;
            }
        }
    }

    static int colour(BannedBlocks.Rule r) {
        return switch (r) {
            case PLACE -> GOLD;
            case USE -> TQ;
            case BREAK -> VIO;
            case CARRY -> BAD;
        };
    }

    /** The strongest rule decides the slot's colour, as in the admin editor: CARRY, then PLACE, USE, BREAK. */
    private static BannedBlocks.Rule strongest(java.util.Set<BannedBlocks.Rule> rules) {
        return rules.contains(BannedBlocks.Rule.CARRY) ? BannedBlocks.Rule.CARRY : rules.contains(BannedBlocks.Rule.PLACE) ? BannedBlocks.Rule.PLACE
                : rules.contains(BannedBlocks.Rule.USE) ? BannedBlocks.Rule.USE : BannedBlocks.Rule.BREAK;
    }

    private static String ruleName(BannedBlocks.Rule r) {
        return Tr.t("player_rule." + r.name().toLowerCase(Locale.ROOT));
    }

    /** Groups what passes the search and the rule chip into lines, a heading line before each mod. */
    private void rebuild() {
        String q = search == null ? "" : search.getValue();
        List<Line> out = new ArrayList<>();
        String mod = null;
        List<BannedHereClient.Banned> row = null;
        for (BannedHereClient.Banned b : all) {
            if ((only != null && !b.rules().contains(only)) || !BannedBlocks.searchMatches(q, b.id(), b.name())) {
                continue;
            }
            if (!b.mod().equals(mod)) {
                mod = b.mod();
                out.add(new Line(mod, List.of()));
                row = null;
            }
            if (row == null || row.size() == COLS) {
                row = new ArrayList<>();
                out.add(new Line(null, row));
            }
            row.add(b);
        }
        lines = out;
        scroll = 0;
    }

    @Override
    protected void layout() {
        int x = left(), y = top();
        search = new EditBox(font, x + 12, y + 25, 110, 10, Component.literal(Tr.t("search_blocks")));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setHint(Component.literal(Tr.t("search_blocks_hint")).withStyle(s -> s.withColor(0x9AA3B5)));
        search.setResponder(v -> rebuild());
        addRenderableWidget(search);
        rebuild();
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
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partial) {
        hits.clear();
        renderBackground(g);
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, BG);
        frame(g, x, y, W, H, TQ);
        text(g, Tr.t("banned_here"), x + 8, y + 8, TQ, 1f);
        String where = list.regionName().isEmpty() ? Tr.t("banned_here_world") : list.regionName();
        text(g, font.plainSubstrByWidth(Tr.t("banned_here_sub", where, all.size()), 420), x + 80, y + 10, MUTED, 0.6f);
        chip(g, x + W - 52, y + 6, 44, 12, Tr.t("close"), false, TEXT, 0.75f, this::onClose);

        // search and the rule chips
        inset(g, x + 8, y + 22, 120, 14);
        int cx = x + 134;
        chip(g, cx, y + 23, 34, 12, Tr.t("banned_here_all", all.size()), only == null, TEXT, 0.55f, () -> {
            only = null;
            rebuild();
        });
        cx += 36;
        for (BannedBlocks.Rule r : BannedBlocks.Rule.values()) {
            int n = counts[r.ordinal()];
            String label = ruleName(r) + " " + n;
            int w = (int) (font.width(label) * 0.55f) + 10;
            chip(g, cx, y + 23, w, 12, label, only == r, n > 0 ? colour(r) : HINT, 0.55f, n == 0 ? null : () -> {
                only = only == r ? null : r;
                rebuild();
            });
            cx += w + 2;
        }

        drawGrid(g, x, y, mouseX, mouseY);
        drawCard(g, x, y);
        super.draw(g, mouseX, mouseY, partial);
        if (hover != null && mouseY < y + GRID_Y + ROWS * CELL) {
            g.renderTooltip(font, hover.stack(), mouseX, mouseY);
        }
    }

    private void drawGrid(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        inset(g, x + 8, y + 38, W - 16, ROWS * CELL + 8);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - ROWS)));
        hover = null;
        if (lines.isEmpty()) {
            text(g, all.isEmpty() ? Tr.t("banned_here_nothing") : Tr.t("banned_here_no_match"), x + 14, y + 46, HINT, 0.7f);
        }
        for (int i = 0; i < ROWS && scroll + i < lines.size(); i++) {
            Line line = lines.get(scroll + i);
            int ly = y + GRID_Y + i * CELL;
            if (line.mod() != null) {
                text(g, line.mod().toUpperCase(Locale.ROOT), x + GRID_X, ly + 6, MUTED, 0.6f);
                g.fill(x + GRID_X, ly + 13, x + GRID_X + COLS * CELL - 2, ly + 14, LINE);
                continue;
            }
            for (int c = 0; c < line.items().size(); c++) {
                BannedHereClient.Banned b = line.items().get(c);
                int sx = x + GRID_X + c * CELL;
                boolean over = mouseX >= sx && mouseX < sx + 16 && mouseY >= ly && mouseY < ly + 16;
                slot(g, sx, ly, colour(strongest(b.rules())), over);
                g.renderItem(b.stack(), sx, ly);
                if (over) {
                    hover = b;
                }
            }
        }
        // scroll bar
        int top = y + GRID_Y, h = ROWS * CELL;
        g.fill(x + W - 14, top, x + W - 10, top + h, SLOT);
        if (lines.size() > ROWS) {
            int bar = Math.max(12, h * ROWS / lines.size());
            int by = top + (h - bar) * scroll / (lines.size() - ROWS);
            g.fill(x + W - 14, by, x + W - 10, by + bar, 0xFF4A5060);
        }
    }

    private void drawCard(GuiGraphics g, int x, int y) {
        int cy = y + GRID_Y + ROWS * CELL + 8;
        g.fill(x + 8, cy, x + W - 8, y + H - 8, 0xFF0A0D12);
        frame(g, x + 8, cy, W - 16, y + H - 8 - cy, LINE);
        boolean anyCarry = counts[BannedBlocks.Rule.CARRY.ordinal()] > 0;
        String hint = anyCarry ? Tr.t("banned_here_carry_hint") : Tr.t("banned_here_keep_hint");
        if (hover == null) {
            text(g, Tr.t("banned_here_hover"), x + 12, cy + 5, HINT, 0.6f);
            text(g, hint, x + 12, cy + 14, MUTED, 0.6f);
            return;
        }
        g.renderItem(hover.stack(), x + 11, cy + 4);
        text(g, font.plainSubstrByWidth(hover.name(), 260), x + 30, cy + 4, TEXT, 0.7f);
        text(g, font.plainSubstrByWidth(hover.mod() + "  " + hover.id(), 330), x + 30, cy + 13, HINT, 0.55f);
        int rx = x + W - 12 - 4 * 46;
        for (BannedBlocks.Rule r : BannedBlocks.Rule.values()) {
            chip(g, rx, cy + 6, 44, 11, ruleName(r), hover.rules().contains(r), colour(r), 0.5f, null);
            rx += 46;
        }
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (super.onClick(mx, my, button)) {
            return true;
        }
        for (Hit h : new ArrayList<>(hits)) {
            if (h.contains(mx, my)) {
                h.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onScroll(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta) * 2;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // Esc: back to where it was opened from
            onClose();
            return true;
        }
        if (search.isFocused()) {
            return search.keyPressed(keyCode, scanCode, modifiers) || search.canConsumeInput() || super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ drawing helpers (as the admin editor's)

    private void text(GuiGraphics g, String s, int x, int y, int colour, float scale) {
        RegionScreen.text(g, font, s, x, y, colour, scale);
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
    }

    private static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, INSET);
        frame(g, x, y, w, h, LINE);
    }

    private static void slot(GuiGraphics g, int x, int y, int border, boolean picked) {
        g.fill(x, y, x + 16, y + 16, SLOT);
        frame(g, x - 1, y - 1, 18, 18, picked ? 0xFFFFFFFF : border);
    }

    private void chip(GuiGraphics g, int x, int y, int w, int h, String label, boolean on, int colour, float scale, @Nullable Runnable action) {
        g.fill(x, y, x + w, y + h, on ? (colour & 0xFFFFFF) | 0x22000000 : INSET);
        frame(g, x, y, w, h, on ? colour : LINE);
        int tw = (int) (font.width(label) * scale);
        text(g, label, x + (w - tw) / 2, y + (int) ((h - 8 * scale) / 2) + 1, on ? colour : MUTED, scale);
        if (action != null) {
            hits.add(new Hit(x, y, w, h, action));
        }
    }
}
