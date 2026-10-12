package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.network.RegionActionPacket;
import com.mrgregles.bsp_region_guard.network.RegionDetailPacket;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import com.mrgregles.bsp_region_guard.region.Cuboid;
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
public class RegionScreen extends Screen {
    static final int W = 420, H = 236;
    static final int BG = 0xF010151C, INSET = 0xFF0C0E12, LINE = 0xFF2A2F3A, TQ = 0xFF19D3B0, VIO = 0xFFB58CFF, GOLD = 0xFFFFD23A,
            BAD = 0xFFFF6B5C, TEXT = 0xFFE6EAF2, MUTED = 0xFF9AA3B5;
    private static final String[] TABS = {"MAP", "FLAGS", "ACCESS", "RANKS", "SALE", "MINE", "TELEPORT", "INFO"};
    private static final String[] FILTERS = {"ALL", "PROT", "CLAIM", "MINE", "SALE"};
    private static final int LIST_X = 8, LIST_Y = 52, LIST_W = 130, LIST_H = 144, ROW = 12;
    private static final int PANE_X = 146, PANE_W = 266, CONTENT_Y = 62, CONTENT_H = 134;

    /** A clickable rectangle registered while drawing and consumed by the next click. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private enum Input { NONE, NEW_ID, FLAG_VALUE, PLAYER, RANK, PARENT, NAME, TELEPORT_MESSAGE, DATA, MINE_BLOCK, MINE_INTERVAL, MINE_SHELL, TRIGGER }

    private String triggerCause = "enter";
    private boolean showRemote;

    @Nullable
    private String editingData;

    private final List<Hit> hits = new ArrayList<>();
    @Nullable
    private String selected;
    private int tab, filter, listScroll, flagScroll;
    private boolean onlySet;
    private boolean confirmDelete;
    private long lastMapClick;
    @Nullable
    private String lastMapClickId;
    private boolean zoomedByHand;
    private final RegionMap map = RegionMap.create();
    private EditBox search;
    private EditBox input;
    private Input inputMode = Input.NONE;
    @Nullable
    private String editingFlag;

    public RegionScreen(@Nullable String select) {
        super(Component.literal("Regions"));
        this.selected = select;
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

    void onList(RegionListPacket msg) {
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
    protected void init() {
        int x = left(), y = top();
        search = new EditBox(font, x + LIST_X + 3, y + 24, LIST_W - 6, 10, Component.literal("search"));
        search.setBordered(false);
        search.setMaxLength(64);
        search.setHint(Component.literal("search regions").withStyle(s -> s.withColor(0x9AA3B5)));
        search.setResponder(t -> listScroll = 0);
        addRenderableWidget(search);
        input = new EditBox(font, x + PANE_X + 7, y + CONTENT_Y + CONTENT_H - 15, PANE_W - 80, 10, Component.literal("value"));
        input.setBordered(false);
        input.setMaxLength(1000);
        input.setVisible(false);
        addRenderableWidget(input);
        frameMap();
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
        g.fill(x, y, x + w, y + h, on ? (colour & 0xFFFFFF) | 0x22000000 : INSET);
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
            chip(g, bx, y, 23, 11, labels[i], on, colours[i], 0.6f,
                    () -> send(new RegionActionPacket(RegionActionPacket.FLAG, selected == null ? "" : selected, flagName, v)));
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        hits.clear();
        renderBackground(g);
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, BG);
        frame(g, x, y, W, H, TQ);
        RegionListPacket l = list();
        String world = l == null ? "" : l.dimension().getPath().toUpperCase(Locale.ROOT);
        text(g, "REGIONS - " + world, x + 10, y + 8, TQ, 1f);
        text(g, l == null ? "loading" : l.regions().size() + " regions", x + W - 10 - (int) (font.width(l == null ? "loading" : l.regions().size() + " regions") * 0.75f), y + 9, MUTED, 0.75f);

        drawList(g, x, y, mouseX, mouseY);
        drawPane(g, x, y, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partial);
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
            chip(g, cx, y + 39, 25, 10, FILTERS[i], filter == i, TQ, 0.5f, () -> {
                filter = fi;
                listScroll = 0;
            });
            cx += 26;
        }
        inset(g, x + LIST_X, y + LIST_Y, LIST_W, LIST_H);
        List<RegionListPacket.Summary> rows = filtered();
        int visible = LIST_H / ROW;
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
            String label = s.parent() != null ? "  " + s.id() : s.id();
            text(g, font.plainSubstrByWidth(label, 96), x + LIST_X + 6, ry + 2, sel ? TEXT : MUTED, 1f);
            int colour = 0xFF000000 | typeColour(s.type());
            text(g, typeLetter(s.type()), x + LIST_X + LIST_W - 22, ry + 2, colour, 1f);
            String prio = s.bounds() == null ? "-" : String.valueOf(s.priority());
            text(g, prio, x + LIST_X + LIST_W - 6 - (int) (font.width(prio) * 0.75f), ry + 3, MUTED, 0.75f);
            hits.add(new Hit(x + LIST_X, ry, LIST_W, ROW, () -> select(s.id())));
        }
        if (rows.isEmpty()) {
            text(g, list() == null ? "waiting for the server" : "no regions match", x + LIST_X + 6, y + LIST_Y + 6, MUTED, 0.75f);
        }
        if (rows.size() > visible) {
            int barH = Math.max(6, LIST_H * visible / rows.size());
            int barY = y + LIST_Y + 1 + (LIST_H - 2 - barH) * listScroll / Math.max(1, rows.size() - visible);
            g.fill(x + LIST_X + LIST_W - 3, barY, x + LIST_X + LIST_W - 1, barY + barH, LINE);
        }
        button(g, x + LIST_X, y + 204, 62, 14, inputMode == Input.NEW_ID ? "CANCEL" : "+ NEW", TQ, () -> {
            if (inputMode == Input.NEW_ID) {
                closeInput();
            } else {
                openInput(Input.NEW_ID, "", "new region id, Enter to create from the wand selection");
            }
        });
        button(g, x + LIST_X + 66, y + 204, 64, 14, "WAND", MUTED, () -> send(RegionActionPacket.of(RegionActionPacket.WAND, "")));
    }

    private void drawPane(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        RegionListPacket l = list();
        RegionListPacket.Summary s = l == null || selected == null ? null : l.find(selected);
        RegionDetailPacket d = detail();
        int px = x + PANE_X;
        if (s == null) {
            text(g, "Pick a region on the left, or select two corners with the wand and press + NEW.", px, y + 26, MUTED, 0.75f);
            drawInput(g, x, y);
            return;
        }
        text(g, s.name().equals(s.id()) ? s.id() : s.name() + "  (" + s.id() + ")", px, y + 22, TEXT, 1f);
        String meta = s.type().replace('_', ' ') + (s.bounds() == null ? "" : " - priority " + s.priority()) + (s.parent() == null ? "" : " - parent " + s.parent());
        text(g, meta, px, y + 33, MUTED, 0.75f);
        String where = s.bounds() == null ? "the whole world" : s.bounds() + " - " + s.bounds().sizeX() + " x " + s.bounds().sizeY() + " x " + s.bounds().sizeZ()
                + " - " + s.owners() + " owner" + (s.owners() == 1 ? "" : "s") + " - " + s.members() + " member" + (s.members() == 1 ? "" : "s");
        text(g, where, px, y + 41, MUTED, 0.75f);

        int tx = px;
        for (int i = 0; i < TABS.length; i++) {
            int ti = i;
            int w = (int) (font.width(TABS[i]) * 0.75f) + 8;
            chip(g, tx, y + 50, w, 12, TABS[i], tab == i, TQ, 0.75f, () -> {
                tab = ti;
                flagScroll = 0;
                closeInput();
            });
            tx += w + 2;
        }
        inset(g, px, y + CONTENT_Y, PANE_W, CONTENT_H);
        switch (tab) {
            case 0 -> drawMap(g, x, y, mouseX, mouseY, s);
            case 1 -> drawFlags(g, x, y, d);
            case 2 -> drawAccess(g, x, y, d);
            case 3 -> drawRanks(g, x, y, d);
            case 4 -> drawSale(g, x, y, s, d);
            case 5 -> drawMine(g, x, y, s, d);
            case 6 -> drawTeleport(g, x, y, d);
            default -> drawInfo(g, x, y, s, d);
        }
        drawInput(g, x, y);

        button(g, px, y + 204, 56, 14, "TELEPORT", TQ, () -> act(RegionActionPacket.TELEPORT, ""));
        button(g, px + 60, y + 204, 68, 14, "REDEFINE", MUTED, () -> act(RegionActionPacket.REDEFINE, ""));
        button(g, px + 132, y + 204, 50, 14, "SELECT", MUTED, () -> act(RegionActionPacket.SELECT_BOUNDS, ""));
        if (!s.type().equals("global")) {
            button(g, x + W - 8 - 58, y + 204, 58, 14, confirmDelete ? "SURE?" : "DELETE", BAD, () -> {
                if (confirmDelete) {
                    act(RegionActionPacket.DELETE, "");
                    confirmDelete = false;
                } else {
                    confirmDelete = true;
                }
            });
        }
        text(g, "Changes save at once. Esc closes.", px, y + 223, MUTED, 0.75f);
    }

    private void drawInput(GuiGraphics g, int x, int y) {
        if (inputMode == Input.NONE) {
            input.setVisible(false);
            return;
        }
        int ix = x + PANE_X + 4, iy = y + CONTENT_Y + CONTENT_H - 18;
        g.fill(ix, iy, ix + PANE_W - 8, iy + 16, BG);
        inset(g, ix, iy, PANE_W - 72, 16);
        input.setX(ix + 4);
        input.setY(iy + 4);
        input.setWidth(PANE_W - 80);
        input.setVisible(true);
        if (inputMode == Input.PLAYER) {
            button(g, ix + PANE_W - 70, iy + 1, 30, 14, "+OWN", TQ, () -> addPerson(RegionActionPacket.ADD_OWNER));
            button(g, ix + PANE_W - 38, iy + 1, 30, 14, "+MEM", VIO, () -> addPerson(RegionActionPacket.ADD_MEMBER));
        } else {
            button(g, ix + PANE_W - 70, iy + 1, 30, 14, "OK", TQ, this::submitInput);
            button(g, ix + PANE_W - 38, iy + 1, 30, 14, inputMode == Input.FLAG_VALUE ? "CLEAR" : "X", inputMode == Input.FLAG_VALUE ? BAD : MUTED, () -> {
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
        RegionListPacket.Summary hovered = map.draw(g, font, mx, my, l.regions(), selected, minecraft.player.blockPosition(), mouseX, mouseY);
        RegionListPacket.Summary target = hovered;
        hits.add(new Hit(mx, my, RegionMap.W, RegionMap.H, () -> {
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
        String scale = "1 px = " + map.bpp + (map.bpp == 1 ? " block" : " blocks") + " - wheel zooms - click a region - double-click opens FLAGS";
        text(g, scale, mx + 2, my + RegionMap.H + 2, MUTED, 0.6f);
        chip(g, mx + RegionMap.W - 70, my + 2, 68, 10, showRemote ? "HIDE OTHERS" : "OTHER SERVERS", showRemote, MUTED, 0.5f, () -> {
            showRemote = !showRemote;
            send(RegionActionPacket.of(showRemote ? RegionActionPacket.LIST_REMOTE : RegionActionPacket.LIST, selected == null ? "" : selected));
        });
        if (hovered != null) {
            String tip = hovered.id() + " - " + hovered.type().replace('_', ' ') + " - priority " + hovered.priority();
            text(g, tip, mx + RegionMap.W - 2 - (int) (font.width(tip) * 0.6f), my + RegionMap.H + 2, TEXT, 0.6f);
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
        chip(g, px + 4, cy + 3, 48, 10, onlySet ? "SET ONLY" : "ALL FLAGS", onlySet, TQ, 0.5f, () -> {
            onlySet = !onlySet;
            flagScroll = 0;
        });
        int set = d == null ? 0 : d.flags().size();
        text(g, set + " set - " + (FlagRegistry.all().size() - set) + " inherit" + (d != null && !d.inherited().isEmpty() ? " - " + d.inherited().size() + " from parent" : ""),
                px + 58, cy + 5, MUTED, 0.6f);
        List<Object> rows = flagRows(d);
        boolean hints = width >= 440;
        int rowH = hints ? 16 : 12;
        int listTop = cy + 15, listH = CONTENT_H - 17 - (inputMode == Input.FLAG_VALUE ? 18 : 0);
        int visible = listH / rowH;
        flagScroll = Mth.clamp(flagScroll, 0, Math.max(0, rows.size() - visible));
        g.enableScissor(px + 1, listTop, px + PANE_W - 1, listTop + listH);
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
                g.fill(px + 1, ry, px + PANE_W - 1, ry + rowH, 0x2219D3B0);
            }
            text(g, f.name(), px + 6, ry + 2, value != null ? TEXT : MUTED, 0.75f);
            if (hints) {
                String hint = inherited != null && value == null ? "inherits " + inherited : f.hint();
                text(g, font.plainSubstrByWidth(hint, 320), px + 6, ry + 9, inherited != null && value == null ? VIO : 0xFF6F7788, 0.5f);
            }
            if (f instanceof StateFlag) {
                stateSwitch(g, px + PANE_W - 78, ry + 1, value, f.name());
            } else {
                String shown = value != null ? value : inherited != null ? inherited : f.defaultValue() != null ? format(f) : "not set";
                int colour = value != null ? TEXT : MUTED;
                String clipped = font.plainSubstrByWidth(shown, 110);
                text(g, clipped, px + PANE_W - 80 - (int) (font.width(clipped) * 0.6f) + 72 - 40, ry + 3, colour, 0.6f);
                chip(g, px + PANE_W - 34, ry + 1, 30, 11, "edit", editing, TQ, 0.6f, () -> {
                    editingFlag = f.name();
                    openInput(Input.FLAG_VALUE, value == null ? "" : value, f.hint());
                });
            }
        }
        g.disableScissor();
        if (rows.size() > visible) {
            int barH = Math.max(6, listH * visible / rows.size());
            int barY = listTop + (listH - barH) * flagScroll / Math.max(1, rows.size() - visible);
            g.fill(px + PANE_W - 3, barY, px + PANE_W - 1, barY + barH, LINE);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String format(Flag f) {
        return f.format(f.defaultValue());
    }

    private void drawAccess(GuiGraphics g, int x, int y, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y, half = PANE_W / 2;
        text(g, "OWNERS", px + 6, cy + 5, TQ, 0.6f);
        text(g, "MEMBERS", px + half + 6, cy + 5, VIO, 0.6f);
        text(g, "may edit the region and build", px + 6, cy + 12, 0xFF6F7788, 0.5f);
        text(g, "may build and pass entry rules", px + half + 6, cy + 12, 0xFF6F7788, 0.5f);
        if (d != null) {
            people(g, px + 4, cy + 20, half - 8, d.owners(), RegionActionPacket.REMOVE_OWNER);
            people(g, px + half + 4, cy + 20, half - 8, d.members(), RegionActionPacket.REMOVE_MEMBER);
        }
        if (inputMode != Input.PLAYER) {
            button(g, px + 4, cy + CONTENT_H - 18, 100, 14, "+ ADD A PLAYER", TQ, () -> openInput(Input.PLAYER, "", "player name, then +OWN or +MEM"));
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
            text(g, "none", x + 2, y + 2, MUTED, 0.75f);
        } else if (list.size() > rows) {
            text(g, "+ " + (list.size() - rows) + " more", x + 2, y + rows * 12 + 2, MUTED, 0.6f);
        }
    }

    private void drawRanks(GuiGraphics g, int x, int y, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        text(g, "RANKS THAT MAY ENTER", px + 6, cy + 5, TQ, 0.6f);
        text(g, d == null || d.ranks().isEmpty() ? "any rank may enter (entry still applies)" : "only these ranks may enter, besides members",
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
            text(g, "LuckPerms was not found on this server, so no ranks are listed. You can still type one:", px + 6, cy + 24, MUTED, 0.6f);
        }
        int col = 0, row = 0;
        for (String rank : available) {
            boolean on = d.ranks().contains(rank.toLowerCase(Locale.ROOT));
            int bx = px + 6 + col * 86, by = cy + 22 + row * 13;
            chip(g, bx, by, 82, 11, (on ? "[x] " : "[ ] ") + font.plainSubstrByWidth(rank, 90), on, VIO, 0.6f,
                    () -> act(on ? RegionActionPacket.REMOVE_RANK : RegionActionPacket.ADD_RANK, rank));
            if (++col == 3) {
                col = 0;
                row++;
            }
            if (row > 5) {
                break;
            }
        }
        if (inputMode != Input.RANK) {
            button(g, px + 4, cy + CONTENT_H - 18, 90, 14, "+ TYPE A RANK", VIO, () -> openInput(Input.RANK, "", "LuckPerms group name, Enter to add"));
        }
    }

    private void drawTeleport(GuiGraphics g, int x, int y, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        String tp = d == null ? null : d.flags().get("teleport");
        String spawn = d == null ? null : d.flags().get("spawn");
        text(g, "TELEPORT POINT", px + 6, cy + 4, TQ, 0.6f);
        text(g, tp == null ? "not set: the top of the region's centre" : font.plainSubstrByWidth(tp, 200), px + 80, cy + 4, tp == null ? MUTED : TEXT, 0.6f);
        chip(g, px + 6, cy + 12, 44, 10, "SET HERE", false, TQ, 0.5f, () -> flag("teleport", "here"));
        chip(g, px + 54, cy + 12, 30, 10, "CLEAR", false, BAD, 0.5f, () -> flag("teleport", ""));
        chip(g, px + 88, cy + 12, 24, 10, "GO", false, MUTED, 0.5f, () -> act(RegionActionPacket.TELEPORT, ""));
        text(g, "RESPAWN POINT", px + 6, cy + 26, TQ, 0.6f);
        text(g, spawn == null ? "not set: normal respawn" : font.plainSubstrByWidth(spawn, 200), px + 80, cy + 26, spawn == null ? MUTED : TEXT, 0.6f);
        chip(g, px + 6, cy + 34, 44, 10, "SET HERE", false, TQ, 0.5f, () -> flag("spawn", "here"));
        chip(g, px + 54, cy + 34, 30, 10, "CLEAR", false, BAD, 0.5f, () -> flag("spawn", ""));

        text(g, "TRIGGERS", px + 6, cy + 50, GOLD, 0.6f);
        text(g, "move players on entering, leaving, a timer, or server shutdown", px + 50, cy + 50, 0xFF6F7788, 0.5f);
        java.util.Map<String, String> data = d == null ? java.util.Map.of() : d.data();
        int ry = cy + 59, n = 0;
        for (int i = 1; i <= 20; i++) {
            String line = data.get("trigger." + i);
            if (line == null) {
                break;
            }
            n++;
            if (n > 5) {
                text(g, "+ more: /rg trigger list " + selected, px + 6, ry, MUTED, 0.5f);
                ry += 8;
                break;
            }
            text(g, i + ". " + font.plainSubstrByWidth(line, 380), px + 6, ry, TEXT, 0.5f);
            int index = i;
            chip(g, px + PANE_W - 18, ry - 1, 12, 8, "x", false, BAD, 0.5f, () -> act(RegionActionPacket.TRIGGER_REMOVE, String.valueOf(index)));
            ry += 8;
        }
        if (n == 0) {
            text(g, "none", px + 6, ry, MUTED, 0.5f);
            ry += 8;
        }
        if (inputMode != Input.TRIGGER) {
            String[] causes = {"enter", "exit", "timer", "shutdown"};
            for (int i = 0; i < causes.length; i++) {
                String c = causes[i];
                chip(g, px + 6 + i * 46, ry + 2, 44, 10, c.toUpperCase(java.util.Locale.ROOT), triggerCause.equals(c), GOLD, 0.5f, () -> triggerCause = c);
            }
            chip(g, px + 6 + 4 * 46, ry + 2, 50, 10, "+ ADD", false, GOLD, 0.5f, () -> openInput(Input.TRIGGER, "here",
                    triggerCause.equals("timer") ? "seconds, then: here | world x y z | server/world x y z [message]" : "here | world x y z | server/world x y z [message]"));
        }
        text(g, "cross-server destinations (server/world) are carried out by BSP Teleport", px + 6, cy + CONTENT_H - 10, 0xFF6F7788, 0.5f);
    }

    private void drawSale(GuiGraphics g, int x, int y, RegionListPacket.Summary s, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        java.util.Map<String, String> data = d == null ? java.util.Map.of() : d.data();
        String type = s.type();
        if (type.equals("global")) {
            text(g, "The world default cannot be sold.", px + 6, cy + 6, MUTED, 0.75f);
            return;
        }
        if (type.equals("claim")) {
            text(g, "OWNED CLAIM", px + 6, cy + 5, VIO, 0.6f);
            text(g, "bought for " + data.getOrDefault("boughtFor", "?") + " pieces, origin: " + data.getOrDefault("origin", "?"), px + 6, cy + 13, TEXT, 0.75f);
            text(g, "The owner gives it up with /claim abandon; an admin can too. An admin plot then goes back on sale.", px + 6, cy + 24, 0xFF6F7788, 0.5f);
            text(g, "BUYER MAY SET: " + data.getOrDefault("playerFlags", "the config list"), px + 6, cy + 36, GOLD, 0.6f);
            return;
        }
        text(g, "PLOT FOR SALE", px + 6, cy + 5, GOLD, 0.6f);
        text(g, "a fixed price for this whole region; the buyer becomes its owner", px + 6, cy + 12, 0xFF6F7788, 0.5f);
        String price = data.getOrDefault("price", "0");
        text(g, type.equals("plot_for_sale") ? "for sale at " + price + " pieces" : "not for sale", px + 6, cy + 20, type.equals("plot_for_sale") ? TEXT : MUTED, 0.75f);
        button(g, px + 6, cy + 31, 70, 12, "SET PRICE", GOLD, () -> data("price", price, "price in Shatter Pieces; 0 takes it off sale"));
        text(g, "OPEN AREA", px + 6, cy + 52, GOLD, 0.6f);
        text(g, "players outline their own plot inside and pay per block of ground", px + 6, cy + 59, 0xFF6F7788, 0.5f);
        String cost = data.getOrDefault("costPerBlock", "0");
        text(g, type.equals("open_for_buying") ? cost + " pieces per block, plots " + data.getOrDefault("minSize", "config") + " to " + data.getOrDefault("maxSize", "config") + " blocks a side" : "not open",
                px + 6, cy + 67, type.equals("open_for_buying") ? TEXT : MUTED, 0.75f);
        button(g, px + 6, cy + 78, 70, 12, "SET COST", GOLD, () -> data("costPerBlock", cost, "Shatter Pieces per block of ground; 0 closes the area"));
        button(g, px + 80, cy + 78, 56, 12, "MIN SIZE", MUTED, () -> data("minSize", data.getOrDefault("minSize", ""), "smallest side in blocks; empty uses the config"));
        button(g, px + 140, cy + 78, 56, 12, "MAX SIZE", MUTED, () -> data("maxSize", data.getOrDefault("maxSize", ""), "largest side in blocks; empty uses the config"));
        text(g, "BUYER MAY SET: " + data.getOrDefault("playerFlags", "the config list"), px + 6, cy + 96, GOLD, 0.6f);
        chip(g, px + PANE_W - 40, cy + 94, 34, 10, "edit", false, GOLD, 0.5f, () -> data("playerFlags", data.getOrDefault("playerFlags", ""), "flags the buyer may set, comma separated; 'default' for the config list"));
        text(g, "Buying happens in BSP Core's shop screen, or with /claim buy until it exists.", px + 6, cy + 108, 0xFF6F7788, 0.5f);
    }

    private void drawMine(GuiGraphics g, int x, int y, RegionListPacket.Summary s, @Nullable RegionDetailPacket d) {
        int px = x + PANE_X, cy = y + CONTENT_Y;
        if (s.type().equals("global")) {
            text(g, "The world default cannot be a mine.", px + 6, cy + 6, MUTED, 0.75f);
            return;
        }
        if (!s.type().equals("mine")) {
            text(g, "Not a mine. A mine refills itself with the blocks you list, on a timer, and lets anyone inside dig.", px + 6, cy + 6, MUTED, 0.6f);
            text(g, "Making it a mine allows breaking and refuses placing inside.", px + 6, cy + 14, 0xFF6F7788, 0.5f);
            button(g, px + 6, cy + 26, 90, 12, "MAKE THIS A MINE", GOLD, () -> act(RegionActionPacket.MINE_CREATE, ""));
            return;
        }
        java.util.Map<String, String> data = d == null ? java.util.Map.of() : d.data();
        text(g, "FILL TABLE", px + 6, cy + 5, GOLD, 0.6f);
        text(g, "the heaviest block fills the mine, the others grow as veins", px + 70, cy + 5, 0xFF6F7788, 0.5f);
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
            text(g, "empty: add blocks below (e.g. minecraft:stone 90, minecraft:iron_ore 8)", px + 6, ry, MUTED, 0.6f);
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
            text(g, row[1] + "  (" + (total > 0 ? 100 * w / total : 0) + "%)", px + 130, ry, MUTED, 0.6f);
            String block = row[0];
            chip(g, px + 176, ry - 1, 12, 8, "x", false, BAD, 0.5f, () -> send(new RegionActionPacket(RegionActionPacket.MINE_ADD, selected == null ? "" : selected, block, "0")));
            ry += 8;
        }
        if (rows.size() > 7) {
            text(g, "+ " + (rows.size() - 7) + " more (see /rg mine info)", px + 6, ry, MUTED, 0.5f);
            ry += 8;
        }
        button(g, px + 6, ry + 1, 70, 11, "+ ADD BLOCK", GOLD, () -> openInput(Input.MINE_BLOCK, "", "block id and weight, e.g. minecraft:iron_ore 8"));
        int ty = cy + 86;
        text(g, "RESET", px + 6, ty, GOLD, 0.6f);
        String secs = data.getOrDefault("secondsToReset", "-1");
        String interval = data.getOrDefault("intervalSeconds", "0");
        text(g, "-1".equals(secs) || "0".equals(interval) ? "no automatic reset" : "every " + interval + " s, next in " + secs + " s", px + 40, ty, TEXT, 0.6f);
        button(g, px + 6, ty + 9, 56, 11, "INTERVAL", MUTED, () -> openInput(Input.MINE_INTERVAL, "0".equals(interval) ? "" : interval, "seconds between resets; empty stops them"));
        button(g, px + 66, ty + 9, 60, 11, "RESET NOW", BAD, () -> act(RegionActionPacket.MINE_RESET, ""));
        button(g, px + 130, ty + 9, 56, 11, Boolean.parseBoolean(data.getOrDefault("hasSpawn", "false")) ? "MOVE SPAWN" : "SET SPAWN", TQ, () -> act(RegionActionPacket.MINE_SPAWN, ""));
        button(g, px + 190, ty + 9, 70, 11, Boolean.parseBoolean(data.getOrDefault("hasShell", "false")) ? "RESIZE SHELL" : "MAKE SHELL", MUTED, () -> openInput(Input.MINE_SHELL, "5", "blocks around the mine where only the mine can be dug"));
        text(g, "players inside are moved to the spawn (or above the mine) when it resets; warnings 60 s and 10 s before", px + 6, ty + 23, 0xFF6F7788, 0.5f);
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
        text(g, "NAME", px + 6, cy + 5, TQ, 0.6f);
        text(g, s.name(), px + 6, cy + 12, TEXT, 0.75f);
        chip(g, px + 110, cy + 10, 30, 11, "edit", false, TQ, 0.6f, () -> openInput(Input.NAME, s.name(), "display name, Enter to save"));
        boolean global = s.type().equals("global");
        text(g, "TYPE", px + 6, cy + 26, TQ, 0.6f);
        String[] types = {"protected", "plot_for_sale", "open_for_buying", "claim", "mine"};
        String[] labels = {"protected", "for sale", "open", "claim", "mine"};
        for (int i = 0; i < types.length; i++) {
            String t = types[i];
            chip(g, px + 6 + i * 50, cy + 33, 48, 11, labels[i], s.type().equals(t), 0xFF000000 | typeColour(t), 0.6f, global ? null : () -> act(RegionActionPacket.TYPE, t));
        }
        text(g, "PRIORITY  " + (global ? "lowest (world default)" : String.valueOf(s.priority())), px + 6, cy + 49, TQ, 0.6f);
        if (!global) {
            int[] steps = {-10, -1, 1, 10};
            for (int i = 0; i < steps.length; i++) {
                int step = steps[i];
                chip(g, px + 110 + i * 26, cy + 47, 24, 11, (step > 0 ? "+" : "") + step, false, TEXT, 0.6f, () -> act(RegionActionPacket.PRIORITY, String.valueOf(s.priority() + step)));
            }
            text(g, "higher wins where regions overlap", px + 6, cy + 56, 0xFF6F7788, 0.5f);
        }
        text(g, "PARENT  " + (s.parent() == null ? "none" : s.parent()), px + 6, cy + 66, TQ, 0.6f);
        if (!global) {
            chip(g, px + 110, cy + 64, 30, 11, "set", false, TQ, 0.6f, () -> openInput(Input.PARENT, s.parent() == null ? "" : s.parent(), "parent region id, Enter to set, empty for none"));
            text(g, "unset flags take the parent's values", px + 6, cy + 73, 0xFF6F7788, 0.5f);
        }
        if (s.bounds() != null) {
            Cuboid b = s.bounds();
            text(g, "BOUNDS", px + 6, cy + 83, TQ, 0.6f);
            text(g, b.min().toShortString() + "  to  " + b.max().toShortString(), px + 6, cy + 90, TEXT, 0.6f);
            text(g, b.sizeX() + " x " + b.sizeY() + " x " + b.sizeZ() + " = " + b.volume() + " blocks, " + b.area() + " on the ground", px + 6, cy + 97, MUTED, 0.6f);
        }
        if (d != null) {
            text(g, "version " + d.version() + " - changed " + ((System.currentTimeMillis() - d.updatedAt()) / 60000) + " min ago", px + 6, cy + 108, 0xFF6F7788, 0.5f);
            if (!d.inherited().isEmpty()) {
                text(g, d.inherited().size() + " flags inherited from the parent: see FLAGS (violet hints)", px + 6, cy + 116, VIO, 0.5f);
            }
        }
        text(g, Region.GLOBAL_ID.equals(s.id()) ? "The world default applies everywhere no other region decides." : "", px + 6, cy + 124, MUTED, 0.5f);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        for (Hit h : hits) {
            if (h.contains(mx, my)) {
                h.action().run();
                return true;
            }
        }
        if (!(mx >= left() + PANE_X && my >= top() + 204 && my < top() + 218)) {
            confirmDelete = false;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int x = left(), y = top();
        if (mx >= x + LIST_X && mx < x + LIST_X + LIST_W && my >= y + LIST_Y && my < y + LIST_Y + LIST_H) {
            listScroll -= (int) Math.signum(delta);
            return true;
        }
        if (mx >= x + PANE_X && mx < x + PANE_X + PANE_W && my >= y + CONTENT_Y && my < y + CONTENT_Y + CONTENT_H) {
            if (tab == 0) {
                zoomedByHand = true;
                map.zoom(delta > 0 ? -1 : 1);
            } else if (tab == 1) {
                flagScroll -= (int) Math.signum(delta);
            }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
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

    @Override
    public void onClose() {
        super.onClose();
    }
}
