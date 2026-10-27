package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.network.RegionActionPacket;
import com.mrgregles.bsp_region_guard.network.RegionDetailPacket;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import com.mrgregles.bsp_region_guard.region.Region;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The admin screen (owner decision D15): the region list on the left, the selected region's tabs on the right, the
 * map as the first tab. Drawn in BSP Core's Holo HUD style with fills. The server owns every value: each edit is sent
 * as a {@link RegionActionPacket} and the screen redraws from the packets that come back.
 */
public class RegionScreen extends HoloScreen {
    /** The smallest size, in screen units; the screen fills the window beyond it (D80). */
    static final int W = 420, H = 236;
    static final int BG = 0xF010151C, INSET = 0xFF0C0E12, LINE = 0xFF2A2F3A, TQ = 0xFF19D3B0, VIO = 0xFFB58CFF, GOLD = 0xFFFFD23A,
            BAD = 0xFFFF6B5C, TEXT = 0xFFE6EAF2, MUTED = 0xFF9AA3B5;
    private static final String[] TABS = {"MAP", "FLAGS", "ACCESS", "RANKS", "SALE", "MINE", "TELEPORT", "INFO", "BORDER", "BANNED"};
    /** BANNED opens the banned blocks editor (D56) rather than drawing in the pane. */
    private static final int BANNED_TAB = 9;
    /** The BORDER tab is the last one and only shown for the world default. */
    private static final int BORDER_TAB = 8;
    private static final String[] FILTERS = {"ALL", "PROT", "CLAIM", "MINE", "SALE"};
    private static final int LIST_X = 8, LIST_Y = 52, LIST_W = 130, LIST_H = 144, ROW = 12;
    private static final int PANE_X = 146, PANE_W = 266, CONTENT_Y = 62, CONTENT_H = 134;
    /** Drawn at 80% of {@code ui.scale} (D80). */
    private static final float SIZE = 0.8f;
    /** The screen's size and its parts' sizes now: the minimum ({@link #W} by {@link #H}) grown to fill the window. */
    private int winW = W, winH = H, listH = LIST_H, paneW = PANE_W, contentH = CONTENT_H;

    /** A clickable rectangle registered while drawing and consumed by the next click. */
    /** How long the server's answer stays in the bottom line, and how long a clicked button stays lit (B14). */
    private static final long ANSWER_MS = 6000, PRESS_MS = 250;
    @Nullable
    private String answer;
    private boolean answerOk;
    private long answerAt, pressedAt;
    @Nullable
    private Hit pressed;

