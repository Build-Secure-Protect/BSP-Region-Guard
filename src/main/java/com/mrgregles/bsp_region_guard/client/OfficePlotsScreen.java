package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.OfficeEditPacket;
import com.mrgregles.bsp_region_guard.network.OfficeStatePacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OFFICE PLOTS (owner decisions D140 to D148; mock-ups A and C together): which plots one Land Office lists. EVERY PLOT
 * lists everything for sale in the world, as before; CHOSEN lists the plots ticked here and every plot in the groups
 * ticked here. The PLOTS tab ticks plots and puts a plot in a group, the GROUPS tab ticks and makes groups, and the
 * map shows what the office lists (teal: ticked, gold: in a ticked group). Opened by an admin's sneak + right-click of
 * the Region Wand on a Land Office, or the Land Office's ADMIN key; every change goes to the server at once.
 */
public class OfficePlotsScreen extends HoloScreen {
    private static final int W = RegionScreen.W, H = RegionScreen.H;
    private static final int BG = RegionScreen.BG, INSET = RegionScreen.INSET, LINE = RegionScreen.LINE, TQ = RegionScreen.TQ,
            GOLD = RegionScreen.GOLD, TEXT = RegionScreen.TEXT, MUTED = RegionScreen.MUTED;
    private static final int HINT = 0xFF6F7788, AMBER = 0xFFFFB84D, GREY = 0xFF4A5060;
    private static final int LIST_X = 8, LIST_Y = 66, LIST_W = 194, ROW = 12, ROWS = 9;
    private static final int MAP_X = 208, MAP_Y = 52, MAP_W = 204, MAP_H = 160;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final Map<BlockPos, Screen> OPEN_OVER = new HashMap<>();

    @Nullable
    private final Screen parent;
    private OfficeStatePacket state;
    private final List<Hit> hits = new ArrayList<>();
    private boolean groupsTab;
    private int scroll;
    @Nullable
    private String picked;
    private EditBox search, newGroup;

    /** Asks the server for an office's state; the screen opens when it answers (admins only). */
    public static void request(BlockPos office, @Nullable Screen parent) {
        OPEN_OVER.put(office, parent);
        RegionNetwork.CHANNEL.sendToServer(OfficeEditPacket.open(office));
    }

