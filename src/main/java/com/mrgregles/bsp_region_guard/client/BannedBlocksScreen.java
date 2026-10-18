package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.RegionActionPacket;
import com.mrgregles.bsp_region_guard.network.RegionDetailPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import com.mrgregles.bsp_region_guard.protection.BannedBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.CreativeModeTabRegistry;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The BANNED BLOCKS editor for one region (owner decisions D51 to D56, mock-up option C): a big grid of banned
 * blocks on the left, Minecraft's creative tabs on the right. Pick a rule on the bar (PLACE, USE, BREAK, CARRY),
 * then drag blocks from the right into the grid, or shift-click them. Right-click a banned block to step through
 * its rules, hover it for its four switches, drag it back to the right (or press x) to free it. Every change is sent
 * at once as the region's {@code deny-place}, {@code deny-use}, {@code deny-break} and {@code deny-carry} lists, so
 * the server checks it exactly as it checks {@code /rg flag}.
 */
public class BannedBlocksScreen extends HoloScreen {
    private static final int W = RegionScreen.W, H = RegionScreen.H;
    private static final int BG = RegionScreen.BG, INSET = RegionScreen.INSET, LINE = RegionScreen.LINE, TQ = RegionScreen.TQ,
            VIO = RegionScreen.VIO, GOLD = RegionScreen.GOLD, BAD = RegionScreen.BAD, TEXT = RegionScreen.TEXT, MUTED = RegionScreen.MUTED;
    private static final int HINT = 0xFF6F7788, SLOT = 0xFF171B22, SLOT_LINE = 0xFF262B35;
    private static final int CELL = 17;
    /** The banned grid and the browser, in screen units relative to the screen's corner. */
    private static final int LIST_X = 12, LIST_Y = 42, LIST_COLS = 9, LIST_ROWS = 8;
    private static final int BROWSE_X = 188, BROWSE_Y = 55, BROWSE_COLS = 13, BROWSE_ROWS = 8;
    private static final int TAB_X = 184, TAB_Y = 21, TAB_SIZE = 18, TABS_SHOWN = 11;

    /** The four rules, in the order of the bar, with their flags and colours. */
    enum Rule {
        PLACE("deny-place", GOLD), USE("deny-use", TQ), BREAK("deny-break", VIO), CARRY("deny-carry", BAD);

        final String flag;
        final int colour;

