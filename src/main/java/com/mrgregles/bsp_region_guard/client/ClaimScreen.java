package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.network.ClaimActionPacket;
import com.mrgregles.bsp_region_guard.network.ClaimOpenPacket;
import com.mrgregles.bsp_region_guard.network.RegionDetailPacket;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's claim screen (owner decision D24, "claim card"): the claim and its surroundings on a map, who may
 * build, the flags the owner may set as plain sentences, messages, and giving the claim up. Every change is an
 * {@link ClaimActionPacket}; the server checks ownership and answers with fresh data.
 */
public class ClaimScreen extends Screen {
    private static final int W = 420, H = 236;
    private static final int BG = RegionScreen.BG, INSET = RegionScreen.INSET, LINE = RegionScreen.LINE, TQ = RegionScreen.TQ, VIO = RegionScreen.VIO,
            GOLD = RegionScreen.GOLD, BAD = RegionScreen.BAD, TEXT = RegionScreen.TEXT, MUTED = RegionScreen.MUTED, HINT = 0xFF6F7788;
    private static final int MAP_X = 8, MAP_Y = 24, RIGHT_X = 190, RIGHT_W = 222;

    /** How each flag reads on this screen. Unlisted allowed flags fall back to their name and hint. */
    /** Flag name to the language key of its plain sentence on this screen. */
    private static final Map<String, String> SENTENCES = new LinkedHashMap<>();
    private static final Map<String, String> MESSAGES = new LinkedHashMap<>();

