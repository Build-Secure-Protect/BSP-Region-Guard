package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.mine.MineData;
import com.mrgregles.bsp_region_guard.network.RegionActionPacket;
import com.mrgregles.bsp_region_guard.network.RegionDetailPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The MINE FILL window (owner decisions D115 to D119, mock-up option B): the mine's blocks on the left, each with a
 * percentage box (one decimal), and the mine's total under them; the creative-style block browser on the right. One
 * block can be marked REST: it fills whatever share is left and is the mine's filler (the others grow as veins). SAVE
 * only when the total is 100%; CANCEL or Esc goes back to where it was opened.
 */
public final class MineFillScreen extends HoloScreen {
    private static final int W = RegionScreen.W, H = RegionScreen.H;
    private static final int LIST_X = 10, LIST_W = 176, LIST_Y = 38, ROW = 15, ROWS = 9;
    private static final int BROWSE_X = 194, BROWSE_Y = 26, BROWSE_COLS = 12, BROWSE_ROWS = 8;
    private static final int TQ = RegionScreen.TQ, GOLD = RegionScreen.GOLD, BAD = RegionScreen.BAD, TEXT = RegionScreen.TEXT, MUTED = RegionScreen.MUTED;

    /** One block of the mine: its id, its icon, and its share as typed. */
    private static final class Row {
        final ResourceLocation block;
        final ItemStack icon;
        String text;