    public static void receive(OfficeStatePacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof OfficePlotsScreen s && s.state.office().equals(msg.office())) {
            s.state = msg;
            return;
        }
        if (mc.player == null || mc.screen instanceof ChatScreen) {
            return;
        }
        Screen parent = OPEN_OVER.containsKey(msg.office()) ? OPEN_OVER.remove(msg.office()) : mc.screen;
        mc.setScreen(new OfficePlotsScreen(parent, msg));
    }

    OfficePlotsScreen(@Nullable Screen parent, OfficeStatePacket state) {
        super(Component.literal(Tr.t("office_plots")), W, H);
        this.parent = parent;
        this.state = state;
    }

    // ------------------------------------------------------------------ data

    private void send(String action, String a, String b) {
        RegionNetwork.CHANNEL.sendToServer(new OfficeEditPacket(state.office(), action, a, b));
    }

    private boolean ticked(OfficeStatePacket.Plot p) {
        return state.tickedPlots().contains(p.id());
    }

    private boolean inTickedGroup(OfficeStatePacket.Plot p) {
        return !p.group().isEmpty() && state.tickedGroups().contains(p.group());
    }

    private boolean listed(OfficeStatePacket.Plot p) {
        return !state.chosenOnly() || ticked(p) || inTickedGroup(p);
    }

    private List<OfficeStatePacket.Plot> shownPlots() {
        String q = search == null ? "" : search.getValue();
        return state.plots().stream().filter(p -> com.mrgregles.bsp_region_guard.claim.OfficeLists.matches(p.id(), p.name(), p.group(), q))
                .collect(Collectors.toList());
    }

    @Nullable
    private OfficeStatePacket.Plot plot(String id) {
        for (var p : state.plots()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void layout() {
        int x = left(), y = top();
        search = new EditBox(font, x + LIST_X + 4, y + 55, LIST_W - 8, 10, Component.literal(Tr.t("office_search")));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setHint(Component.literal(Tr.t("office_search")).withStyle(s -> s.withColor(0x9AA3B5)));
        search.setMaxLength(64); // sent with TICK ALL SHOWN; the packet holds 128
        search.setResponder(v -> scroll = 0);
        addRenderableWidget(search);
        newGroup = new EditBox(font, x + LIST_X + 4, y + 199, 120, 10, Component.literal(Tr.t("office_new_group")));
        newGroup.setBordered(false);
        newGroup.setTextColor(TEXT);
        newGroup.setMaxLength(32);
        newGroup.setHint(Component.literal(Tr.t("office_new_group")).withStyle(s -> s.withColor(0x9AA3B5)));
        addRenderableWidget(newGroup);
        showTab();
    }

    private void showTab() {
        search.visible = !groupsTab;
        newGroup.visible = groupsTab;
        scroll = 0;
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
        text(g, Tr.t("office_plots"), x + 8, y + 8, TQ, 1f);
        BlockPos o = state.office();
        text(g, font.plainSubstrByWidth(Tr.t("office_at", o.getX(), o.getY(), o.getZ(), state.world()), 400), x + 90, y + 10, MUTED, 0.6f);
        chip(g, x + W - 52, y + 6, 44, 12, Tr.t("done"), false, TEXT, 0.75f, this::onClose);

        // mode
        text(g, Tr.t("office_lists"), x + 8, y + 26, MUTED, 0.6f);
        chip(g, x + 40, y + 23, 62, 12, Tr.t("office_every"), !state.chosenOnly(), TQ, 0.6f, () -> send(OfficeEditPacket.MODE, "0", ""));
        chip(g, x + 104, y + 23, 62, 12, Tr.t("office_chosen"), state.chosenOnly(), TQ, 0.6f, () -> send(OfficeEditPacket.MODE, "1", ""));
        long listed = state.plots().stream().filter(this::listed).count();
        text(g, Tr.t("office_listed", listed, state.plots().size()), x + 172, y + 26, state.chosenOnly() ? TEXT : HINT, 0.6f);

        // tabs
        chip(g, x + 8, y + 38, 60, 11, Tr.t("office_tab_plots"), !groupsTab, TEXT, 0.6f, () -> {
            groupsTab = false;
            showTab();
        });
        chip(g, x + 70, y + 38, 60, 11, Tr.t("office_tab_groups"), groupsTab, TEXT, 0.6f, () -> {
            groupsTab = true;
            showTab();
        });

        if (groupsTab) {
            drawGroups(g, x, y, mouseX, mouseY);
        } else {
            drawPlots(g, x, y, mouseX, mouseY);
        }
        drawMap(g, x, y, mouseX, mouseY);
        String line = state.message().isEmpty() ? (state.chosenOnly() ? Tr.t("office_hint_chosen") : Tr.t("office_hint_every")) : state.message();
        text(g, font.plainSubstrByWidth(line, 560), x + 8, y + H - 12, state.message().isEmpty() ? HINT : GOLD, 0.6f);
        super.draw(g, mouseX, mouseY, partial);
    }

    private void drawPlots(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        inset(g, x + LIST_X, y + 52, LIST_W, 14);
        List<OfficeStatePacket.Plot> shown = shownPlots();
        int rows = picked == null ? ROWS : ROWS - 4;
        inset(g, x + LIST_X, y + LIST_Y, LIST_W, rows * ROW + 2);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - rows)));
        if (shown.isEmpty()) {
            text(g, Tr.t("office_no_plots"), x + LIST_X + 4, y + LIST_Y + 4, HINT, 0.6f);
        }
        for (int i = 0; i < rows && scroll + i < shown.size(); i++) {
            OfficeStatePacket.Plot p = shown.get(scroll + i);
            int ry = y + LIST_Y + 1 + i * ROW;
            boolean on = ticked(p);
            if (p.id().equals(picked)) {
                g.fill(x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW, 0x2219D3B0);
            }
            box(g, x + LIST_X + 3, ry + 2, on);
            hits.add(new Hit(x + LIST_X + 1, ry, 12, ROW, () -> send(on ? OfficeEditPacket.UNTICK : OfficeEditPacket.TICK, p.id(), "")));
            text(g, font.plainSubstrByWidth(p.id(), 180), x + LIST_X + 16, ry + 3, on || inTickedGroup(p) ? TEXT : MUTED, 0.6f);
            String tag = p.open() ? Tr.t("office_open") : (p.maxX() - p.minX() + 1) + "x" + (p.maxZ() - p.minZ() + 1);
            if (!p.group().isEmpty()) {
                tag = p.group() + "  " + tag;
            }
            int tw = (int) (font.width(tag) * 0.5f);
            text(g, tag, x + LIST_X + LIST_W - 5 - tw, ry + 4, inTickedGroup(p) ? GOLD : HINT, 0.5f);
            hits.add(new Hit(x + LIST_X + 14, ry, LIST_W - 15, ROW, () -> picked = p.id().equals(picked) ? null : p.id()));
        }
        int by = y + LIST_Y + rows * ROW + 6;
        chip(g, x + LIST_X, by, 70, 11, Tr.t("office_tick_shown"), false, TQ, 0.55f, () -> send(OfficeEditPacket.TICK_MATCHING, search.getValue(), ""));
        chip(g, x + LIST_X + 72, by, 40, 11, Tr.t("office_clear"), false, MUTED, 0.55f, () -> send(OfficeEditPacket.CLEAR, "", ""));
        OfficeStatePacket.Plot p = picked == null ? null : plot(picked);
        if (p == null) {
            picked = null;
            return;
        }
        // the picked plot's group
        int gy = by + 15;
        text(g, font.plainSubstrByWidth(Tr.t("office_group_of", p.id()), 320), x + LIST_X, gy, MUTED, 0.55f);
        // chips wrap onto as many lines as fit above the message line; the rest are counted (search narrows them)
        List<String> labels = new ArrayList<>();
        labels.add("");
        labels.addAll(state.groups());
        int cx = x + LIST_X, right = x + LIST_X + LIST_W, bottom = y + H - 16;
        gy += 9;
        int hidden = 0;
        for (String grp : labels) {
            String label = grp.isEmpty() ? Tr.t("office_no_group") : grp;
            int w = Math.min(LIST_W, (int) (font.width(label) * 0.55f) + 8);
            if (cx + w > right) {
                cx = x + LIST_X;
                gy += 13;
            }
            if (gy + 11 > bottom) {
                hidden++;
                continue;
            }
            chip(g, cx, gy, w, 11, font.plainSubstrByWidth(label, (int) ((w - 6) / 0.55f)), p.group().equals(grp), GOLD, 0.55f,
                    () -> send(OfficeEditPacket.SET_GROUP, p.id(), grp));
            cx += w + 2;
        }
        if (hidden > 0) {
            text(g, Tr.n("office_more_groups", hidden), x + LIST_X + LIST_W - 60, bottom - 8, HINT, 0.5f);
        }
    }

    private void drawGroups(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        Map<String, Integer> counts = new HashMap<>();
        for (var p : state.plots()) {
            if (!p.group().isEmpty()) {
                counts.merge(p.group(), 1, Integer::sum);
            }
        }
        inset(g, x + LIST_X, y + 52, LIST_W, ROWS * ROW + 16);
        List<String> groups = state.groups();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, groups.size() - ROWS - 1)));
        if (groups.isEmpty()) {
            text(g, Tr.t("office_no_groups"), x + LIST_X + 4, y + 56, HINT, 0.6f);
        }
        for (int i = 0; i < ROWS + 1 && scroll + i < groups.size(); i++) {
            String grp = groups.get(scroll + i);
            int ry = y + 54 + i * ROW;
            boolean on = state.tickedGroups().contains(grp);
            box(g, x + LIST_X + 3, ry + 2, on);
            hits.add(new Hit(x + LIST_X + 1, ry, LIST_W - 2, ROW, () -> send(on ? OfficeEditPacket.UNTICK_GROUP : OfficeEditPacket.TICK_GROUP, grp, "")));
            text(g, font.plainSubstrByWidth(grp, 200), x + LIST_X + 16, ry + 3, on ? GOLD : MUTED, 0.6f);
            String n = Tr.n("office_group_count", counts.getOrDefault(grp, 0));
            text(g, n, x + LIST_X + LIST_W - 5 - (int) (font.width(n) * 0.5f), ry + 4, HINT, 0.5f);
        }
        inset(g, x + LIST_X, y + 196, 128, 14);
        chip(g, x + LIST_X + 132, y + 197, 62, 12, Tr.t("office_add_group"), false, TQ, 0.55f, () -> {
            if (!newGroup.getValue().isBlank()) {
                send(OfficeEditPacket.NEW_GROUP, newGroup.getValue(), "");
                newGroup.setValue("");
            }
        });
    }

    private void drawMap(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int mx0 = x + MAP_X, my0 = y + MAP_Y;
        inset(g, mx0, my0, MAP_W, MAP_H);
        BlockPos o = state.office();
        int minX = o.getX(), minZ = o.getZ(), maxX = o.getX(), maxZ = o.getZ();
        for (var p : state.plots()) {
            minX = Math.min(minX, p.minX());
            minZ = Math.min(minZ, p.minZ());
            maxX = Math.max(maxX, p.maxX());
            maxZ = Math.max(maxZ, p.maxZ());
        }
        float scale = Math.min((MAP_W - 12) / (float) Math.max(16, maxX - minX + 1), (MAP_H - 12) / (float) Math.max(16, maxZ - minZ + 1));
        int ox = mx0 + 6, oz = my0 + 6;
        OfficeStatePacket.Plot over = null;
        for (var p : state.plots()) {
            int px1 = ox + (int) ((p.minX() - minX) * scale), pz1 = oz + (int) ((p.minZ() - minZ) * scale);
            int px2 = Math.max(px1 + 2, ox + (int) ((p.maxX() + 1 - minX) * scale)), pz2 = Math.max(pz1 + 2, oz + (int) ((p.maxZ() + 1 - minZ) * scale));
            int colour = !state.chosenOnly() ? TQ : ticked(p) ? TQ : inTickedGroup(p) ? GOLD : GREY;
            boolean dim = !state.chosenOnly();
            g.fill(px1, pz1, px2, pz2, (colour & 0xFFFFFF) | (dim ? 0x22000000 : 0x44000000));
            frame(g, px1, pz1, px2 - px1, pz2 - pz1, p.id().equals(picked) ? 0xFFFFFFFF : colour);
            if (mouseX >= px1 && mouseX < px2 && mouseY >= pz1 && mouseY < pz2) {
                over = p;
            }
            boolean on = ticked(p);
            hits.add(new Hit(px1, pz1, px2 - px1, pz2 - pz1, () -> {
                picked = p.id();
                send(on ? OfficeEditPacket.UNTICK : OfficeEditPacket.TICK, p.id(), "");
            }));
        }
        int ofx = ox + (int) ((o.getX() - minX) * scale), ofz = oz + (int) ((o.getZ() - minZ) * scale);
        g.fill(ofx - 2, ofz - 2, ofx + 3, ofz + 3, AMBER);
        text(g, Tr.t("office_map_hint"), mx0, my0 + MAP_H + 3, HINT, 0.5f);
        if (over != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(over.name().isEmpty() ? over.id() : over.name()));
            tip.add(Component.literal(over.group().isEmpty() ? Tr.t("office_no_group") : Tr.t("office_in_group", over.group())).withStyle(s -> s.withColor(0x9AA3B5)));
            int listedColour = listed(over) ? 0x19D3B0 : 0x6F7788;
            tip.add(Component.literal(listed(over) ? Tr.t("office_is_listed") : Tr.t("office_not_listed")).withStyle(s -> s.withColor(listedColour)));
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
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
        scroll -= (int) Math.signum(delta);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            onClose();
            return true;
        }
        for (EditBox box : new EditBox[]{search, newGroup}) {
            if (box.isFocused() && box.visible) {
                if (keyCode == 257 && box == newGroup && !newGroup.getValue().isBlank()) { // Enter adds the group
                    send(OfficeEditPacket.NEW_GROUP, newGroup.getValue(), "");
                    newGroup.setValue("");
                    return true;
                }
                return box.keyPressed(keyCode, scanCode, modifiers) || box.canConsumeInput() || super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ drawing helpers (as the other Region Guard screens')

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

    private void box(GuiGraphics g, int x, int y, boolean on) {
        g.fill(x, y, x + 8, y + 8, on ? 0x3319D3B0 : INSET);
        frame(g, x, y, 8, 8, on ? TQ : LINE);
        if (on) {
            g.fill(x + 2, y + 2, x + 6, y + 6, TQ);
        }
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