    void onAnswer(String text, boolean ok) {
        answer = text;
        answerOk = ok;
        answerAt = net.minecraft.Util.getMillis();
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private enum Input { NONE, NEW_ID, FLAG_VALUE, PLAYER, RANK, PARENT, NAME, TELEPORT_MESSAGE, DATA, MINE_BLOCK, MINE_INTERVAL, MINE_SHELL, TRIGGER, BORDER, WIPE_CODE }

    private String triggerCause = "enter";
    /** The edge whose "leads to" list is open on the BORDER tab, or null. */
    @Nullable
    private String pickSide;
    /** The claim wipe (D45): the last state from the server, when it came, and whether the right pane shows it. */
    @Nullable
    private com.mrgregles.bsp_region_guard.network.ClaimWipePacket wipe;
    private long wipeReceived;
    private boolean wipeOpen;
    /** While looking at another server: draw this server's regions in grey underneath. */
    private boolean overlayHere;

    @Nullable
    private String editingData;

    private final List<Hit> hits = new ArrayList<>();
    @Nullable
    private String selected;
    private int tab, filter, listScroll, flagScroll, rankScroll;
    private boolean onlySet;
    private boolean confirmDelete;
    private long lastMapClick;
    @Nullable
    private String lastMapClickId;
    private boolean zoomedByHand;
    private final RegionMap map = RegionMap.create();
    private EditBox search;
    private EditBox input;
    /** The INFO tab's six corner fields (D75, D80): from x y z, to x y z. */
    private final EditBox[] sizeBox = new EditBox[6];
    /** The region and box the corner fields were last filled from, so a change from the server refills them. */
    @Nullable
    private String sizeFor;
    @Nullable
    private Cuboid sizeFrom;
    private Input inputMode = Input.NONE;
    @Nullable
    private String editingFlag;

    /** Opened from the region map's card on {@code tab}: Esc brings the map back (D86). */
    private final boolean returnToMap;

    public RegionScreen(@Nullable String select) {
        this(select, 1, false);
    }

    public RegionScreen(@Nullable String select, int tab, boolean returnToMap) {
        super(Component.literal(Tr.t("regions")), W, H);
        this.selected = select;
        this.tab = tab;
        this.returnToMap = returnToMap;
    }

    // ------------------------------------------------------------------ data

    @Nullable
    private RegionListPacket list() {
        return RegionClient.list();
    }

    @Nullable
    private RegionDetailPacket detail() {
        RegionDetailPacket d = RegionClient.detail();
        return d != null && d.id().equals(selected) ? d : null;
    }

    /** True while the screen shows another server's regions (D44): read-only. */
    private boolean remote() {
        RegionListPacket l = list();
        return l != null && l.remote();
    }

    /** The server list's next (+1) or previous (-1) entry from the one shown, wrapping. */
    private void switchServer(int step) {
        RegionListPacket l = list();
        if (l == null || l.servers().size() < 2) {
            return;
        }
        String now = l.remote() ? l.viewing() : l.servers().get(0);
        int i = Math.max(0, l.servers().indexOf(now));
        String next = l.servers().get(Math.floorMod(i + step, l.servers().size()));
        closeInput();
        pickSide = null;
        send(new RegionActionPacket(RegionActionPacket.VIEW_SERVER, selected == null ? "" : selected, next.equals(l.servers().get(0)) ? "" : next, ""));
    }

    void onWipe(com.mrgregles.bsp_region_guard.network.ClaimWipePacket msg) {
        wipe = msg;
        wipeReceived = System.currentTimeMillis();
        wipeOpen = true;
        if (!msg.code().isEmpty()) {
            openInput(Input.WIPE_CODE, "", Tr.t("wipe_code_hint"));
        } else if (inputMode == Input.WIPE_CODE) {
            closeInput();
        }
    }

    private void closeWipe() {
        wipeOpen = false;
        if (inputMode == Input.WIPE_CODE) {
            closeInput();
        }
    }

    void onList(RegionListPacket msg) {
        if (msg.remote() && tab != 0 && tab != 1 && tab != 7) {
            tab = 0; // only MAP, FLAGS and INFO while looking at another server
        }
        if (msg.select() != null) {
            selected = msg.select();
        }
        if (selected != null && msg.find(selected) == null) {
            selected = msg.regions().isEmpty() ? null : msg.regions().get(0).id();
            if (selected != null) {
                send(RegionActionPacket.of(RegionActionPacket.SELECT, selected));
            }
        }
        confirmDelete = false;
    }

    void onDetail(RegionDetailPacket msg) {
        if (msg.id().equals(selected)) {
            frameMap();
        }
    }

    private void select(String id) {
        if (id.contains("/")) {
            return; // another server's region: shown on the map and in the list, not editable here
        }
        if (!id.equals(selected)) {
            selected = id;
            confirmDelete = false;
            zoomedByHand = false;
            editingFlag = null;
            closeInput();
            send(RegionActionPacket.of(RegionActionPacket.SELECT, id));
            frameMap();
        }
    }

    private void frameMap() {
        RegionListPacket l = list();
        RegionListPacket.Summary s = l == null || selected == null ? null : l.find(selected);
        BlockPos player = minecraft != null && minecraft.player != null ? minecraft.player.blockPosition() : BlockPos.ZERO;
        map.frame(s == null ? null : s.bounds(), player, zoomedByHand);
    }

    private static void send(RegionActionPacket packet) {
        RegionNetwork.CHANNEL.sendToServer(packet);
    }

    private void act(String action, String a) {
        if (selected != null) {
            send(RegionActionPacket.of(action, selected, a));
        }
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected float sizeFactor() {
        return SIZE;
    }

    @Override
    protected void layout() {
        winW = Math.max(W, width - 8);
        winH = Math.max(H, height - 8);
        listH = winH - (H - LIST_H);
        paneW = winW - (W - PANE_W);
        contentH = winH - (H - CONTENT_H);
        map.size(paneW - 4, contentH - 16);
        int x = left(), y = top();
        search = new EditBox(font, x + LIST_X + 3, y + 24, LIST_W - 6, 10, Component.literal(Tr.t("search_label")));
        search.setBordered(false);
        search.setMaxLength(64);
        search.setHint(Component.literal(Tr.t("search_regions")).withStyle(s -> s.withColor(0x9AA3B5)));
        search.setResponder(t -> listScroll = 0);
        addRenderableWidget(search);
        input = new EditBox(font, x + PANE_X + 7, y + CONTENT_Y + contentH - 15, paneW - 80, 10, Component.literal(Tr.t("value_label")));
        input.setBordered(false);
        input.setMaxLength(1000);
        input.setVisible(false);
        addRenderableWidget(input);
        String[] labels = {Tr.t("size_field_0"), Tr.t("size_field_1"), Tr.t("size_field_2"), Tr.t("size_field_3"), Tr.t("size_field_4"), Tr.t("size_field_5")};
        for (int i = 0; i < sizeBox.length; i++) {
            EditBox b = new EditBox(font, 0, 0, 34, 10, Component.literal(labels[i]));
            b.setBordered(false);
            b.setMaxLength(9);
            b.setFilter(t -> t.matches("-?\\d*"));
            b.setVisible(false);
            sizeBox[i] = addRenderableWidget(b);
        }
        sizeFor = null;
        frameMap();
    }

    private int left() {
        return (width - winW) / 2;
    }

    private int top() {
        return (height - winH) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return inputMode == Input.NONE;
    }

    private void openInput(Input mode, String current, String hint) {
        inputMode = mode;
        input.setVisible(true);
        input.setValue(current);
        input.setHint(Component.literal(hint).withStyle(s -> s.withColor(0x9AA3B5)));
        setFocused(input);
        input.setFocused(true);
    }

    private void closeInput() {
        inputMode = Input.NONE;
        input.setVisible(false);
        input.setFocused(false);
        input.setValue("");
        editingFlag = null;
    }

    private void submitInput() {
        String value = input.getValue().trim();
        switch (inputMode) {
            case NEW_ID -> {
                if (!value.isEmpty()) {
                    send(new RegionActionPacket(RegionActionPacket.CREATE, "", value, ""));
                }
            }
            case FLAG_VALUE -> {
                if (editingFlag != null) {
                    send(new RegionActionPacket(RegionActionPacket.FLAG, selected == null ? "" : selected, editingFlag, value));
                }
            }
            case PLAYER -> {
                // handled by the +OWNER / +MEMBER chips, which read the box
            }
            case RANK -> {
                if (!value.isEmpty()) {
                    act(RegionActionPacket.ADD_RANK, value);
                }
            }
            case PARENT -> act(RegionActionPacket.PARENT, value);
            case NAME -> act(RegionActionPacket.RENAME, value);
            case TELEPORT_MESSAGE -> {
                if (selected != null) {
                    send(new RegionActionPacket(RegionActionPacket.FLAG, selected, "teleport-message", value));
                }
            }
            case DATA -> {
                if (selected != null && editingData != null) {
                    send(new RegionActionPacket(RegionActionPacket.SET_DATA, selected, editingData, value.isEmpty() ? "0" : value));
                }
            }
            case MINE_BLOCK -> {
                String[] parts = value.split("\\s+");
                if (parts.length >= 1 && !parts[0].isEmpty() && selected != null) {
                    send(new RegionActionPacket(RegionActionPacket.MINE_ADD, selected, parts[0], parts.length > 1 ? parts[1] : "10"));
                }
            }
            case MINE_INTERVAL -> act(RegionActionPacket.MINE_INTERVAL, value.isEmpty() ? "0" : value);
            case TRIGGER -> {
                if (selected != null && !value.isEmpty()) {
                    send(new RegionActionPacket(RegionActionPacket.TRIGGER_ADD, selected, triggerCause, value));
                }
            }
            case MINE_SHELL -> act(RegionActionPacket.MINE_SHELL, value.isEmpty() ? "5" : value);
            case WIPE_CODE -> send(new RegionActionPacket(RegionActionPacket.WIPE_CONFIRM, "", value, ""));
            case BORDER -> {
                String[] parts = value.split("\\s+");
                if (value.equalsIgnoreCase("off")) {
                    act(RegionActionPacket.BORDER_OFF, "");
                } else if (!value.isEmpty() && selected != null) {
                    send(new RegionActionPacket(RegionActionPacket.BORDER_SET, selected, parts[0], parts.length >= 3 ? parts[1] + " " + parts[2] : ""));
                }
            }
            default -> {
            }
        }
        if (inputMode != Input.PLAYER) {
            closeInput();
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    static int typeColour(String type) {
        return switch (type) {
            case "claim" -> VIO & 0xFFFFFF;
            case "mine", "plot_for_sale", "open_for_buying" -> GOLD & 0xFFFFFF;
            case "global", "remote" -> MUTED & 0xFFFFFF;
            default -> TQ & 0xFFFFFF;
        };
    }

    static String typeLetter(String type) {
        return switch (type) {
            case "claim" -> "C";
            case "mine" -> "M";
            case "plot_for_sale", "open_for_buying" -> "S";
            case "global" -> "W";
            case "remote" -> "R";
            default -> "P";
        };
    }

    static void text(GuiGraphics g, Font font, String s, int x, int y, int colour, float scale) {
        if (scale == 1f) {
            g.drawString(font, s, x, y, colour, false);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    /** {@code s} cut to {@code room} screen units at {@code scale}, ending in "..." when cut, so it stays inside its box. */
    private String fit(String s, int room, float scale) {
        int px = (int) (room / scale);
        if (font.width(s) <= px) {
            return s;
        }
        return font.plainSubstrByWidth(s, px - font.width("...")) + "...";
    }

    private void text(GuiGraphics g, String s, int x, int y, int colour, float scale) {
        text(g, font, s, x, y, colour, scale);
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

    /** A bordered label that acts as a button; {@code on} draws it in the accent. */
    private void chip(GuiGraphics g, int x, int y, int w, int h, String label, boolean on, int colour, float scale, @Nullable Runnable action) {
        // B14: a clicked button lights up for a moment, so the click is seen
        boolean flash = pressed != null && pressed.x() == x && pressed.y() == y && pressed.w() == w && pressed.h() == h
                && net.minecraft.Util.getMillis() - pressedAt < PRESS_MS;
        on |= flash;
        g.fill(x, y, x + w, y + h, flash ? (colour & 0xFFFFFF) | 0x66000000 : on ? (colour & 0xFFFFFF) | 0x22000000 : INSET);
        frame(g, x, y, w, h, on ? colour : LINE);
        int tw = (int) (font.width(label) * scale);
        text(g, label, x + (w - tw) / 2, y + (int) ((h - 8 * scale) / 2) + 1, on ? colour : MUTED, scale);
        if (action != null) {
            hits.add(new Hit(x, y, w, h, action));
        }
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int colour, @Nullable Runnable action) {
        chip(g, x, y, w, h, label, true, colour, 0.75f, action);
    }

    /** The inherit / allow / deny switch of a state flag. */
    private void stateSwitch(GuiGraphics g, int x, int y, @Nullable String value, String flagName) {
        String[] labels = {"inh", "allow", "deny"};
        String[] values = {"", "allow", "deny"};
        int[] colours = {TEXT, TQ, BAD};
        for (int i = 0; i < 3; i++) {
            boolean on = i == 0 ? value == null : values[i].equals(value);
            int bx = x + i * 24;
            String v = values[i];
            chip(g, bx, y, 23, 11, Tr.t("state." + labels[i]), on, colours[i], 0.6f,
                    () -> send(new RegionActionPacket(RegionActionPacket.FLAG, selected == null ? "" : selected, flagName, v)));
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partial) {
        hits.clear();
        renderBackground(g);
        int x = left(), y = top();
        g.fill(x, y, x + winW, y + winH, BG);
        boolean ro = remote();
        frame(g, x, y, winW, winH, ro ? GOLD : TQ);
        RegionListPacket l = list();
        String world = l == null ? "" : l.dimension().getPath().toUpperCase(Locale.ROOT);
        text(g, Tr.t("regions_in", world), x + 10, y + 8, ro ? GOLD : TQ, 1f);
        if (l != null && l.servers().size() > 1) {
            // the server switcher (D44): this server first, then the others the shared storage knows
            String shown = ro ? l.viewing() : l.servers().get(0);
            int sx = x + 150;
            text(g, Tr.t("server_label"), sx, y + 9, MUTED, 0.6f);
            sx += (int) (font.width(Tr.t("server_label")) * 0.6f) + 4;
            chip(g, sx, y + 6, 10, 11, "<", false, TEXT, 0.75f, () -> switchServer(-1));
            sx += 13;
            text(g, shown, sx, y + 8, ro ? GOLD : TEXT, 0.85f);
            sx += (int) (font.width(shown) * 0.85f) + 3;
            chip(g, sx, y + 6, 10, 11, ">", false, TEXT, 0.75f, () -> switchServer(1));
            sx += 13;
            text(g, Tr.t("server_index", l.servers().indexOf(shown) + 1, l.servers().size()), sx, y + 9, MUTED, 0.6f);
        }
        String count = ro ? Tr.t("read_only") : l == null ? Tr.t("loading") : Tr.n("region_count", l.regions().size());
        text(g, count, x + winW - 10 - (int) (font.width(count) * 0.75f), y + 9, ro ? GOLD : MUTED, 0.75f);

        for (EditBox b : sizeBox) {
            b.setVisible(false); // the INFO tab shows them again
        }
        drawList(g, x, y, mouseX, mouseY);
        drawPane(g, x, y, mouseX, mouseY);
        super.draw(g, mouseX, mouseY, partial);
    }

    private List<RegionListPacket.Summary> filtered() {
        List<RegionListPacket.Summary> out = new ArrayList<>();
        RegionListPacket l = list();
        if (l == null) {
            return out;
        }
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        for (RegionListPacket.Summary s : l.regions()) {
            boolean typeOk = switch (filter) {
                case 1 -> s.type().equals("protected") || s.type().equals("global");
                case 2 -> s.type().equals("claim");
                case 3 -> s.type().equals("mine");
                case 4 -> s.type().equals("plot_for_sale") || s.type().equals("open_for_buying");
                default -> true;
            };
            if (typeOk && (q.isEmpty() || s.id().contains(q) || s.name().toLowerCase(Locale.ROOT).contains(q))) {
                out.add(s);
            }
        }
        return out;
    }

    private void drawList(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        inset(g, x + LIST_X, y + 22, LIST_W, 14);
        if (inputMode == Input.NEW_ID) {
            search.setVisible(false);
        } else {
            search.setVisible(true);
        }
        int cx = x + LIST_X;
        for (int i = 0; i < FILTERS.length; i++) {
            int fi = i;
            chip(g, cx, y + 39, 25, 10, Tr.t("filter." + FILTERS[i].toLowerCase(Locale.ROOT)), filter == i, TQ, 0.5f, () -> {
                filter = fi;
                if (fi != 2) {
                    closeWipe();
                }
                listScroll = 0;
            });
            cx += 26;
        }
        inset(g, x + LIST_X, y + LIST_Y, LIST_W, listH);
        List<RegionListPacket.Summary> rows = filtered();
        int visible = listH / ROW;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, rows.size() - visible));
        for (int i = 0; i < visible && i + listScroll < rows.size(); i++) {
            RegionListPacket.Summary s = rows.get(i + listScroll);
            int ry = y + LIST_Y + 1 + i * ROW;
            boolean sel = s.id().equals(selected);
            boolean hover = mouseX >= x + LIST_X && mouseX < x + LIST_X + LIST_W && mouseY >= ry && mouseY < ry + ROW;
            if (sel) {
                g.fill(x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW, 0x2219D3B0);
                g.fill(x + LIST_X + 1, ry, x + LIST_X + 3, ry + ROW, TQ);
            } else if (hover) {
                g.fill(x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW, 0x14FFFFFF);
            }
            // the region's name as set on INFO (its id until it is given one); the id stays in the header (B15)
            String label = s.parent() != null ? "  " + s.name() : s.name();
            boolean noCorners = Region.noCorners(s.bounds());
            text(g, font.plainSubstrByWidth(label, noCorners ? 70 : 96), x + LIST_X + 6, ry + 2, sel ? TEXT : MUTED, 1f);
            int colour = 0xFF000000 | typeColour(s.type());
            if (!noCorners) {
                text(g, typeLetter(s.type()), x + LIST_X + LIST_W - 22, ry + 2, colour, 1f);
            }
            if (Region.noCorners(s.bounds())) { // D73: made without corners, applies nowhere yet
                String none = Tr.t("no_corners_short");
                text(g, none, x + LIST_X + LIST_W - 6 - (int) (font.width(none) * 0.5f), ry + 4, GOLD, 0.5f);
            } else {
                String prio = s.bounds() == null ? "-" : String.valueOf(s.priority());
                text(g, prio, x + LIST_X + LIST_W - 6 - (int) (font.width(prio) * 0.75f), ry + 3, MUTED, 0.75f);
            }
            hits.add(new Hit(x + LIST_X, ry, LIST_W, ROW, () -> select(s.id())));
        }
        if (rows.isEmpty()) {
            text(g, list() == null ? Tr.t("waiting_for_the_server") : Tr.t("no_regions_match"), x + LIST_X + 6, y + LIST_Y + 6, MUTED, 0.75f);
        }
        if (rows.size() > visible) {
            int barH = Math.max(6, listH * visible / rows.size());
            int barY = y + LIST_Y + 1 + (listH - 2 - barH) * listScroll / Math.max(1, rows.size() - visible);
            g.fill(x + LIST_X + LIST_W - 3, barY, x + LIST_X + LIST_W - 1, barY + barH, LINE);
        }
        if (remote()) {
            return; // nothing to create while looking at another server
        }
        RegionListPacket all = list();
        long claims = all == null ? 0 : all.regions().stream().filter(r -> r.type().equals("claim")).count();
        if (filter == 2 && claims > 0 && (search == null || search.getValue().isBlank())) {
            // D45: the wipe sits under the list of claims; it always takes every claim in this world, so it only shows unfiltered
            button(g, x + LIST_X, y + winH - 32, LIST_W, 14, Tr.n("wipe_all", claims), BAD, () -> {
                closeInput();
                send(RegionActionPacket.of(RegionActionPacket.WIPE_REQUEST, ""));
            });
            return;
        }
        button(g, x + LIST_X, y + winH - 32, 40, 14, inputMode == Input.NEW_ID ? Tr.t("cancel") : Tr.t("new"), TQ, () -> {
            if (inputMode == Input.NEW_ID) {
                closeInput();
            } else {
                openInput(Input.NEW_ID, "", Tr.t("new_region_id_enter_to_create_from_the_wand_sele"));
            }
        });
        button(g, x + LIST_X + 43, y + winH - 32, 40, 14, Tr.t("wand"), MUTED, () -> send(RegionActionPacket.of(RegionActionPacket.WAND, "")));
        // the region map (D57, D82): every region of this world on a full-window map, opened on the selected one
        button(g, x + LIST_X + 86, y + winH - 32, 44, 14, Tr.t("plot_map_button"), GOLD, this::openMap);
    }

    /** The right pane as the wipe's confirmation (D45): what goes, the code to type back, the backups. */
    private void drawWipe(GuiGraphics g, int x, int y) {
        var w = wipe;
        int px = x + PANE_X;
        RegionListPacket l = list();
        String world = l == null ? "" : l.dimension().getPath().toUpperCase(Locale.ROOT);
        text(g, Tr.t("wipe_title", world), px, y + 22, BAD, 1f);
        if (w.claims() == 0) {
            text(g, Tr.t("wipe_none"), px, y + 36, MUTED, 0.75f);
        } else {
            text(g, Tr.n("wipe_summary", w.claims(), w.owners()), px, y + 36, TEXT, 0.75f);
        }
        text(g, Tr.t("wipe_note"), px, y + 46, MUTED, 0.6f);
        long left = Math.max(0, w.secondsLeft() - (System.currentTimeMillis() - wipeReceived) / 1000);
        if (!w.code().isEmpty() && left > 0) {
            text(g, Tr.t("wipe_type"), px, y + 64, MUTED, 0.75f);
            text(g, w.code(), px + 30, y + 59, GOLD, 2f);
            text(g, Tr.t("wipe_then"), px + 90, y + 64, MUTED, 0.75f);
            text(g, Tr.t("wipe_left", left), px + paneW - 50, y + 64, GOLD, 0.75f);
        } else if (w.claims() > 0) {
            if (inputMode == Input.WIPE_CODE) {
                closeInput(); // the code ran out
            }
            button(g, px, y + 58, 100, 14, Tr.t("wipe_ask"), BAD, () -> send(RegionActionPacket.of(RegionActionPacket.WIPE_REQUEST, "")));
        }
        if (!w.message().isEmpty()) {
            text(g, font.plainSubstrByWidth(w.message(), (int) (paneW / 0.6f)), px, y + 84, TEXT, 0.6f);
        }
        text(g, Tr.t("wipe_backups"), px, y + 98, TQ, 0.6f);
        int ry = y + 108;
        if (w.backups().isEmpty()) {
            text(g, Tr.t("wipe_no_backups"), px, ry, MUTED, 0.6f);
        }
        for (int i = 0; i < w.backups().size() && i < 6; i++) {
            String file = w.backups().get(i);
            text(g, font.plainSubstrByWidth(file, 330), px, ry + 1, TEXT, 0.6f);
            chip(g, px + paneW - 50, ry - 1, 48, 10, Tr.t("restore"), false, TQ, 0.5f,
                    () -> send(new RegionActionPacket(RegionActionPacket.WIPE_RESTORE, "", file, "")));
            ry += 11;
        }
        button(g, px, y + winH - 32, 56, 14, Tr.t("close"), MUTED, this::closeWipe);
        text(g, Tr.t("wipe_footer"), px + 62, y + winH - 28, MUTED, 0.6f);
        drawInput(g, x, y);
    }

    private void drawPane(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (wipeOpen && wipe != null && !remote()) {
            drawWipe(g, x, y);
            return;
        }
        RegionListPacket l = list();
        RegionListPacket.Summary s = l == null || selected == null ? null : l.find(selected);
        RegionDetailPacket d = detail();
        int px = x + PANE_X;
        if (s == null) {
            text(g, Tr.t("pick_a_region_on_the_left_or_select_two_corners"), px, y + 26, MUTED, 0.75f);
            drawInput(g, x, y);
            return;
        }
        text(g, s.name().equals(s.id()) ? s.id() : s.name() + "  (" + s.id() + ")", px, y + 22, TEXT, 1f);
        String meta = Tr.t("type." + s.type()) + (s.bounds() == null ? "" : Tr.t("meta_priority", s.priority())) + (s.parent() == null ? "" : Tr.t("meta_parent", s.parent()));
        text(g, meta, px, y + 33, MUTED, 0.75f);
        String where = s.bounds() == null ? Tr.t("the_whole_world") : Region.noCorners(s.bounds()) ? Tr.t("no_corners_where", Tr.n("owner_count", s.owners()), Tr.n("member_count", s.members())) : Tr.t("where", s.bounds(), s.bounds().sizeX(), s.bounds().sizeY(), s.bounds().sizeZ(),
                Tr.n("owner_count", s.owners()), Tr.n("member_count", s.members()));
        text(g, where, px, y + 41, Region.noCorners(s.bounds()) ? GOLD : MUTED, 0.75f);

        boolean global = s.type().equals("global");
        // the world default can be neither sold nor a mine, so it shows BORDER instead of SALE and MINE (and the row fits)
        if (global ? tab == 4 || tab == 5 : tab == BORDER_TAB) {
            tab = 1;
        }
        if (tab == 0 && !remote()) {
            tab = 1;
        }
        List<Integer> visible = new ArrayList<>();
        int textWidth = 0;
        for (int i = 0; i < TABS.length; i++) {
            if (global ? i == 4 || i == 5 : i == BORDER_TAB) {
                continue;
            }
            if (remote() && i != 0 && i != 1 && i != 7) {
                continue;
            }
            if (i == 0 && !remote()) {
                continue; // this server's map is the region map (D91); the small map stays for other servers' regions
            }
            visible.add(i);
            textWidth += (int) (font.width(Tr.t("tab." + TABS[i].toLowerCase(Locale.ROOT))) * 0.75f);
        }
        // the row must fit the pane: narrow the padding when there are many tabs
        int pad = 8;
        while (pad > 3 && textWidth + visible.size() * (pad + 2) > paneW) {
            pad--;
        }
        int tx = px;
        for (int i : visible) {
            int ti = i;
            String tabName = Tr.t("tab." + TABS[i].toLowerCase(Locale.ROOT));
            int w = (int) (font.width(tabName) * 0.75f) + pad;
            if (ti == BANNED_TAB) {
                chip(g, tx, y + 50, w, 12, tabName, false, BAD, 0.75f, () -> {
                    closeInput();
                    if (minecraft != null) {
                        minecraft.setScreen(new BannedBlocksScreen(this, s.id()));
                    }
                });
                tx += w + 2;
                continue;
            }
            chip(g, tx, y + 50, w, 12, tabName, tab == i, TQ, 0.75f, () -> {
                tab = ti;
                flagScroll = 0;
                rankScroll = 0;
                pickSide = null;
                closeInput();
                if (ti == BORDER_TAB) {
                    act(RegionActionPacket.EDGE_REFRESH, ""); // read the other servers' worlds afresh
                }
            });
            tx += w + 2;
        }
        inset(g, px, y + CONTENT_Y, paneW, contentH);
        switch (tab) {
            case 0 -> drawMap(g, x, y, mouseX, mouseY, s);
            case 1 -> drawFlags(g, x, y, d);
            case 2 -> drawAccess(g, x, y, d);
            case 3 -> drawRanks(g, x, y, d);
            case 4 -> drawSale(g, x, y, s, d);
            case 5 -> drawMine(g, x, y, s, d);
            case 6 -> drawTeleport(g, x, y, d);
            case BORDER_TAB -> drawBorder(g, x, y, d);
            default -> drawInfo(g, x, y, s, d);
        }
        drawInput(g, x, y);

        if (remote()) {
            RegionListPacket l2 = list();
            String home = l2 == null || l2.servers().isEmpty() ? "" : l2.servers().get(0);
            button(g, px, y + winH - 32, 110, 14, Tr.t("back_to", home), TQ, () -> send(new RegionActionPacket(RegionActionPacket.VIEW_SERVER, "", "", "")));
            text(g, Tr.t("read_only_hint"), px + 116, y + winH - 28, MUTED, 0.6f);
            return;
        }
        button(g, px, y + winH - 32, 56, 14, Tr.t("teleport"), TQ, () -> act(RegionActionPacket.TELEPORT, ""));
        button(g, px + 60, y + winH - 32, 50, 14, Tr.t("select"), MUTED, () -> act(RegionActionPacket.SELECT_BOUNDS, ""));
        if (!s.type().equals("global")) {
            button(g, x + winW - 8 - 58, y + winH - 32, 58, 14, confirmDelete ? Tr.t("sure") : Tr.t("delete"), BAD, () -> {
                if (confirmDelete) {
                    act(RegionActionPacket.DELETE, "");
                    confirmDelete = false;
                } else {
                    confirmDelete = true;
                }
            });
        }
        if (answer != null && net.minecraft.Util.getMillis() - answerAt < ANSWER_MS) { // B14: what the server said to the last click
            text(g, fit(answer, paneW, 0.75f), px, y + winH - 13, answerOk ? TQ : BAD, 0.75f);
        } else {
            text(g, Tr.t("changes_save_at_once_esc_closes"), px, y + winH - 13, MUTED, 0.75f);
        }
    }

    private void drawInput(GuiGraphics g, int x, int y) {
        if (inputMode == Input.NONE) {
            input.setVisible(false);
            return;
        }
        int ix = x + PANE_X + 4, iy = y + CONTENT_Y + contentH - 18;
        g.fill(ix, iy, ix + paneW - 8, iy + 16, BG);
        inset(g, ix, iy, paneW - 72, 16);
        input.setX(ix + 4);
        input.setY(iy + 4);
        input.setWidth(paneW - 80);
        input.setVisible(true);
        if (inputMode == Input.PLAYER) {
            button(g, ix + paneW - 70, iy + 1, 30, 14, Tr.t("own"), TQ, () -> addPerson(RegionActionPacket.ADD_OWNER));
            button(g, ix + paneW - 38, iy + 1, 30, 14, Tr.t("mem"), VIO, () -> addPerson(RegionActionPacket.ADD_MEMBER));
        } else {
            boolean wiping = inputMode == Input.WIPE_CODE;
            button(g, ix + paneW - 70, iy + 1, 30, 14, wiping ? Tr.t("wipe_button") : Tr.t("ok"), wiping ? BAD : TQ, this::submitInput);
            button(g, ix + paneW - 38, iy + 1, 30, 14, inputMode == Input.FLAG_VALUE ? Tr.t("clear") : "X", inputMode == Input.FLAG_VALUE ? BAD : MUTED, () -> {
                if (inputMode == Input.FLAG_VALUE && editingFlag != null && selected != null) {
                    send(new RegionActionPacket(RegionActionPacket.FLAG, selected, editingFlag, ""));
                }
                closeInput();
            });
        }
    }

    private void addPerson(String action) {
        String name = input.getValue().trim();
        if (!name.isEmpty()) {
            act(action, name);
            input.setValue("");
        }
    }

    // ------------------------------------------------------------------ tabs

    private void drawMap(GuiGraphics g, int x, int y, int mouseX, int mouseY, RegionListPacket.Summary s) {
        RegionListPacket l = list();
        if (l == null || minecraft == null || minecraft.player == null) {
            return;
        }
        int mx = x + PANE_X + 2, my = y + CONTENT_Y + 2;
        List<RegionListPacket.Summary> drawn = l.regions();
        RegionListPacket own = RegionClient.ownList();
        if (l.remote() && overlayHere && own != null) {
            // this server's regions underneath, grey; their ids carry "here/" so a click on one does nothing
            drawn = new ArrayList<>();
            for (RegionListPacket.Summary o : own.regions()) {
                if (o.bounds() != null) {
                    drawn.add(new RegionListPacket.Summary("here/" + o.id(), o.name(), "remote", o.priority(), null, o.bounds(), 0, 0, 0));
                }
            }
            drawn.addAll(l.regions());
        }
        RegionListPacket.Summary hovered = map.draw(g, font, mx, my, drawn, selected, minecraft.player.blockPosition(), mouseX, mouseY);
        RegionListPacket.Summary target = hovered;
        hits.add(new Hit(mx, my, map.w, map.h, () -> {
            long now = System.currentTimeMillis();
            if (target != null) {
                if (target.id().equals(lastMapClickId) && now - lastMapClick < 350) {
                    tab = 1;
                } else {
                    select(target.id());
                }
                lastMapClickId = target.id();
            } else {
                lastMapClickId = null;
            }
            lastMapClick = now;
        }));
        String scale = Tr.n("map_scale", map.bpp);
        text(g, scale, mx + 2, my + map.h + 2, MUTED, 0.6f);
        if (l.remote()) {
            chip(g, mx + map.w - 70, my + 2, 68, 10, Tr.t("this_server_overlay"), overlayHere, MUTED, 0.5f, () -> overlayHere = !overlayHere);
        }
        if (hovered != null) {
            String tip = hovered.id() + " - " + Tr.t("type." + hovered.type()) + Tr.t("meta_priority", hovered.priority());
            text(g, tip, mx + map.w - 2 - (int) (font.width(tip) * 0.6f), my + map.h + 2, TEXT, 0.6f);
        }
    }

    private List<Object> flagRows(@Nullable RegionDetailPacket d) {
        List<Object> rows = new ArrayList<>();
        String category = null;
        for (Flag<?> f : FlagRegistry.all()) {
            String value = d == null ? null : d.flags().get(f.name());
            if (onlySet && value == null) {
                continue;
            }
            if (!f.category().equals(category)) {
                category = f.category();
                rows.add(category);
            }
            rows.add(f);
        }
        return rows;
    }

    private void drawFlags(GuiGraphics g, int x, int y, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        chip(g, px + 4, cy + 3, 48, 10, onlySet ? Tr.t("set_only") : Tr.t("all_flags"), onlySet, TQ, 0.5f, () -> {
            onlySet = !onlySet;
            flagScroll = 0;
        });
        int set = d == null ? 0 : d.flags().size();
        text(g, Tr.t("flags_summary", set, FlagRegistry.all().size() - set) + (d != null && !d.inherited().isEmpty() ? Tr.t("flags_from_parent", d.inherited().size()) : ""),
                px + 58, cy + 5, MUTED, 0.6f);
        List<Object> rows = flagRows(d);
        boolean hints = width >= 440;
        int rowH = hints ? 16 : 12;
        int listTop = cy + 15, listH = contentH - 17 - (inputMode == Input.FLAG_VALUE ? 18 : 0);
        int visible = listH / rowH;
        flagScroll = Mth.clamp(flagScroll, 0, Math.max(0, rows.size() - visible));
        HoloScreen.scissor(g, px + 1, listTop, px + paneW - 1, listTop + listH);
        for (int i = 0; i < visible && i + flagScroll < rows.size(); i++) {
            Object row = rows.get(i + flagScroll);
            int ry = listTop + i * rowH;
            if (row instanceof String category) {
                text(g, category.toUpperCase(Locale.ROOT), px + 6, ry + 4, TQ, 0.6f);
                continue;
            }
            Flag<?> f = (Flag<?>) row;
            String value = d == null ? null : d.flags().get(f.name());
            String inherited = d == null ? null : d.inherited().get(f.name());
            boolean editing = f.name().equals(editingFlag);
            if (editing) {
                g.fill(px + 1, ry, px + paneW - 1, ry + rowH, 0x2219D3B0);
            }
            text(g, f.name(), px + 6, ry + 2, value != null ? TEXT : MUTED, 0.75f);
            if (hints) {
                String hint = inherited != null && value == null ? Tr.t("inherits", inherited) : f.hint();
                text(g, font.plainSubstrByWidth(hint, 320), px + 6, ry + 9, inherited != null && value == null ? VIO : 0xFF6F7788, 0.5f);
            }
            if (remote()) {
                String shown = value != null ? value : inherited != null ? inherited : Tr.t("not_set");
                String clipped = font.plainSubstrByWidth(shown, 110);
                text(g, clipped, px + paneW - 6 - (int) (font.width(clipped) * 0.6f), ry + 3, value != null ? TEXT : MUTED, 0.6f);
            } else if (f instanceof StateFlag) {
                stateSwitch(g, px + paneW - 78, ry + 1, value, f.name());
            } else {
                String shown = value != null ? value : inherited != null ? inherited : f.defaultValue() != null ? format(f) : Tr.t("not_set");
                int colour = value != null ? TEXT : MUTED;
                String clipped = font.plainSubstrByWidth(shown, 110);
                text(g, clipped, px + paneW - 80 - (int) (font.width(clipped) * 0.6f) + 72 - 40, ry + 3, colour, 0.6f);
                chip(g, px + paneW - 34, ry + 1, 30, 11, Tr.t("edit"), editing, TQ, 0.6f, () -> {
                    editingFlag = f.name();
                    openInput(Input.FLAG_VALUE, value == null ? "" : value, f.hint());
                });
            }
        }
        g.disableScissor();
        if (rows.size() > visible) {
            int barH = Math.max(6, listH * visible / rows.size());
            int barY = listTop + (listH - barH) * flagScroll / Math.max(1, rows.size() - visible);
            g.fill(px + paneW - 3, barY, px + paneW - 1, barY + barH, LINE);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String format(Flag f) {
        return f.format(f.defaultValue());
    }

    private void drawAccess(GuiGraphics g, int x, int y, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y, half = paneW / 2;
        text(g, Tr.t("owners"), px + 6, cy + 5, TQ, 0.6f);
        text(g, Tr.t("members"), px + half + 6, cy + 5, VIO, 0.6f);
        text(g, Tr.t("may_edit_the_region_and_build"), px + 6, cy + 12, 0xFF6F7788, 0.5f);
        text(g, Tr.t("may_build_and_pass_entry_rules"), px + half + 6, cy + 12, 0xFF6F7788, 0.5f);
        if (d != null) {
            people(g, px + 4, cy + 20, half - 8, d.owners(), RegionActionPacket.REMOVE_OWNER);
            people(g, px + half + 4, cy + 20, half - 8, d.members(), RegionActionPacket.REMOVE_MEMBER);
        }
        if (inputMode != Input.PLAYER) {
            button(g, px + 4, cy + contentH - 18, 100, 14, Tr.t("add_a_player"), TQ, () -> openInput(Input.PLAYER, "", Tr.t("player_name_then_own_or_mem")));
        }
    }

    private void people(GuiGraphics g, int x, int y, int w, List<RegionDetailPacket.Person> list, String removeAction) {
        int rows = Math.min(list.size(), 7);
        for (int i = 0; i < rows; i++) {
            RegionDetailPacket.Person p = list.get(i);
            int ry = y + i * 12;
            text(g, font.plainSubstrByWidth(p.name(), (int) ((w - 20) / 0.75f)), x + 2, ry + 2, TEXT, 0.75f);
            chip(g, x + w - 12, ry, 12, 11, "x", false, BAD, 0.6f, () -> act(removeAction, p.uuid().toString()));
        }
        if (list.isEmpty()) {
            text(g, Tr.t("none"), x + 2, y + 2, MUTED, 0.75f);
        } else if (list.size() > rows) {
            text(g, Tr.t("n_more", list.size() - rows), x + 2, y + rows * 12 + 2, MUTED, 0.6f);
        }
    }

    private void drawRanks(GuiGraphics g, int x, int y, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        text(g, Tr.t("ranks_that_may_enter"), px + 6, cy + 5, TQ, 0.6f);
        text(g, d == null || d.ranks().isEmpty() ? Tr.t("any_rank_may_enter_entry_still_applies") : Tr.t("only_these_ranks_may_enter_besides_members"),
                px + 6, cy + 12, 0xFF6F7788, 0.5f);
        if (d == null) {
            return;
        }
        List<String> available = new ArrayList<>(d.availableRanks());
        for (String r : d.ranks()) {
            if (!available.contains(r)) {
                available.add(r);
            }
        }
        if (available.isEmpty()) {
            text(g, Tr.t("luckperms_was_not_found_on_this_server_so_no_ran"), px + 6, cy + 24, MUTED, 0.6f);
        }
        // B17: every group, as many across and down as the pane has room for; the wheel scrolls the rest
        int cols = Math.max(3, (paneW - 12) / 86), rowsShown = Math.max(1, (contentH - 44) / 13);
        int rowsAll = (available.size() + cols - 1) / cols;
        rankScroll = Mth.clamp(rankScroll, 0, Math.max(0, rowsAll - rowsShown));
        for (int i = rankScroll * cols; i < available.size() && i < (rankScroll + rowsShown) * cols; i++) {
            String rank = available.get(i);
            boolean on = d.ranks().contains(rank.toLowerCase(Locale.ROOT));
            int col = i % cols, row = i / cols - rankScroll;
            int bx = px + 6 + col * 86, by = cy + 22 + row * 13;
            chip(g, bx, by, 82, 11, (on ? Tr.t("x") : "[ ] ") + font.plainSubstrByWidth(rank, 90), on, VIO, 0.6f,
                    () -> act(on ? RegionActionPacket.REMOVE_RANK : RegionActionPacket.ADD_RANK, rank));
        }
        if (rowsAll > rowsShown) {
            int above = rankScroll * cols, below = Math.max(0, available.size() - (rankScroll + rowsShown) * cols);
            String more = Tr.t("ranks_more", above + below);
            text(g, more, px + paneW - 6 - (int) (font.width(more) * 0.6f), cy + contentH - 13, MUTED, 0.6f);
        }
        if (inputMode != Input.RANK) {
            button(g, px + 4, cy + contentH - 18, 90, 14, Tr.t("type_a_rank"), VIO, () -> openInput(Input.RANK, "", Tr.t("luckperms_group_name_enter_to_add")));
        }
    }

    /**
     * The world default's BORDER tab (owner decision D42, mock-up option C): this world in the centre with its border,
     * and a slot on each side naming the world on another server that edge leads to, or + LINK. A free slot opens a
     * list of the other servers' worlds, the ones whose matching edge (south for north) is free first.
     */
    private void drawBorder(GuiGraphics g, int x, int y, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        java.util.Map<String, String> data = d == null ? java.util.Map.of() : d.data();
        String storage = data.getOrDefault("storage", "off");
        String size = data.get("borderSize");
        if (pickSide != null) {
            drawEdgePicker(g, px, cy, data, pickSide);
            return;
        }
        // centre: this world and its border
        int tx = px + 93, ty = cy + 36, tw = 80, th = 56;
        g.fill(tx, ty, tx + tw, ty + th, (TQ & 0xFFFFFF) | 0x22000000);
        frame(g, tx, ty, tw, th, TQ);
        text(g, fit(storage.equals("off") ? Tr.t("this_server") : storage, tw - 8, 0.75f), tx + 4, ty + 4, TQ, 0.75f);
        if (size == null) {
            text(g, Tr.t("no_border"), tx + 4, ty + 16, MUTED, 0.6f);
        } else {
            text(g, Tr.t("border_wide", size), tx + 4, ty + 16, TEXT, 0.6f);
            text(g, fit(Tr.t("border_around", whole(data.getOrDefault("borderX", "0")), whole(data.getOrDefault("borderZ", "0"))), tw - 8, 0.6f), tx + 4, ty + 25, MUTED, 0.6f);
        }
        chip(g, tx + 4, ty + th - 13, 40, 10, size == null ? Tr.t("set") : Tr.t("change"), false, TQ, 0.5f,
                () -> openInput(Input.BORDER, size == null ? "" : size + " " + whole(data.getOrDefault("borderX", "0")) + " " + whole(data.getOrDefault("borderZ", "0")),
                        Tr.t("border_hint")));
        // the four edges
        slot(g, data, "north", px + 93, cy + 2, 80, 30, size != null, storage);
        slot(g, data, "south", px + 93, cy + 96, 80, 30, size != null, storage);
        slot(g, data, "west", px + 4, cy + 36, 85, 56, size != null, storage);
        slot(g, data, "east", px + 177, cy + 36, 85, 56, size != null, storage);
        if (storage.equals("off")) {
            text(g, Tr.t("links_need_shared_storage"), px + 4, cy + 4, MUTED, 0.5f);
        }
        drawPlotsCard(g, data, px + 177, cy + 96, 85, 30);
    }

    /**
     * Plots in this world (D92, D93), in the BORDER tab's free bottom-right corner: allowed or refused and why, and the
     * world's own switch ALLOW / REFUSE / CONFIG for when it counts as a PvP world.
     */
    private void drawPlotsCard(GuiGraphics g, java.util.Map<String, String> data, int sx, int sy, int sw, int sh) {
        String rule = data.getOrDefault("plots.rule", "config_refuses");
        boolean allowed = !rule.endsWith("refuses");
        frame(g, sx, sy, sw, sh, allowed ? TQ : BAD);
        text(g, Tr.t("plots_here"), sx + 3, sy + 3, allowed ? TQ : BAD, 0.6f);
        String limit = data.get("plotLimit");
        chip(g, sx + sw - 38, sy + 2, 36, 8, limit == null ? Tr.t("plots_max_config") : Tr.t("plots_max", limit), limit != null, GOLD, 0.5f,
                () -> data("plotLimit", limit == null ? "" : limit, Tr.t("plot_limit_world_hint")));
        text(g, Tr.t("plots_rule." + rule), sx + 3, sy + 11, MUTED, 0.5f);
        String own = data.getOrDefault(com.mrgregles.bsp_region_guard.protection.TotemRule.PLOTS_IN_PVP, "");
        int cw = (sw - 8) / 3;
        chip(g, sx + 2, sy + sh - 11, cw, 9, Tr.t("plots_allow"), own.equals("allow"), TQ, 0.5f, () -> act(RegionActionPacket.PLOTS_PVP, "allow"));
        chip(g, sx + 4 + cw, sy + sh - 11, cw, 9, Tr.t("plots_refuse"), own.equals("deny"), BAD, 0.5f, () -> act(RegionActionPacket.PLOTS_PVP, "deny"));
        chip(g, sx + 6 + 2 * cw, sy + sh - 11, cw, 9, Tr.t("plots_config"), own.isEmpty(), MUTED, 0.5f, () -> act(RegionActionPacket.PLOTS_PVP, "default"));
    }

    private static String whole(String number) {
        return number.endsWith(".0") ? number.substring(0, number.length() - 2) : number;
    }

    private void slot(GuiGraphics g, java.util.Map<String, String> data, String side, int sx, int sy, int sw, int sh, boolean hasBorder, String storage) {
        String edge = data.get("edge." + side);
        if (edge != null) {
            String[] e = edge.split("\\|");
            g.fill(sx, sy, sx + sw, sy + sh, (VIO & 0xFFFFFF) | 0x1A000000);
            frame(g, sx, sy, sw, sh, VIO);
            text(g, Tr.t("side." + side), sx + 3, sy + 3, MUTED, 0.5f);
            text(g, e[0], sx + 3, sy + 10, VIO, 0.6f);
            text(g, Tr.t("its_edge", Tr.t("edge." + e[2])) + (e.length > 3 && !"0".equals(e[3]) ? " - " + e[3] : ""), sx + 3, sy + 18, MUTED, 0.5f);
            if (sh > 32) {
                text(g, worldShort(e[1]), sx + 3, sy + 26, MUTED, 0.5f);
                text(g, e.length > 4 && "theirs".equals(e[4]) ? Tr.t("made_on", e[0]) : Tr.t("arrive_16_in"), sx + 3, sy + 34, MUTED, 0.5f);
            }
            chip(g, sx + sw - 13, sy + 2, 11, 8, "x", false, BAD, 0.5f, () -> act(RegionActionPacket.EDGE_UNLINK, side));
            return;
        }
        boolean usable = hasBorder && !storage.equals("off");
        frame(g, sx, sy, sw, sh, usable ? LINE : 0xFF1C2028);
        String label = usable ? Tr.t("link") : hasBorder ? Tr.t("no_storage") : Tr.t("set_a_border");
        text(g, label, sx + (sw - (int) (font.width(label) * 0.6f)) / 2, sy + sh / 2 - 6, usable ? TQ : 0xFF4A5060, 0.6f);
        String sideName = Tr.t("side." + side);
        text(g, sideName, sx + (sw - (int) (font.width(sideName) * 0.5f)) / 2, sy + sh / 2 + 3, MUTED, 0.5f);
        if (usable) {
            hits.add(new Hit(sx, sy, sw, sh, () -> pickSide = side));
        }
    }

    private static String worldShort(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    /** "THE NORTH EDGE LEADS TO": every other server's world with a free edge; the matching edge (south for north) first. */
    private void drawEdgePicker(GuiGraphics g, int px, int cy, java.util.Map<String, String> data, String side) {
        String match = switch (side) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
        text(g, Tr.t("edge_leads_to", Tr.t("side." + side)), px + 6, cy + 4, GOLD, 0.6f);
        chip(g, px + paneW - 44, cy + 2, 38, 10, Tr.t("cancel"), false, MUTED, 0.5f, () -> pickSide = null);
        int ry = cy + 16, shown = 0;
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (int i = 1; ; i++) { // every entry: a fixed cap hid worlds past it (bug B5)
            String net = data.get("net." + i);
            if (net == null) {
                break;
            }
            String[] w = (net + "|").split("\\|", -1); // server|world|width|free
            if ("0".equals(w[2]) || w[3].isEmpty()) {
                continue; // no border there, or every edge taken
            }
            for (String free : w[3].split(",")) {
                rows.add(new String[]{w[0], w[1], free, w[2]});
            }
        }
        rows.sort(java.util.Comparator.comparing((String[] r) -> !r[2].equals(match)).thenComparing(r -> r[0]));
        for (String[] r : rows) {
            if (shown++ >= 10) {
                text(g, Tr.t("more_border_link", side), px + 6, ry, MUTED, 0.5f);
                break;
            }
            boolean best = r[2].equals(match);
            text(g, r[0], px + 6, ry, VIO, 0.6f);
            text(g, Tr.t("picker_row", worldShort(r[1]), Tr.t("edge." + r[2]), r[3]), px + 70, ry, best ? TEXT : MUTED, 0.6f);
            String target = r[0] + "|" + r[1] + "|" + r[2];
            hits.add(new Hit(px + 4, ry - 1, paneW - 8, 10, () -> {
                send(new RegionActionPacket(RegionActionPacket.EDGE_LINK, selected, side, target));
                pickSide = null;
            }));
            ry += 11;
        }
        if (rows.isEmpty()) {
            text(g, Tr.t("no_other_server_s_world_with_a_border_and_a_free"), px + 6, ry, MUTED, 0.6f);
            text(g, Tr.t("set_a_border_there_this_list_reads_the_shared_st"), px + 6, ry + 10, MUTED, 0.5f);
        }
    }

    private void drawTeleport(GuiGraphics g, int x, int y, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        String tp = d == null ? null : d.flags().get("teleport");
        String spawn = d == null ? null : d.flags().get("spawn");
        text(g, Tr.t("teleport_point"), px + 6, cy + 4, TQ, 0.6f);
        text(g, tp == null ? Tr.t("not_set_the_top_of_the_region_s_centre") : font.plainSubstrByWidth(tp, 200), px + 80, cy + 4, tp == null ? MUTED : TEXT, 0.6f);
        chip(g, px + 6, cy + 12, 44, 10, Tr.t("set_here"), false, TQ, 0.5f, () -> flag("teleport", "here"));
        chip(g, px + 54, cy + 12, 30, 10, Tr.t("clear"), false, BAD, 0.5f, () -> flag("teleport", ""));
        chip(g, px + 88, cy + 12, 24, 10, Tr.t("go"), false, MUTED, 0.5f, () -> act(RegionActionPacket.TELEPORT, ""));
        text(g, Tr.t("respawn_point"), px + 6, cy + 26, TQ, 0.6f);
        text(g, spawn == null ? Tr.t("not_set_normal_respawn") : font.plainSubstrByWidth(spawn, 200), px + 80, cy + 26, spawn == null ? MUTED : TEXT, 0.6f);
        chip(g, px + 6, cy + 34, 44, 10, Tr.t("set_here"), false, TQ, 0.5f, () -> flag("spawn", "here"));
        chip(g, px + 54, cy + 34, 30, 10, Tr.t("clear"), false, BAD, 0.5f, () -> flag("spawn", ""));

        text(g, Tr.t("triggers"), px + 6, cy + 50, GOLD, 0.6f);
        text(g, Tr.t("move_players_on_entering_leaving_a_timer_or_serv"), px + 50, cy + 50, 0xFF6F7788, 0.5f);
        java.util.Map<String, String> data = d == null ? java.util.Map.of() : d.data();
        int ry = cy + 59, n = 0;
        for (int i = 1; i <= 20; i++) {
            String line = data.get("trigger." + i);
            if (line == null) {
                break;
            }
            n++;
            if (n > 5) {
                text(g, Tr.t("more_triggers", selected), px + 6, ry, MUTED, 0.5f);
                ry += 8;
                break;
            }
            text(g, i + ". " + font.plainSubstrByWidth(line, 380), px + 6, ry, TEXT, 0.5f);
            int index = i;
            chip(g, px + paneW - 18, ry - 1, 12, 8, "x", false, BAD, 0.5f, () -> act(RegionActionPacket.TRIGGER_REMOVE, String.valueOf(index)));
            ry += 8;
        }
        if (n == 0) {
            text(g, Tr.t("none"), px + 6, ry, MUTED, 0.5f);
            ry += 8;
        }
        if (inputMode != Input.TRIGGER) {
            String[] causes = {"enter", "exit", "timer", "shutdown"};
            for (int i = 0; i < causes.length; i++) {
                String c = causes[i];
                chip(g, px + 6 + i * 46, ry + 2, 44, 10, Tr.t("cause." + c), triggerCause.equals(c), GOLD, 0.5f, () -> triggerCause = c);
            }
            chip(g, px + 6 + 4 * 46, ry + 2, 50, 10, Tr.t("add"), false, GOLD, 0.5f, () -> openInput(Input.TRIGGER, "here",
                    triggerCause.equals("timer") ? Tr.t("trigger_hint_timer") : Tr.t("trigger_hint")));
        }
        text(g, Tr.t("cross_server_destinations_server_world_are_carri"), px + 6, cy + contentH - 10, 0xFF6F7788, 0.5f);
    }

    private void drawSale(GuiGraphics g, int x, int y, RegionListPacket.Summary s, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        java.util.Map<String, String> data = d == null ? java.util.Map.of() : d.data();
        String type = s.type();
        if (type.equals("global")) {
            text(g, Tr.t("the_world_default_cannot_be_sold"), px + 6, cy + 6, MUTED, 0.75f);
            return;
        }
        if (type.equals("claim")) {
            text(g, Tr.t("owned_claim"), px + 6, cy + 5, VIO, 0.6f);
            String paid = data.containsKey("boughtCoins") ? Tr.t("worth_copper", data.get("boughtCoins")) : money(data, data.getOrDefault("boughtFor", "?"), "pieces");
            text(g, Tr.t("bought_for_origin", paid, data.getOrDefault("origin", "?")), px + 6, cy + 13, TEXT, 0.75f);
            text(g, Tr.t("the_owner_gives_it_up_with_claim_abandon_an_admi"), px + 6, cy + 24, 0xFF6F7788, 0.5f);
            text(g, fit(Tr.t("buyer_may_set", data.getOrDefault("playerFlags", Tr.t("the_config_list"))), paneW - 12, 0.6f), px + 6, cy + 36, GOLD, 0.6f);
            return;
        }
        text(g, Tr.t("plot_for_sale"), px + 6, cy + 5, GOLD, 0.6f);
        // D111: the plot's look in the world, while it is for sale or open
        chip(g, px + paneW - 40, cy + 3, 34, 10, Tr.t("look.button"), false, GOLD, 0.5f, () -> {
            if (minecraft != null) {
                minecraft.setScreen(new PlotLookScreen(s.id(), this));
            }
        });
        text(g, Tr.t("a_fixed_price_for_this_whole_region_the_buyer_be"), px + 6, cy + 12, 0xFF6F7788, 0.5f);
        String price = data.getOrDefault("price", "0");
        boolean coins = !data.getOrDefault("currency", "pieces").equals("pieces");
        text(g, type.equals("plot_for_sale") ? Tr.t("for_sale_at", money(data, price, null)) : Tr.t("not_for_sale"), px + 6, cy + 20, type.equals("plot_for_sale") ? TEXT : MUTED, 0.75f);
        button(g, px + 6, cy + 31, 70, 12, Tr.t("set_price"), GOLD, () -> data("price", price, coins ? Tr.t("price_in_coins_0_takes_it_off_sale") : Tr.t("price_in_shatter_pieces_0_takes_it_off_sale")));
        if (coins) {
            // D95: the tier this plot or open area prices in; lit = this region's own choice, none lit = the config's
            String own = data.getOrDefault(com.mrgregles.bsp_region_guard.claim.Prices.COIN_TIER, "");
            int cx = px + 82;
            for (String tier : data.getOrDefault("coinTiers", "").split(",")) {
                if (tier.isEmpty()) {
                    continue;
                }
                chip(g, cx, cy + 32, 30, 10, Tr.t("coin_tier." + tier), tier.equals(own), GOLD, 0.5f,
                        () -> send(new RegionActionPacket(RegionActionPacket.SET_DATA, selected, com.mrgregles.bsp_region_guard.claim.Prices.COIN_TIER, tier.equals(own) ? "default" : tier)));
                cx += 32;
            }
        }
        text(g, Tr.t("open_area"), px + 6, cy + 52, GOLD, 0.6f);
        text(g, Tr.t("players_outline_their_own_plot_inside_and_pay_pe"), px + 6, cy + 59, 0xFF6F7788, 0.5f);
        String cost = data.getOrDefault("costPerBlock", "0");
        text(g, type.equals("open_for_buying") ? Tr.t("open_cost", money(data, cost, null), data.getOrDefault("minSize", Tr.t("config")), data.getOrDefault("maxSize", Tr.t("config"))) : Tr.t("not_open"),
                px + 6, cy + 67, type.equals("open_for_buying") ? TEXT : MUTED, 0.75f);
        button(g, px + 6, cy + 78, 70, 12, Tr.t("set_cost"), GOLD, () -> data("costPerBlock", cost, coins ? Tr.t("coins_per_block_of_ground_0_closes_the_area") : Tr.t("shatter_pieces_per_block_of_ground_0_closes_the")));
        button(g, px + 80, cy + 78, 56, 12, Tr.t("min_size"), MUTED, () -> data("minSize", data.getOrDefault("minSize", ""), Tr.t("smallest_side_in_blocks_empty_uses_the_config")));
        button(g, px + 140, cy + 78, 56, 12, Tr.t("max_size"), MUTED, () -> data("maxSize", data.getOrDefault("maxSize", ""), Tr.t("largest_side_in_blocks_empty_uses_the_config")));
        text(g, fit(Tr.t("buyer_may_set", data.getOrDefault("playerFlags", Tr.t("the_config_list"))), paneW - 50, 0.6f), px + 6, cy + 96, GOLD, 0.6f);
        chip(g, px + paneW - 40, cy + 94, 34, 10, Tr.t("edit"), false, GOLD, 0.5f, () -> data("playerFlags", data.getOrDefault("playerFlags", ""), Tr.t("flags_the_buyer_may_set_comma_separated_default")));
        text(g, Tr.t("buying_happens_in_bsp_core_s_shop_screen_or_with"), px + 6, cy + 108, 0xFF6F7788, 0.5f);
        String limit = data.get("plotLimit");
        text(g, limit == null ? Tr.t("plot_limit_none") : Tr.t("plot_limit_is", limit), px + 6, cy + 120, limit == null ? MUTED : TEXT, 0.6f);
        chip(g, px + paneW - 40, cy + 118, 34, 10, Tr.t("edit"), false, GOLD, 0.5f, () -> data("plotLimit", limit == null ? "" : limit, Tr.t("plot_limit_hint")));
    }

    private void drawMine(GuiGraphics g, int x, int y, RegionListPacket.Summary s, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        if (s.type().equals("global")) {
            text(g, Tr.t("the_world_default_cannot_be_a_mine"), px + 6, cy + 6, MUTED, 0.75f);
            return;
        }
        if (!s.type().equals("mine")) {
            text(g, Tr.t("not_a_mine_a_mine_refills_itself_with_the_blocks"), px + 6, cy + 6, MUTED, 0.6f);
            text(g, Tr.t("making_it_a_mine_allows_breaking_and_refuses_pla"), px + 6, cy + 14, 0xFF6F7788, 0.5f);
            button(g, px + 6, cy + 26, 90, 12, Tr.t("make_this_a_mine"), GOLD, () -> act(RegionActionPacket.MINE_CREATE, ""));
            return;
        }
        java.util.Map<String, String> data = d == null ? java.util.Map.of() : d.data();
        text(g, Tr.t("fill_table"), px + 6, cy + 5, GOLD, 0.6f);
        text(g, Tr.t("the_heaviest_block_fills_the_mine_the_others_gro"), px + 70, cy + 5, 0xFF6F7788, 0.5f);
        String fill = data.getOrDefault("fill", "");
        int total = 0;
        java.util.List<String[]> rows = new ArrayList<>();
        if (!fill.isEmpty()) {
            for (String part : fill.split(";")) {
                int eq = part.lastIndexOf('=');
                if (eq > 0) {
                    rows.add(new String[]{part.substring(0, eq), part.substring(eq + 1)});
                    try {
                        total += Integer.parseInt(part.substring(eq + 1));
                    } catch (NumberFormatException ignored) {
                        // shown as is
                    }
                }
            }
        }
        int ry = cy + 14;
        if (rows.isEmpty()) {
            text(g, Tr.t("empty_add_blocks_below_e_g_minecraft_stone_90_mi"), px + 6, ry, MUTED, 0.6f);
            ry += 9;
        }
        for (int i = 0; i < rows.size() && i < 7; i++) {
            String[] row = rows.get(i);
            int w = 0;
            try {
                w = Integer.parseInt(row[1]);
            } catch (NumberFormatException ignored) {
                // shown as is
            }
            text(g, font.plainSubstrByWidth(row[0], 180), px + 6, ry, TEXT, 0.6f);
            // D116: the share as the MINE FILL window shows it, one decimal; the block that fills the rest is marked
            boolean isRest = row[0].equals(data.getOrDefault(com.mrgregles.bsp_region_guard.mine.MineData.FILLER, ""));
            String share = String.format(java.util.Locale.ROOT, "%.1f%%", total > 0 ? 100.0 * w / total : 0);
            text(g, isRest ? share + "  " + Tr.t("fill.rest") : share, px + 130, ry, isRest ? GOLD : MUTED, 0.6f);
            String block = row[0];
            chip(g, px + 176, ry - 1, 12, 8, "x", false, BAD, 0.5f, () -> send(new RegionActionPacket(RegionActionPacket.MINE_ADD, selected == null ? "" : selected, block, "0")));
            ry += 8;
        }
        if (rows.size() > 7) {
            text(g, Tr.t("more_mine", rows.size() - 7), px + 6, ry, MUTED, 0.5f);
            ry += 8;
        }
        // D115: blocks are picked in the MINE FILL window's browser, not typed
        button(g, px + 6, ry + 1, 70, 11, Tr.t("fill.edit"), GOLD, () -> {
            if (minecraft != null && selected != null) {
                minecraft.setScreen(new MineFillScreen(selected, this));
            }
        });
        int ty = cy + 86;
        text(g, Tr.t("reset"), px + 6, ty, GOLD, 0.6f);
        String secs = data.getOrDefault("secondsToReset", "-1");
        String interval = data.getOrDefault("intervalSeconds", "0");
        text(g, "-1".equals(secs) || "0".equals(interval) ? Tr.t("no_automatic_reset") : Tr.t("reset_every", interval, secs), px + 40, ty, TEXT, 0.6f);
        button(g, px + 6, ty + 9, 56, 11, Tr.t("interval"), MUTED, () -> openInput(Input.MINE_INTERVAL, "0".equals(interval) ? "" : interval, Tr.t("seconds_between_resets_empty_stops_them")));
        button(g, px + 66, ty + 9, 60, 11, Tr.t("reset_now"), BAD, () -> act(RegionActionPacket.MINE_RESET, ""));
        button(g, px + 130, ty + 9, 56, 11, Boolean.parseBoolean(data.getOrDefault("hasSpawn", "false")) ? Tr.t("move_spawn") : Tr.t("set_spawn"), TQ, () -> act(RegionActionPacket.MINE_SPAWN, ""));
        button(g, px + 190, ty + 9, 70, 11, Boolean.parseBoolean(data.getOrDefault("hasShell", "false")) ? Tr.t("resize_shell") : Tr.t("make_shell"), MUTED, () -> openInput(Input.MINE_SHELL, "5", Tr.t("blocks_around_the_mine_where_only_the_mine_can_b")));
        text(g, Tr.t("players_inside_are_moved_to_the_spawn_or_above_t"), px + 6, ty + 23, 0xFF6F7788, 0.5f);
    }

    /** An amount in this region's currency (D95): {@code 500 pieces} or {@code 5 gold coins}; {@code currency} null reads the detail's. */
    private static String money(java.util.Map<String, String> data, String amount, @Nullable String currency) {
        String c = currency != null ? currency : data.getOrDefault("currency", "pieces");
        if (c.equals("pieces")) {
            return Tr.t("amount_pieces", amount);
        }
        String tier = Tr.t("coin_tier." + c).toLowerCase(java.util.Locale.ROOT);
        return amount.equals("1") ? Tr.t("amount_coin", amount, tier) : Tr.t("amount_coins", amount, tier);
    }

    private void data(String key, String current, String hint) {
        editingData = key;
        openInput(Input.DATA, current.equals("0") ? "" : current, hint);
    }

    private void flag(String name, String value) {
        if (selected != null) {
            send(new RegionActionPacket(RegionActionPacket.FLAG, selected, name, value));
        }
    }

    private void drawInfo(GuiGraphics g, int x, int y, RegionListPacket.Summary s, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        text(g, Tr.t("name"), px + 6, cy + 5, TQ, 0.6f);
        text(g, s.name(), px + 6, cy + 12, TEXT, 0.75f);
        boolean ro = remote();
        if (!ro) {
            chip(g, px + 110, cy + 10, 30, 11, Tr.t("edit"), false, TQ, 0.6f, () -> openInput(Input.NAME, s.name(), Tr.t("display_name_enter_to_save")));
        }
        boolean global = s.type().equals("global");
        text(g, Tr.t("type"), px + 6, cy + 26, TQ, 0.6f);
        String[] types = {"protected", "plot_for_sale", "open_for_buying", "claim", "mine"};
        for (int i = 0; i < types.length; i++) {
            String t = types[i];
            chip(g, px + 6 + i * 50, cy + 33, 48, 11, Tr.t("type." + t), s.type().equals(t), 0xFF000000 | typeColour(t), 0.6f, global || ro ? null : () -> act(RegionActionPacket.TYPE, t));
        }
        text(g, Tr.t("priority_label") + (global ? Tr.t("lowest_world_default") : String.valueOf(s.priority())), px + 6, cy + 49, TQ, 0.6f);
        if (!global && !ro) {
            int[] steps = {-10, -1, 1, 10};
            for (int i = 0; i < steps.length; i++) {
                int step = steps[i];
                chip(g, px + 110 + i * 26, cy + 47, 24, 11, (step > 0 ? "+" : "") + step, false, TEXT, 0.6f, () -> act(RegionActionPacket.PRIORITY, String.valueOf(s.priority() + step)));
            }
            text(g, Tr.t("higher_wins_where_regions_overlap"), px + 6, cy + 56, 0xFF6F7788, 0.5f);
        }
        text(g, Tr.t("parent_label") + (s.parent() == null ? Tr.t("none") : s.parent()), px + 6, cy + 66, TQ, 0.6f);
        if (!global && !ro) {
            chip(g, px + 110, cy + 64, 30, 11, Tr.t("set_small"), false, TQ, 0.6f, () -> openInput(Input.PARENT, s.parent() == null ? "" : s.parent(), Tr.t("parent_region_id_enter_to_set_empty_for_none")));
            text(g, Tr.t("unset_flags_take_the_parent_s_values"), px + 6, cy + 73, 0xFF6F7788, 0.5f);
        }
        if (s.bounds() != null) {
            drawSize(g, px, cy + 83, s, ro);
        }
        if (d != null) {
            text(g, Tr.t("version_changed", d.version(), (System.currentTimeMillis() - d.updatedAt()) / 60000), px + 150, cy + 5, 0xFF6F7788, 0.5f);
            if (!d.inherited().isEmpty()) {
                text(g, Tr.t("inherited_from_parent", d.inherited().size()), px + 150, cy + 12, VIO, 0.5f);
            }
        }
        text(g, Region.GLOBAL_ID.equals(s.id()) ? Tr.t("the_world_default_applies_everywhere_no_other_re") : "", px + 6, cy + 86, MUTED, 0.5f);
    }

    /**
     * The INFO tab's SIZE block (D75, option A of D80): the corners as six fields (Enter saves), the size, VERTICAL
     * FILL, SET CORNERS ON MAP and USE WAND SELECTION; for a region without corners only the last two.
     */
    private void drawSize(GuiGraphics g, int px, int top, RegionListPacket.Summary s, boolean ro) {
        Cuboid b = s.bounds();
        text(g, Tr.t("size"), px + 6, top, TQ, 0.6f);
        boolean none = Region.noCorners(b);
        int bx = px + 150, bw = paneW - 156;
        if (ro) {
            text(g, none ? Tr.t("no_corners_yet") : Tr.t("bounds_from_to", b.min().toShortString(), b.max().toShortString()), px + 6, top + 8, none ? GOLD : TEXT, 0.6f);
            return;
        }
        if (none) {
            text(g, Tr.t("no_corners_yet"), px + 6, top + 9, GOLD, 0.6f);
            text(g, Tr.t("no_corners_hint"), px + 6, top + 17, MUTED, 0.5f);
        } else {
            if (!s.id().equals(sizeFor) || !b.equals(sizeFrom)) {
                fillSize(s.id(), b);
            }
            int[] values = {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};
            for (int i = 0; i < 6; i++) {
                int fx = px + 30 + (i % 3) * 38, fy = top + 9 + (i / 3) * 13;
                if (i % 3 == 0) {
                    text(g, i == 0 ? Tr.t("size_from") : Tr.t("size_to"), px + 6, fy + 2, MUTED, 0.5f);
                }
                EditBox box = sizeBox[i];
                inset(g, fx - 2, fy - 1, 36, 11);
                if (box.isFocused()) {
                    frame(g, fx - 2, fy - 1, 36, 11, TQ);
                } else if (!box.getValue().equals(String.valueOf(values[i]))) {
                    frame(g, fx - 2, fy - 1, 36, 11, GOLD); // typed but not saved yet
                }
                box.setX(fx);
                box.setY(fy + 1);
                box.setVisible(true);
            }
            text(g, "x", px + 44, top + 35, 0xFF6F7788, 0.5f);
            text(g, "y", px + 82, top + 35, 0xFF6F7788, 0.5f);
            text(g, "z", px + 120, top + 35, 0xFF6F7788, 0.5f);
            text(g, Tr.t("bounds_size", b.sizeX(), b.sizeY(), b.sizeZ(), b.volume(), b.area()), px + 6, top + 42, MUTED, 0.5f);
            text(g, Tr.t("size_hint"), px + 6, top + 49, 0xFF6F7788, 0.5f);
            String fill = minecraft == null || minecraft.level == null ? Tr.t("vertical_fill")
                    : Tr.t("vertical_fill_range", minecraft.level.getMinBuildHeight(), minecraft.level.getMaxBuildHeight() - 1);
            chip(g, bx, top + 8, bw, 11, fill, true, GOLD, 0.6f, () -> act(RegionActionPacket.VFILL, ""));
        }
        chip(g, bx, top + (none ? 8 : 21), bw, 11, Tr.t("set_corners_on_map"), false, TQ, 0.6f, () -> act(RegionActionPacket.CORNERS, ""));
        chip(g, bx, top + (none ? 21 : 34), bw, 11, Tr.t("use_wand_selection"), false, MUTED, 0.6f, () -> act(RegionActionPacket.REDEFINE, ""));
    }

    private void fillSize(String id, Cuboid b) {
        int[] values = {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};
        for (int i = 0; i < 6; i++) {
            sizeBox[i].setValue(String.valueOf(values[i]));
            sizeBox[i].setFocused(false);
        }
        sizeFor = id;
        sizeFrom = b;
    }

    /** Sends the six corner fields; a field left empty keeps its number. */
    private void submitSize() {
        if (sizeFrom == null || sizeFor == null || !sizeFor.equals(selected)) {
            return;
        }
        int[] values = {sizeFrom.minX(), sizeFrom.minY(), sizeFrom.minZ(), sizeFrom.maxX(), sizeFrom.maxY(), sizeFrom.maxZ()};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            String v = sizeBox[i].getValue().trim();
            out.append(i == 0 ? "" : " ").append(v.isEmpty() || v.equals("-") ? String.valueOf(values[i]) : v);
            sizeBox[i].setFocused(false);
        }
        setFocused(null);
        act(RegionActionPacket.RESIZE, out.toString());
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (super.onClick(mx, my, button)) {
            return true;
        }
        for (Hit h : hits) {
            if (h.contains(mx, my)) {
                pressed = h;
                pressedAt = net.minecraft.Util.getMillis();
                h.action().run();
                return true;
            }
        }
        if (!(mx >= left() + PANE_X && my >= top() + winH - 32 && my < top() + winH - 18)) {
            confirmDelete = false;
        }
        return false;
    }

    @Override
    protected boolean onScroll(double mx, double my, double delta) {
        int x = left(), y = top();
        if (mx >= x + LIST_X && mx < x + LIST_X + LIST_W && my >= y + LIST_Y && my < y + LIST_Y + listH) {
            listScroll -= (int) Math.signum(delta);
            return true;
        }
        if (mx >= x + PANE_X && mx < x + PANE_X + paneW && my >= y + CONTENT_Y && my < y + CONTENT_Y + contentH) {
            if (tab == 0) {
                zoomedByHand = true;
                map.zoom(delta > 0 ? -1 : 1);
            } else if (tab == 1) {
                flagScroll -= (int) Math.signum(delta);
            } else if (tab == 3) {
                rankScroll -= (int) Math.signum(delta); // B17: RANKS
            }
            return true;
        }
        return super.onScroll(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (EditBox b : sizeBox) {
            if (b.isVisible() && b.isFocused()) {
                if (keyCode == 257 || keyCode == 335) { // Enter saves the corners
                    submitSize();
                    return true;
                }
                if (keyCode == 256 && sizeFor != null && sizeFrom != null) { // Esc puts the numbers back
                    fillSize(sizeFor, sizeFrom);
                    setFocused(null);
                    return true;
                }
            }
        }
        if (inputMode != Input.NONE && input.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { // Enter
                if (inputMode == Input.PLAYER) {
                    addPerson(RegionActionPacket.ADD_MEMBER);
                } else {
                    submitInput();
                }
                return true;
            }
            if (keyCode == 256) { // Esc
                closeInput();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Opens the region map on this world, centred on the selected region. */
    private void openMap() {
        PlotMapScreen.focusNext = selected;
        com.mrgregles.bsp_region_guard.network.RegionNetwork.CHANNEL.sendToServer(
                com.mrgregles.bsp_region_guard.network.PlotMapActionPacket.simple(com.mrgregles.bsp_region_guard.network.PlotMapActionPacket.VIEW,
                        minecraft == null || minecraft.level == null ? "" : minecraft.level.dimension().location().toString(), true));
    }

    @Override
    public void onClose() {
        if (remote()) {
            send(new RegionActionPacket(RegionActionPacket.VIEW_SERVER, "", "", "")); // the server forgets the view too
        }
        super.onClose();
        if (returnToMap && !remote()) {
            openMap(); // came from the region map's card: back to it
        }
    }
}