        Row(ResourceLocation block, String text) {
            this.block = block;
            this.icon = BlockBrowser.iconOf(block);
            this.text = text;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final String id;
    private final Screen back;
    private final List<Row> rows = new ArrayList<>();
    @Nullable
    private ResourceLocation rest;
    private boolean loaded;
    private String size = "";
    private String status = "";
    private int scroll;
    private final List<Hit> hits = new ArrayList<>();
    /** One box per visible row; {@code boundTo} is the row each box shows (-1 none). */
    private final EditBox[] boxes = new EditBox[ROWS];
    private final int[] boundTo = new int[ROWS];
    private EditBox search;
    private BlockBrowser browser;

    public MineFillScreen(String id, Screen back) {
        super(Component.literal(Tr.t("fill.title", id)), W, H);
        this.id = id;
        this.back = back;
        RegionDetailPacket d = RegionClient.detail();
        if (d != null && d.id().equals(id)) {
            load(d);
        } else {
            RegionNetwork.CHANNEL.sendToServer(RegionActionPacket.of(RegionActionPacket.SELECT, id));
        }
    }

    /** The mine's fill table from the detail, its weights turned into percentages (D119). */
    void onDetail(RegionDetailPacket d) {
        if (!loaded && d.id().equals(id)) {
            load(d);
        }
    }

    private void load(RegionDetailPacket d) {
        loaded = true;
        rows.clear();
        List<ResourceLocation> blocks = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (String part : d.data().getOrDefault("fill", "").split(";")) {
            int eq = part.lastIndexOf('=');
            ResourceLocation b = eq < 0 ? null : ResourceLocation.tryParse(part.substring(0, eq));
            if (b != null) {
                try {
                    weights.add(Math.max(0, Integer.parseInt(part.substring(eq + 1))));
                    blocks.add(b);
                } catch (NumberFormatException ignored) {
                    // a broken row; left out
                }
            }
        }
        int total = weights.stream().mapToInt(Integer::intValue).sum();
        int[] tenths = new int[weights.size()];
        int sum = 0, biggest = 0;
        for (int i = 0; i < tenths.length; i++) {
            tenths[i] = total == MineData.WHOLE ? weights.get(i) : (int) Math.round(weights.get(i) * (double) MineData.WHOLE / Math.max(1, total));
            sum += tenths[i];
            if (tenths[i] > tenths[biggest]) {
                biggest = i;
            }
        }
        if (tenths.length > 0 && sum != MineData.WHOLE) {
            tenths[biggest] += MineData.WHOLE - sum; // rounding left over goes to the biggest share
        }
        for (int i = 0; i < tenths.length; i++) {
            rows.add(new Row(blocks.get(i), percent(tenths[i])));
        }
        String filler = d.data().getOrDefault(MineData.FILLER, "");
        rest = filler.isEmpty() ? null : ResourceLocation.tryParse(filler);
        if (rest != null && rows.stream().noneMatch(r -> r.block.equals(rest))) {
            rest = null;
        }
        Cuboid b = d.bounds();
        size = b == null ? "" : Tr.t("fill.size", b.sizeX(), b.sizeY(), b.sizeZ(), String.format(Locale.ROOT, "%,d", b.volume()));
        java.util.Arrays.fill(boundTo, -1);
    }

    private static String percent(int tenths) {
        return tenths % 10 == 0 ? String.valueOf(tenths / 10) : String.format(Locale.ROOT, "%.1f", tenths / 10.0);
    }

    /** A typed share in tenths of a percent, or -1 when it is not a number of 0 or more. */
    static int tenths(String s) {
        try {
            double v = Double.parseDouble(s.trim().replace(',', '.'));
            return v < 0 || Double.isNaN(v) ? -1 : (int) Math.round(v * 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ the totals

    /** The shares of every row but REST added up, in tenths; -1 when one is not a number. */
    private int others() {
        int sum = 0;
        for (Row r : rows) {
            if (r.block.equals(rest)) {
                continue;
            }
            int t = tenths(r.text);
            if (t < 0) {
                return -1;
            }
            sum += t;
        }
        return sum;
    }

    /** Why SAVE is not possible yet, or null when it is. */
    @Nullable
    private String problem() {
        if (rows.isEmpty()) {
            return Tr.t("fill.add_hint");
        }
        int others = others();
        if (others < 0) {
            return Tr.t("fill.bad_number");
        }
        for (Row r : rows) {
            if (!r.block.equals(rest) && tenths(r.text) == 0) {
                return Tr.t("fill.zero");
            }
        }
        if (rest != null) {
            return others >= MineData.WHOLE ? Tr.t("fill.rest_empty") : null;
        }
        if (others < MineData.WHOLE) {
            return Tr.t("fill.left", percent(MineData.WHOLE - others) + "%");
        }
        return others > MineData.WHOLE ? Tr.t("fill.too_much", percent(others - MineData.WHOLE) + "%") : null;
    }

    private void save() {
        if (problem() != null) {
            return;
        }
        int others = others();
        StringBuilder table = new StringBuilder();
        for (Row r : rows) {
            int t = r.block.equals(rest) ? MineData.WHOLE - others : tenths(r.text);
            table.append(table.length() == 0 ? "" : ";").append(r.block).append('=').append(t);
        }
        RegionNetwork.CHANNEL.sendToServer(new RegionActionPacket(RegionActionPacket.MINE_FILL, id, rest == null ? "" : rest.toString(), table.toString()));
        onClose();
    }

    // ------------------------------------------------------------------ screen

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
        for (int i = 0; i < ROWS; i++) {
            final int slot = i;
            EditBox box = new EditBox(font, 0, 0, 30, 11, Component.literal(Tr.t("fill.percent")));
            box.setMaxLength(6);
            box.setResponder(v -> {
                int r = boundTo[slot];
                if (r >= 0 && r < rows.size()) {
                    rows.get(r).text = v;
                }
            });
            box.visible = false;
            boxes[i] = addRenderableWidget(box);
        }
        java.util.Arrays.fill(boundTo, -1);
        search = new EditBox(font, 0, 0, 98, 10, Component.literal(Tr.t("search_blocks")));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setHint(BlockBrowser.hint());
        addRenderableWidget(search);
        browser = new BlockBrowser(font, search);
        browser.place(left() + BROWSE_X, top() + BROWSE_Y, BROWSE_COLS, BROWSE_ROWS);
        browser.load();
    }

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        hits.clear();
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, RegionScreen.BG);
        BlockBrowser.frame(g, x, y, W, H, TQ);
        RegionScreen.text(g, font, font.plainSubstrByWidth(Tr.t("fill.title", id), (int) ((BROWSE_X - 16) / 0.85f)), x + 10, y + 8, TQ, 0.85f);
        RegionScreen.text(g, font, size, x + 10, y + 19, MUTED, 0.6f);
        drawList(g, x, y);
        browser.place(x + BROWSE_X, y + BROWSE_Y, BROWSE_COLS, BROWSE_ROWS);
        browser.draw(g, mouseX, mouseY, b -> rows.stream().anyMatch(r -> r.block.equals(b)), new BlockBrowser.HitSink() {
            @Override
            public void add(int hx, int hy, int w, int h, Runnable action) {
                hits.add(new Hit(hx, hy, w, h, action));
            }

            @Override
            public void chip(GuiGraphics gg, int cx, int cy, int w, int h, String label, Runnable action) {
                MineFillScreen.this.chip(gg, cx, cy, w, h, label, false, MUTED, action);
            }
        });
        String problem = problem();
        chip(g, x + W - 128, y + H - 20, 56, 14, Tr.t("cancel"), false, MUTED, this::onClose);
        chip(g, x + W - 66, y + H - 20, 56, 14, Tr.t("fill.save"), problem == null, TQ, problem == null ? this::save : null);
        if (!status.isEmpty()) {
            RegionScreen.text(g, font, font.plainSubstrByWidth(status, (int) ((W - 140) / 0.6f)), x + 10, y + H - 15, GOLD, 0.6f);
        }
        super.draw(g, mouseX, mouseY, partial);
    }

    private void drawList(GuiGraphics g, int x, int y) {
        int lx = x + LIST_X, ly = y + LIST_Y;
        RegionScreen.text(g, font, Tr.t("fill.block"), lx + 16, y + 30, MUTED, 0.5f);
        RegionScreen.text(g, font, "%", lx + 112, y + 30, MUTED, 0.5f);
        RegionScreen.text(g, font, Tr.t("fill.rest"), lx + 145, y + 30, MUTED, 0.5f);
        g.fill(lx, ly - 1, lx + LIST_W, ly + ROWS * ROW, RegionScreen.INSET);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - ROWS)));
        int others = others();
        for (int i = 0; i < ROWS; i++) {
            int index = scroll + i, ry = ly + i * ROW;
            EditBox box = boxes[i];
            if (index >= rows.size()) {
                box.visible = false;
                boundTo[i] = -1;
                continue;
            }
            Row r = rows.get(index);
            boolean isRest = r.block.equals(rest);
            g.pose().pushPose();
            g.pose().translate(lx + 1, ry + 1, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.renderItem(r.icon, 0, 0);
            g.pose().popPose();
            String name = r.icon.isEmpty() ? r.block.toString() : r.icon.getHoverName().getString();
            RegionScreen.text(g, font, font.plainSubstrByWidth(name, (int) (82 / 0.6f)), lx + 16, ry + 4, TEXT, 0.6f);
            if (boundTo[i] != index || isRest) { // a box shows its row; REST shows what is left and is not typed in
                boundTo[i] = -1;
                box.setValue(isRest ? (others < 0 ? "?" : percent(Math.max(0, MineData.WHOLE - others))) : r.text);
                boundTo[i] = index;
            }
            box.setEditable(!isRest);
            box.setTextColor(isRest ? GOLD : tenths(r.text) < 0 ? BAD : TEXT);
            box.setX(lx + 100);
            box.setY(ry + 1);
            box.visible = true;
            RegionScreen.text(g, font, "%", lx + 132, ry + 4, MUTED, 0.6f);
            chip(g, lx + 140, ry + 1, 24, 11, Tr.t("fill.rest"), isRest, GOLD, () -> rest = isRest ? null : r.block);
            chip(g, lx + 166, ry + 1, 10, 11, "x", false, BAD, () -> remove(r));
        }
        if (rows.size() > ROWS) {
            RegionScreen.text(g, font, Tr.t("fill.more", rows.size() - ROWS), lx + 2, ly + ROWS * ROW + 1, MUTED, 0.5f);
        }
        int ty = y + 182;
        g.fill(lx, ty - 4, lx + LIST_W, ty - 3, RegionScreen.LINE);
        String problem = problem();
        int total = others < 0 ? -1 : rest != null ? Math.max(others, MineData.WHOLE) : others;
        String label = Tr.t("fill.total") + "  ";
        RegionScreen.text(g, font, label, lx, ty, MUTED, 0.75f);
        RegionScreen.text(g, font, total < 0 ? "?" : percent(total) + "%", lx + (int) (font.width(label) * 0.75f), ty, problem == null ? TQ : BAD, 0.75f);
        String hint = problem != null ? problem : rest != null ? Tr.t("fill.rest_hint") : Tr.t("fill.biggest_hint");
        int hy = ty + 11;
        for (var line : font.split(Component.literal(hint), (int) (LIST_W / 0.6f))) {
            g.pose().pushPose();
            g.pose().translate(lx, hy, 0);
            g.pose().scale(0.6f, 0.6f, 1f);
            g.drawString(font, line, 0, 0, problem == null ? MUTED : BAD, false);
            g.pose().popPose();
            hy += 6;
        }
    }