        Rule(String flag, int colour) {
            this.flag = flag;
            this.colour = colour;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Screen parent;
    private final String regionId;
    /** Banned entries in the order they were added, with their rules. Entries are ids, {@code @mod} or {@code #tag}. */
    private final Map<String, EnumSet<Rule>> banned = new LinkedHashMap<>();
    private final Map<String, ItemStack> icons = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private List<CreativeModeTab> tabs = List.of();
    private List<ItemStack> shown = List.of();
    private int tab, tabScroll, listScroll, browseScroll;
    private Rule brush = Rule.PLACE;
    @Nullable
    private String hoverEntry;
    /** The browser block the mouse was last over, for BAN WHOLE MOD. */
    private ItemStack lastBrowsed = ItemStack.EMPTY;
    /** What is being dragged: a browser block (to ban) or a banned entry (to free). */
    @Nullable
    private ItemStack dragStack;
    @Nullable
    private String dragEntry;
    private EditBox search;
    private String status = "";

    public BannedBlocksScreen(Screen parent, String regionId) {
        super(Component.literal(Tr.t("banned_blocks")), W, H);
        this.parent = parent;
        this.regionId = regionId;
        load();
    }

    // ------------------------------------------------------------------ data

    /** Reads the region's four lists from the last detail the server sent. */
    private void load() {
        banned.clear();
        RegionDetailPacket d = RegionClient.detail();
        if (d == null || !d.id().equals(regionId)) {
            return;
        }
        for (Rule r : Rule.values()) {
            String v = d.flags().get(r.flag);
            if (v == null || v.isBlank()) {
                continue;
            }
            for (String e : v.split(",")) {
                String entry = e.trim().toLowerCase(Locale.ROOT);
                if (!entry.isEmpty()) {
                    banned.computeIfAbsent(entry, k -> EnumSet.noneOf(Rule.class)).add(r);
                }
            }
        }
    }

    /** Sends the lists whose contents differ from {@code before}. */
    private void save(Map<String, EnumSet<Rule>> before) {
        for (Rule r : Rule.values()) {
            String now = list(banned, r), was = list(before, r);
            if (!now.equals(was)) {
                RegionNetwork.CHANNEL.sendToServer(new RegionActionPacket(RegionActionPacket.FLAG, regionId, r.flag, now));
            }
        }
    }

    private static String list(Map<String, EnumSet<Rule>> entries, Rule r) {
        return entries.entrySet().stream().filter(e -> e.getValue().contains(r)).map(Map.Entry::getKey).collect(Collectors.joining(","));
    }

    /** Changes the banned entries and sends what changed. */
    private void edit(Runnable change) {
        Map<String, EnumSet<Rule>> before = new LinkedHashMap<>();
        banned.forEach((k, v) -> before.put(k, EnumSet.copyOf(v)));
        change.run();
        banned.values().removeIf(EnumSet::isEmpty);
        save(before);
    }

    private void ban(String entry, Rule rule) {
        edit(() -> banned.computeIfAbsent(entry, k -> EnumSet.noneOf(Rule.class)).add(rule));
        status = Tr.t("banned_now", label(entry), Tr.t("rule." + rule.key()));
    }

    private void free(String entry) {
        edit(() -> banned.remove(entry));
        status = Tr.t("freed_now", label(entry));
        if (entry.equals(hoverEntry)) {
            hoverEntry = null;
        }
    }

    @Nullable
    private static String idOf(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? null : id.toString();
    }

    /** The picture for an entry: the item itself, the first item of a mod, or the first item of a tag. */
    private ItemStack icon(String entry) {
        return icons.computeIfAbsent(entry, e -> {
            if (e.startsWith("@") || e.endsWith(":*")) {
                String mod = e.startsWith("@") ? e.substring(1) : e.substring(0, e.length() - 2);
                for (Item item : ForgeRegistries.ITEMS) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                    if (id != null && id.getNamespace().equals(mod) && item != Items.AIR) {
                        return new ItemStack(item);
                    }
                }
                return new ItemStack(Items.BARRIER);
            }
            if (e.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(e.substring(1));
                if (tag != null) {
                    var set = BuiltInRegistries.ITEM.getTag(ItemTags.create(tag));
                    if (set.isPresent() && set.get().size() > 0) {
                        return new ItemStack(set.get().get(0).value());
                    }
                }
                return new ItemStack(Items.NAME_TAG);
            }
            ResourceLocation id = ResourceLocation.tryParse(e);
            Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            return item == null || item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item);
        });
    }

    /** How an entry reads on screen. */
    private String label(String entry) {
        if (entry.startsWith("@")) {
            return Tr.t("whole_mod", entry.substring(1));
        }
        if (entry.endsWith(":*")) {
            return Tr.t("whole_mod", entry.substring(0, entry.length() - 2));
        }
        if (entry.startsWith("#")) {
            return Tr.t("tag_entry", entry);
        }
        ItemStack s = icon(entry);
        return s.is(Items.BARRIER) ? entry : s.getHoverName().getString();
    }

    /** The strongest rule decides the slot's colour: CARRY, then PLACE, USE, BREAK. */
    private static Rule strongest(Set<Rule> rules) {
        return rules.contains(Rule.CARRY) ? Rule.CARRY : rules.contains(Rule.PLACE) ? Rule.PLACE : rules.contains(Rule.USE) ? Rule.USE : Rule.BREAK;
    }

    // ------------------------------------------------------------------ the browser