    static {
        SENTENCES.put("entry", "strangers_may_enter");
        SENTENCES.put("exit", "strangers_may_leave");
        SENTENCES.put("chest-access", "strangers_may_open_chests");
        SENTENCES.put("use", "strangers_may_use_doors_and_switches");
        SENTENCES.put("interact", "strangers_may_use_machines_and_animals");
        SENTENCES.put("ride", "strangers_may_ride");
        SENTENCES.put("sleep", "strangers_may_sleep_here");
        SENTENCES.put("block-break", "strangers_may_break_blocks");
        SENTENCES.put("block-place", "strangers_may_place_blocks");
        SENTENCES.put("build", "strangers_may_build");
        SENTENCES.put("pvp", "pvp_inside");
        SENTENCES.put("mob-spawning", "mobs_spawn_inside");
        SENTENCES.put("item-drop", "items_may_be_dropped");
        SENTENCES.put("item-pickup", "items_may_be_picked_up");
        SENTENCES.put("fire-spread", "fire_spreads");
        SENTENCES.put("tnt", "tnt_explodes");
        MESSAGES.put("greeting", "on_entering");
        MESSAGES.put("farewell", "on_leaving");
        MESSAGES.put("entry-deny-message", "when_refused");
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private enum Input { NONE, PLAYER, MESSAGE }

    private final List<Hit> hits = new ArrayList<>();
    private final RegionMap map = RegionMap.create();
    @Nullable
    private String selected;
    private List<String> allowed;
    private int limit;
    private boolean confirmGiveUp;
    private boolean zoomedByHand;
    private EditBox input;
    private Input inputMode = Input.NONE;
    @Nullable
    private String editingMessage;

    public ClaimScreen(ClaimOpenPacket open) {
        super(Component.literal(Tr.t("my_claim")));
        this.selected = open.select();
        this.allowed = open.allowed();
        this.limit = open.limit();
    }

    // ------------------------------------------------------------------ data

    void onOpen(ClaimOpenPacket msg) {
        selected = msg.select();
        allowed = msg.allowed();
        limit = msg.limit();
        confirmGiveUp = false;
        frameMap();
    }

    void onList(RegionListPacket msg) {
        if (selected != null && msg.find(selected) == null) {
            List<String> mine = mine();
            selected = mine.isEmpty() ? null : mine.get(0);
            if (selected != null) {
                send(ClaimActionPacket.of(ClaimActionPacket.SELECT, selected));
            }
        }
        confirmGiveUp = false;
    }

    void onDetail(RegionDetailPacket msg) {
        if (msg.id().equals(selected)) {
            frameMap();
        }
    }

    @Nullable
    private RegionDetailPacket detail() {
        RegionDetailPacket d = RegionClient.detail();
        return d != null && d.id().equals(selected) ? d : null;
    }

    /** The player's own claims, from the list the server sent (claims the player belongs to as owner). */
    private List<String> mine() {
        List<String> out = new ArrayList<>();
        RegionListPacket l = RegionClient.list();
        if (l == null || minecraft == null || minecraft.player == null) {
            return out;
        }
        for (RegionListPacket.Summary s : l.regions()) {
            if (s.type().equals("claim")) {
                out.add(s.id());
            }
        }
        return out;
    }

    private void select(String id) {
        selected = id;
        confirmGiveUp = false;
        zoomedByHand = false;
        closeInput();
        send(ClaimActionPacket.of(ClaimActionPacket.SELECT, id));
        frameMap();
    }

    private void frameMap() {
        RegionListPacket l = RegionClient.list();
        RegionListPacket.Summary s = l == null || selected == null ? null : l.find(selected);
        BlockPos player = minecraft != null && minecraft.player != null ? minecraft.player.blockPosition() : BlockPos.ZERO;
        map.frame(s == null ? null : s.bounds(), player, zoomedByHand);
        if (s != null && !zoomedByHand) {
            map.zoom(1); // one step out, so the surroundings show
        }
    }

    private static void send(ClaimActionPacket packet) {
        RegionNetwork.CHANNEL.sendToServer(packet);
    }

    private void act(String action, String a, String b) {
        if (selected != null) {
            send(new ClaimActionPacket(action, selected, a, b));
        }
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void init() {
        input = new EditBox(font, left() + RIGHT_X + 7, top() + H - 44, RIGHT_W - 80, 10, Component.literal(Tr.t("value_label")));
        input.setBordered(false);
        input.setMaxLength(200);
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
        editingMessage = null;
    }

    private void submitInput() {
        String value = input.getValue().trim();
        if (inputMode == Input.PLAYER) {
            if (!value.isEmpty()) {
                act(ClaimActionPacket.ADD_MEMBER, value, "");
            }
        } else if (inputMode == Input.MESSAGE && editingMessage != null) {
            act(ClaimActionPacket.FLAG, editingMessage, value);
        }
        closeInput();
    }

    // ------------------------------------------------------------------ drawing

    private void text(GuiGraphics g, String s, int x, int y, int colour, float scale) {
        RegionScreen.text(g, font, s, x, y, colour, scale);
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
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

    /** An on / off / grey switch. {@code value} is "allow", "deny" or null (follow the area). */
    private void toggle(GuiGraphics g, int x, int y, String sentence, @Nullable String value, String flagName) {
        int colour = "allow".equals(value) ? TQ : "deny".equals(value) ? BAD : LINE;
        g.fill(x, y, x + 14, y + 7, INSET);
        frame(g, x, y, 14, 7, colour);
        int knob = "allow".equals(value) ? x + 8 : x + 1;
        g.fill(knob, y + 1, knob + 5, y + 6, "allow".equals(value) ? TQ : "deny".equals(value) ? BAD : MUTED);
        text(g, sentence, x + 18, y, value == null ? MUTED : TEXT, 0.75f);
        String next = value == null ? "allow" : "allow".equals(value) ? "deny" : "";
        hits.add(new Hit(x, y - 1, 100, 9, () -> act(ClaimActionPacket.FLAG, flagName, next)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        hits.clear();
        renderBackground(g);
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, BG);
        frame(g, x, y, W, H, VIO);
        text(g, Tr.t("my_claim_title"), x + 10, y + 8, VIO, 1f);

        RegionListPacket l = RegionClient.list();
        RegionListPacket.Summary s = l == null || selected == null ? null : l.find(selected);
        RegionDetailPacket d = detail();
        List<String> mine = mine();

        if (s == null) {
            text(g, mine.isEmpty() ? Tr.t("you_have_no_claim_in_this_world_yet") : Tr.t("pick_a_claim"), x + 10, y + 26, MUTED, 0.75f);
            text(g, Tr.t("plots_for_sale_are_the_gold_boxes_in_the_world_b"), x + 10, y + 36, HINT, 0.6f);
            text(g, Tr.t("or_with_claim_buy_open_areas_let_you_outline_you"), x + 10, y + 43, HINT, 0.6f);
            chip(g, x + 10, y + 56, 60, 12, Tr.t("plot_tool"), true, MUTED, 0.75f, () -> send(ClaimActionPacket.of(ClaimActionPacket.TOOL, "")));
            drawForSale(g, x, y + 76);
            text(g, Tr.t("claims_used", mine.size(), limit), x + 10, y + H - 14, MUTED, 0.6f);
            super.render(g, mouseX, mouseY, partial);
            return;
        }

        // header: arrows through my claims
        int idx = mine.indexOf(s.id());
        if (mine.size() > 1) {
            chip(g, x + 70, y + 6, 12, 12, "<", false, TEXT, 0.75f, () -> select(mine.get((idx - 1 + mine.size()) % mine.size())));
            chip(g, x + 70 + 12 + 4 + (int) (font.width(s.id()) * 1f) + 6, y + 6, 12, 12, ">", false, TEXT, 0.75f, () -> select(mine.get((idx + 1) % mine.size())));
        }
        text(g, s.id(), x + 70 + (mine.size() > 1 ? 16 : 0), y + 8, TEXT, 1f);
        String meta = (s.parent() == null ? "" : Tr.t("claim_in", s.parent())) + (s.bounds() == null ? "" : s.bounds().sizeX() + " x " + s.bounds().sizeZ())
                + (d == null ? "" : Tr.t("claim_bought_for", d.data().getOrDefault("boughtFor", "?"))) + Tr.t("claim_index", idx + 1, mine.size());
        text(g, meta, x + W - 10 - (int) (font.width(meta) * 0.6f), y + 10, MUTED, 0.6f);

        // map
        if (minecraft != null && minecraft.player != null && l != null) {
            g.fill(x + MAP_X, y + MAP_Y, x + MAP_X + RegionMap.W / 2 + 46, y + MAP_Y + RegionMap.H + 4, INSET);
            map.draw(g, font, x + MAP_X + 2, y + MAP_Y + 2, l.regions(), selected, minecraft.player.blockPosition(), mouseX, mouseY);
            // the map is 262 wide; the left column is narrower, so cover the overflow
            g.fill(x + MAP_X + RegionMap.W / 2 + 46, y + MAP_Y, x + RIGHT_X - 4, y + MAP_Y + RegionMap.H + 4, BG);
            hits.add(new Hit(x + MAP_X, y + MAP_Y, RIGHT_X - MAP_X - 4, RegionMap.H + 4, () -> {
            }));
            text(g, Tr.t("your_plot_and_what_is_around_it_wheel_zooms"), x + MAP_X, y + MAP_Y + RegionMap.H + 7, HINT, 0.6f);
        }
        chip(g, x + MAP_X, y + H - 44, 56, 12, Tr.t("plot_tool"), true, MUTED, 0.75f, () -> send(ClaimActionPacket.of(ClaimActionPacket.TOOL, "")));
        chip(g, x + MAP_X + 60, y + H - 44, 56, 12, Tr.t("enlarge"), true, MUTED, 0.75f, () -> act(ClaimActionPacket.ENLARGE, "", ""));
        chip(g, x + MAP_X + 120, y + H - 44, 58, 12, Tr.t("teleport"), true, TQ, 0.75f, () -> act(ClaimActionPacket.TELEPORT, "", ""));
        text(g, Tr.t("enlarge_outline_a_bigger_plot_around_this_one_wi"), x + MAP_X, y + H - 30, HINT, 0.5f);

        // right column
        int rx = x + RIGHT_X, ry = y + MAP_Y;
        text(g, Tr.t("who_may_build_here"), rx, ry, TQ, 0.6f);
        int cx = rx, cy = ry + 9;
        if (d != null) {
            for (RegionDetailPacket.Person p : d.owners()) {
                int w = (int) (font.width(p.name()) * 0.6f) + 8;
                chip(g, cx, cy, w, 10, p.name(), true, VIO, 0.6f, null);
                cx += w + 3;
            }
            for (RegionDetailPacket.Person p : d.members()) {
                int w = (int) (font.width(p.name()) * 0.6f) + 20;
                if (cx + w > x + W - 10) {
                    cx = rx;
                    cy += 12;
                }
                chip(g, cx, cy, w, 10, p.name() + "  x", false, VIO, 0.6f, () -> act(ClaimActionPacket.REMOVE_MEMBER, p.uuid().toString(), ""));
                cx += w + 3;
            }
        }
        if (cx + 30 > x + W - 10) {
            cx = rx;
            cy += 12;
        }
        chip(g, cx, cy, 28, 10, Tr.t("add_member"), inputMode == Input.PLAYER, TQ, 0.6f, () -> openInput(Input.PLAYER, "", Tr.t("player_name_enter_to_add")));

        int fy = cy + 18;
        text(g, Tr.t("rules"), rx, fy, TQ, 0.6f);
        text(g, Tr.t("grey_follow_the_area_s_rule"), rx + 40, fy, HINT, 0.5f);
        fy += 9;
        List<String> switches = new ArrayList<>(), messages = new ArrayList<>(), others = new ArrayList<>();
        for (String name : allowed) {
            Flag<?> f = FlagRegistry.get(name);
            if (f == null) {
                continue;
            }
            if (MESSAGES.containsKey(name)) {
                messages.add(name);
            } else if (f instanceof StateFlag) {
                switches.add(name);
            } else {
                others.add(name);
            }
        }
        int col = 0;
        for (String name : switches) {
            String value = d == null ? null : d.flags().get(name);
            String sentence = SENTENCES.containsKey(name) ? Tr.t(SENTENCES.get(name)) : name.replace('-', ' ');
            toggle(g, rx + col * 112, fy, font.plainSubstrByWidth(sentence, 120), value, name);
            if (++col == 2) {
                col = 0;
                fy += 11;
            }
        }
        if (col == 1) {
            fy += 11;
        }
        if (switches.isEmpty() && messages.isEmpty()) {
            text(g, Tr.t("the_admin_has_not_allowed_any_rules_on_this_plot"), rx, fy, MUTED, 0.6f);
            fy += 9;
        }
        if (!messages.isEmpty()) {
            fy += 3;
            text(g, Tr.t("messages"), rx, fy, TQ, 0.6f);
            fy += 9;
            for (String name : messages) {
                String value = d == null ? null : d.flags().get(name);
                text(g, Tr.t(MESSAGES.get(name)), rx, fy, MUTED, 0.6f);
                String shown = value == null ? Tr.t("not_set") : Tr.t("quoted", font.plainSubstrByWidth(value, 150));
                text(g, shown, rx + 50, fy, value == null ? MUTED : TEXT, 0.6f);
                String flag = name;
                chip(g, x + W - 10 - 26, fy - 1, 26, 9, Tr.t("edit"), flag.equals(editingMessage), TQ, 0.5f, () -> {
                    editingMessage = flag;
                    openInput(Input.MESSAGE, value == null ? "" : value, Tr.t("message_enter_to_save_empty_clears"));
                });
                fy += 10;
            }
        }
        for (String name : others) {
            Flag<?> f = FlagRegistry.get(name);
            String value = d == null ? null : d.flags().get(name);
            text(g, name + ": " + (value == null ? Tr.t("not_set") : value), rx, fy, value == null ? MUTED : TEXT, 0.6f);
            text(g, Tr.t("claim_flag_command", s.id(), name), rx, fy + 7, HINT, 0.5f);
            fy += 15;
        }

        if (inputMode != Input.NONE) {
            int ix = rx, iy = y + H - 48;
            g.fill(ix, iy, ix + RIGHT_W, iy + 16, BG);
            g.fill(ix, iy, ix + RIGHT_W - 34, iy + 16, INSET);
            frame(g, ix, iy, RIGHT_W - 34, 16, LINE);
            input.setX(ix + 4);
            input.setY(iy + 4);
            input.setWidth(RIGHT_W - 42);
            input.setVisible(true);
            chip(g, ix + RIGHT_W - 30, iy + 1, 30, 14, Tr.t("ok"), true, TQ, 0.75f, this::submitInput);
        } else {
            input.setVisible(false);
        }

        String refund = d == null ? "" : Tr.t("give_up_refund", d.data().getOrDefault("boughtFor", "?"));
        text(g, refund, rx, y + H - 28, HINT, 0.5f);
        chip(g, x + W - 10 - 96, y + H - 20, 96, 12, confirmGiveUp ? Tr.t("really_give_up") : Tr.t("give_up_this_claim"), true, BAD, 0.75f, () -> {
            if (confirmGiveUp) {
                act(ClaimActionPacket.ABANDON, "", "");
                confirmGiveUp = false;
            } else {
                confirmGiveUp = true;
            }
        });
        text(g, Tr.t("changes_save_at_once_esc_closes"), x + 10, y + H - 14, MUTED, 0.6f);
        super.render(g, mouseX, mouseY, partial);
    }

    /** With no claim selected: the plots for sale and open areas the server lists for this player. */
    private void drawForSale(GuiGraphics g, int x, int y) {
        RegionListPacket l = RegionClient.list();
        if (l == null) {
            return;
        }
        text(g, Tr.t("for_sale_in_this_world"), x + 10, y, GOLD, 0.6f);
        int ry = y + 10;
        for (RegionListPacket.Summary s : l.regions()) {
            if (s.type().equals("plot_for_sale") || s.type().equals("open_for_buying")) {
                Cuboid b = s.bounds();
                String line = s.id() + "  " + (b == null ? "" : b.sizeX() + " x " + b.sizeZ()) + (s.type().equals("open_for_buying") ? Tr.t("sale_line_open") : Tr.t("sale_line_plot"));
                text(g, line, x + 10, ry, TEXT, 0.6f);
                ry += 9;
                if (ry > top() + H - 30) {
                    break;
                }
            }
        }
        if (ry == y + 10) {
            text(g, Tr.t("nothing_for_sale_right_now"), x + 10, ry, MUTED, 0.6f);
        }
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
        confirmGiveUp = false;
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int x = left(), y = top();
        if (mx >= x + MAP_X && mx < x + RIGHT_X && my >= y + MAP_Y && my < y + MAP_Y + RegionMap.H) {
            zoomedByHand = true;
            map.zoom(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inputMode != Input.NONE && input.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {
                submitInput();
                return true;
            }
            if (keyCode == 256) {
                closeInput();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