    private void remove(Row r) {
        rows.remove(r);
        if (r.block.equals(rest)) {
            rest = null;
        }
        java.util.Arrays.fill(boundTo, -1);
    }

    /** A browser block clicked: add it with no share yet; the first block of an empty mine fills the rest. */
    private void add(ItemStack s) {
        ResourceLocation b = BlockBrowser.blockOf(s);
        if (b == null) {
            return;
        }
        if (rows.stream().anyMatch(r -> r.block.equals(b))) {
            status = Tr.t("fill.already", s.getHoverName().getString());
            return;
        }
        if (rows.size() >= MineData.MAX_ROWS) {
            status = Tr.t("fill.full", MineData.MAX_ROWS);
            return;
        }
        rows.add(new Row(b, ""));
        if (rows.size() == 1) {
            rest = b;
        }
        scroll = Math.max(0, rows.size() - ROWS); // show the new row
        java.util.Arrays.fill(boundTo, -1);
        status = rest != null && rest.equals(b) ? Tr.t("fill.first_is_rest") : Tr.t("fill.type_share", s.getHoverName().getString());
    }

    private void chip(GuiGraphics g, int x, int y, int w, int h, String label, boolean on, int colour, @Nullable Runnable action) {
        g.fill(x, y, x + w, y + h, on ? (colour & 0xFFFFFF) | 0x22000000 : RegionScreen.INSET);
        BlockBrowser.frame(g, x, y, w, h, on ? colour : RegionScreen.LINE);
        float scale = h >= 14 ? 0.75f : 0.5f;
        RegionScreen.text(g, font, label, x + (w - (int) (font.width(label) * scale)) / 2, y + (int) ((h - 8 * scale) / 2) + 1, on ? colour : MUTED, scale);
        if (action != null) {
            hits.add(new Hit(x, y, w, h, action));
        }
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        for (Hit h : List.copyOf(hits)) {
            if (h.contains(mx, my)) {
                h.action().run();
                return true;
            }
        }
        ItemStack s = browser.at(mx, my);
        if (s != null) {
            add(s);
            return true;
        }
        return super.onClick(mx, my, button);
    }

    @Override
    protected boolean onScroll(double mx, double my, double delta) {
        int lx = left() + LIST_X, ly = top() + LIST_Y;
        if (mx >= lx && mx < lx + LIST_W && my >= ly && my < ly + ROWS * ROW) {
            scroll -= (int) Math.signum(delta);
            java.util.Arrays.fill(boundTo, -1);
            return true;
        }
        return browser.scroll(mx, my, delta) || super.onScroll(mx, my, delta);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(back);
        }
    }
}