    private void loadTabs() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) {
            CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),
                    mc.options.operatorItemsTab().get() && mc.player.canUseGameMasterBlocks(), mc.level.registryAccess());
        }
        tabs = CreativeModeTabRegistry.getSortedCreativeModeTabs().stream()
                .filter(t -> t.getType() == CreativeModeTab.Type.CATEGORY && t.shouldDisplay() && !t.getDisplayItems().isEmpty())
                .toList();
        tab = Math.min(tab, Math.max(0, tabs.size() - 1));
        refreshShown();
    }

    /** What the browser shows: the search across every item, or the picked tab. */
    private void refreshShown() {
        String q = search == null ? "" : search.getValue();
        if (!q.isBlank()) {
            List<ItemStack> out = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (item == Items.AIR || id == null) {
                    continue;
                }
                ItemStack s = new ItemStack(item);
                if (BannedBlocks.searchMatches(q, id, s.getHoverName().getString())) {
                    out.add(s);
                }
            }
            shown = out;
        } else {
            shown = tabs.isEmpty() ? List.of() : new ArrayList<>(tabs.get(tab).getDisplayItems());
        }
        browseScroll = 0;
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void layout() {
        int x = left(), y = top();
        search = new EditBox(font, x + 300, y + 41, 108, 10, Component.literal(Tr.t("search_blocks")));
        search.setBordered(false);
        search.setTextColor(TEXT);
        search.setHint(Component.literal(Tr.t("search_blocks_hint")).withStyle(s -> s.withColor(0x9AA3B5)));
        search.setResponder(v -> refreshShown());
        addRenderableWidget(search);
        loadTabs();
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
        text(g, Tr.t("banned_title", regionId), x + 8, y + 8, TQ, 1f);
        chip(g, x + W - 70, y + 6, 62, 12, Tr.t("done"), false, TEXT, 0.75f, this::onClose);

        // the rule brush
        text(g, Tr.t("drop_with_rule"), x + 8, y + 25, MUTED, 0.6f);
        for (int i = 0; i < Rule.values().length; i++) {
            Rule r = Rule.values()[i];
            chip(g, x + 62 + i * 29, y + 22, 27, 11, Tr.t("rule." + r.key()), brush == r, r.colour, 0.55f, () -> brush = r);
        }

        drawList(g, x, y, mouseX, mouseY);
        drawCard(g, x, y);
        drawBrowser(g, x, y, mouseX, mouseY);

        chip(g, x + 184, y + 200, 70, 11, Tr.t("ban_whole_mod"), !lastBrowsed.isEmpty(), VIO, 0.55f, () -> {
            String id = idOf(lastBrowsed);
            if (id != null) {
                ban("@" + id.substring(0, id.indexOf(':')), brush);
            }
        });
        text(g, Tr.t("banned_hint_1"), x + 258, y + 200, HINT, 0.5f);
        text(g, Tr.t("banned_hint_2"), x + 258, y + 206, HINT, 0.5f);
        text(g, status.isEmpty() ? Tr.t("changes_save_at_once_esc_closes") : status, x + 184, y + 222, MUTED, 0.6f);

        super.draw(g, mouseX, mouseY, partial);

        if (dragStack != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.renderItem(dragStack, mouseX - 8, mouseY - 8);
            g.pose().popPose();
        } else {
            ItemStack under = stackAt(mouseX, mouseY);
            if (under != null && !under.isEmpty()) {
                g.renderTooltip(font, under, mouseX, mouseY);
            }
        }
    }

    private void drawList(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        inset(g, x + 8, y + 38, 168, 156);
        List<String> entries = new ArrayList<>(banned.keySet());
        int rows = Math.max(LIST_ROWS, (entries.size() + LIST_COLS) / LIST_COLS);
        listScroll = Math.max(0, Math.min(listScroll, rows - LIST_ROWS));
        for (int i = 0; i < LIST_COLS * LIST_ROWS; i++) {
            int sx = x + LIST_X + (i % LIST_COLS) * CELL, sy = y + LIST_Y + (i / LIST_COLS) * CELL;
            int index = i + listScroll * LIST_COLS;
            String entry = index < entries.size() ? entries.get(index) : null;
            int border = SLOT_LINE;
            if (entry != null) {
                border = strongest(banned.get(entry)).colour;
            }
            slot(g, sx, sy, border, entry != null && entry.equals(hoverEntry));
            if (entry != null) {
                g.renderItem(icon(entry), sx, sy);
                Rule s = strongest(banned.get(entry));
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                g.fill(sx + 11, sy + 11, sx + 17, sy + 17, 0xFF000000);
                text(g, s.name().substring(0, 1), sx + 12, sy + 12, s.colour, 0.5f);
                if (entry.startsWith("@") || entry.endsWith(":*") || entry.startsWith("#")) {
                    text(g, entry.startsWith("#") ? "#" : "*", sx + 1, sy + 1, TEXT, 0.6f);
                }
                g.pose().popPose();
                if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                    hoverEntry = entry;
                }
            }
        }
        // scroll bar
        g.fill(x + 169, y + LIST_Y, x + 173, y + LIST_Y + LIST_ROWS * CELL, SLOT);
        int bar = Math.max(12, LIST_ROWS * CELL * LIST_ROWS / rows);
        int by = y + LIST_Y + (rows <= LIST_ROWS ? 0 : (LIST_ROWS * CELL - bar) * listScroll / (rows - LIST_ROWS));
        g.fill(x + 169, by, x + 173, by + bar, 0xFF4A5060);
        if (entries.isEmpty()) {
            text(g, Tr.t("nothing_banned_yet"), x + 14, y + 44, HINT, 0.6f);
        }
    }

    private void drawCard(GuiGraphics g, int x, int y) {
        g.fill(x + 8, y + 198, x + 176, y + 230, 0xFF0A0D12);
        frame(g, x + 8, y + 198, 168, 32, LINE);
        String entry = hoverEntry != null && banned.containsKey(hoverEntry) ? hoverEntry : null;
        if (entry == null) {
            text(g, Tr.t("hover_a_banned_block"), x + 12, y + 202, HINT, 0.6f);
            return;
        }
        g.renderItem(icon(entry), x + 10, y + 200);
        text(g, font.plainSubstrByWidth(label(entry), 190), x + 28, y + 201, TEXT, 0.6f);
        text(g, entry, x + 28, y + 208, HINT, 0.5f);
        EnumSet<Rule> rules = banned.get(entry);
        for (int i = 0; i < Rule.values().length; i++) {
            Rule r = Rule.values()[i];
            boolean on = rules.contains(r) || (r != Rule.CARRY && rules.contains(Rule.CARRY));
            chip(g, x + 12 + i * 28, y + 218, 26, 9, Tr.t("rule." + r.key()), on, r.colour, 0.5f, () -> edit(() -> {
                EnumSet<Rule> set = banned.get(entry);
                if (set == null) {
                    return;
                }
                if (!set.remove(r)) {
                    set.add(r);
                }
                if (set.isEmpty()) {
                    set.add(Rule.PLACE); // a banned block keeps at least one rule; x frees it
                }
            }));
        }
        chip(g, x + 128, y + 218, 44, 9, Tr.t("remove_entry"), false, MUTED, 0.5f, () -> free(entry));
    }

    private void drawBrowser(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        // tabs across the top, scrolled with arrows when there are many
        int count = tabs.size();
        tabScroll = Math.max(0, Math.min(tabScroll, Math.max(0, count - TABS_SHOWN)));
        boolean arrows = count > TABS_SHOWN;
        int tx = x + TAB_X + (arrows ? 10 : 0);
        if (arrows) {
            chip(g, x + TAB_X, y + TAB_Y, 9, TAB_SIZE, "<", false, TEXT, 0.75f, () -> tabScroll--);
            chip(g, x + TAB_X + 10 + (TABS_SHOWN - 1) * (TAB_SIZE + 1) + TAB_SIZE + 1 - 10, y + TAB_Y, 9, TAB_SIZE, ">", false, TEXT, 0.75f, () -> tabScroll++);
        }
        int shownTabs = arrows ? TABS_SHOWN - 1 : Math.min(count, TABS_SHOWN + 1);
        for (int i = 0; i < shownTabs && tabScroll + i < count; i++) {
            int ti = tabScroll + i;
            CreativeModeTab t = tabs.get(ti);
            int sx = tx + i * (TAB_SIZE + 1);
            boolean on = ti == tab && search.getValue().isBlank();
            g.fill(sx, y + TAB_Y, sx + TAB_SIZE, y + TAB_Y + TAB_SIZE, on ? INSET : SLOT);
            frame(g, sx, y + TAB_Y, TAB_SIZE, TAB_SIZE, on ? TQ : SLOT_LINE);
            g.renderItem(t.getIconItem(), sx + 1, y + TAB_Y + 1);
            hits.add(new Hit(sx, y + TAB_Y, TAB_SIZE, TAB_SIZE, () -> {
                tab = ti;
                search.setValue("");
                refreshShown();
            }));
            if (mouseX >= sx && mouseX < sx + TAB_SIZE && mouseY >= y + TAB_Y && mouseY < y + TAB_Y + TAB_SIZE) {
                g.renderTooltip(font, t.getDisplayName(), mouseX, mouseY);
            }
        }
        inset(g, x + 184, y + 38, 228, 156);
        g.fill(x + 298, y + 40, x + 410, y + 52, 0xFF050608);
        String heading = !search.getValue().isBlank() ? Tr.t("search_results", shown.size())
                : tabs.isEmpty() ? "" : tabs.get(tab).getDisplayName().getString();
        text(g, font.plainSubstrByWidth(heading, 170), x + 188, y + 43, TEXT, 0.6f);
        int rows = Math.max(BROWSE_ROWS, (shown.size() + BROWSE_COLS - 1) / BROWSE_COLS);
        browseScroll = Math.max(0, Math.min(browseScroll, rows - BROWSE_ROWS));
        for (int i = 0; i < BROWSE_COLS * BROWSE_ROWS; i++) {
            int sx = x + BROWSE_X + (i % BROWSE_COLS) * CELL, sy = y + BROWSE_Y + (i / BROWSE_COLS) * CELL;
            int index = i + browseScroll * BROWSE_COLS;
            ItemStack s = index < shown.size() ? shown.get(index) : ItemStack.EMPTY;
            String id = s.isEmpty() ? null : idOf(s);
            EnumSet<Rule> rules = id == null ? null : banned.get(id);
            slot(g, sx, sy, rules == null ? SLOT_LINE : strongest(rules).colour, false);
            if (!s.isEmpty()) {
                g.renderItem(s, sx, sy);
                if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                    lastBrowsed = s;
                }
            }
        }
    }

    // ------------------------------------------------------------------ where the mouse is

    /** The banned entry under the mouse, or null. */
    @Nullable
    private String entryAt(double mx, double my) {
        int x = left() + LIST_X, y = top() + LIST_Y;
        if (mx < x || my < y || mx >= x + LIST_COLS * CELL || my >= y + LIST_ROWS * CELL) {
            return null;
        }
        int index = ((int) (my - y) / CELL + listScroll) * LIST_COLS + (int) (mx - x) / CELL;
        List<String> entries = new ArrayList<>(banned.keySet());
        return index < entries.size() ? entries.get(index) : null;
    }

    /** The browser block under the mouse, or null. */
    @Nullable
    private ItemStack browsedAt(double mx, double my) {
        int x = left() + BROWSE_X, y = top() + BROWSE_Y;
        if (mx < x || my < y || mx >= x + BROWSE_COLS * CELL || my >= y + BROWSE_ROWS * CELL) {
            return null;
        }
        int index = ((int) (my - y) / CELL + browseScroll) * BROWSE_COLS + (int) (mx - x) / CELL;
        return index < shown.size() ? shown.get(index) : null;
    }

    @Nullable
    private ItemStack stackAt(double mx, double my) {
        ItemStack b = browsedAt(mx, my);
        if (b != null) {
            return b;
        }
        String e = entryAt(mx, my);
        return e == null ? null : icon(e);
    }

    private boolean overList(double mx, double my) {
        return mx >= left() + 8 && mx < left() + 176 && my >= top() + 38 && my < top() + 194;
    }

    private boolean overBrowser(double mx, double my) {
        return mx >= left() + 184 && mx < left() + 412 && my >= top() + 38 && my < top() + 194;
    }

    // ------------------------------------------------------------------ input

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
        ItemStack b = browsedAt(mx, my);
        if (b != null && button == 0) {
            String id = idOf(b);
            if (id != null && hasShiftDown()) {
                ban(id, brush);
            } else {
                dragStack = b.copy();
                dragEntry = null;
            }
            return true;
        }
        String e = entryAt(mx, my);
        if (e != null) {
            if (button == 1) {
                Rule next = Rule.values()[(strongest(banned.get(e)).ordinal() + 1) % Rule.values().length];
                edit(() -> banned.put(e, EnumSet.of(next)));
                status = Tr.t("banned_now", label(e), Tr.t("rule." + next.key()));
            } else if (button == 0) {
                hoverEntry = e;
                dragEntry = e;
                dragStack = icon(e).copy();
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean onRelease(double mx, double my, int button) {
        if (dragStack != null && button == 0) {
            if (dragEntry == null && overList(mx, my)) {
                String id = idOf(dragStack);
                if (id != null) {
                    ban(id, brush);
                }
            } else if (dragEntry != null && overBrowser(mx, my)) {
                free(dragEntry);
            }
            dragStack = null;
            dragEntry = null;
            return true;
        }
        return super.onRelease(mx, my, button);
    }

    @Override
    protected boolean onScroll(double mx, double my, double delta) {
        if (overList(mx, my)) {
            listScroll -= (int) Math.signum(delta);
            return true;
        }
        if (overBrowser(mx, my)) {
            browseScroll -= (int) Math.signum(delta);
            return true;
        }
        if (my >= top() + TAB_Y && my < top() + TAB_Y + TAB_SIZE && mx >= left() + TAB_X) {
            tabScroll -= (int) Math.signum(delta);
            return true;
        }
        return super.onScroll(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // Esc: back to the region screen
            onClose();
            return true;
        }
        if (search.isFocused()) {
            return search.keyPressed(keyCode, scanCode, modifiers) || search.canConsumeInput() || super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ drawing helpers (as RegionScreen's)

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
