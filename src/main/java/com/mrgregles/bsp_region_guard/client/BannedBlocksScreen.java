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
import net.minecraft.tags.BlockTags;
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
 * <p>
 * Tags and whole mods (B24, mock-up option A): each takes a wide row at the top of the grid with its name, how many
 * items it covers and OPEN, which shows those items in the grid (read-only) under a BACK key. The browser frames items
 * a tag or whole mod covers with a dashed line and a "#" (or "*"), and a field beside BAN WHOLE MOD adds a tag by name.
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
    /** The tag or whole mod whose items the left grid shows (OPEN), or null for the entries. */
    @Nullable
    private String opened;
    private int openScroll;
    /** The item of the opened tag under the mouse, for the card. */
    @Nullable
    private ItemStack hoverMember;
    /** The items each tag or whole mod covers, worked out once per entry while the screen is open. */
    private final Map<String, List<ItemStack>> members = new HashMap<>();
    /** The browser's items and the tag or whole mod covering each ("" for none); emptied when the entries change. */
    private final Map<Item, String> covered = new HashMap<>();
    @Nullable
    private Map<String, String> groupEntries;
    /** The left grid's lines, rebuilt when the entries change. */
    @Nullable
    private List<Row> rowsCache;
    /** The opened tag's items that pass the search, and what they were filtered for. */
    private List<ItemStack> openShown = List.of();
    private String openShownFor = "";
    private EditBox tagBox;

    /** One line of the left grid: a tag or whole mod on its own, or up to {@link #LIST_COLS} single items. */
    private record Row(@Nullable String group, List<String> items) {
    }
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
        entriesChanged();
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
        entriesChanged();
        save(before);
    }

    private void entriesChanged() {
        covered.clear();
        groupEntries = null;
        rowsCache = null;
        if (opened != null && !banned.containsKey(opened)) {
            closeOpened();
        }
    }

    private static boolean isGroup(String entry) {
        return BannedBlocks.isGroupEntry(entry);
    }

    /** The items a tag or whole mod covers, matched as the server matches them (the same opening as BANNED HERE). */
    private List<ItemStack> members(String entry) {
        return members.computeIfAbsent(entry, e -> BannedBlocks.itemsMatching(Map.of(e, e)));
    }

    /** The opened tag's items, narrowed by the search box (as JEI's, {@code @mod} too); worked out again only when the search changes. */
    private List<ItemStack> openedItems() {
        String q = search == null ? "" : search.getValue().trim();
        String key = opened + "|" + q;
        if (!key.equals(openShownFor)) {
            List<ItemStack> all = members(opened);
            if (q.isEmpty()) {
                openShown = all;
            } else {
                List<ItemStack> out = new ArrayList<>();
                for (ItemStack s : all) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
                    if (id != null && BannedBlocks.searchMatches(q, id, s.getHoverName().getString())) {
                        out.add(s);
                    }
                }
                openShown = out;
            }
            openShownFor = key;
            openScroll = 0;
        }
        return openShown;
    }

    /** How a tag or whole mod is named on screen: a tag by itself, a whole mod as "every block of ...". */
    private String groupName(String entry) {
        return entry.startsWith("#") ? entry : label(entry);
    }

    /** "1 item", "1,473 items". */
    private static String countText(int n) {
        return n == 1 ? Tr.t("group_count_one") : Tr.t("group_count", String.format(Locale.ROOT, "%,d", n));
    }

    /** The tag or whole mod that covers a browser item, or null. */
    @Nullable
    private String coveredBy(ItemStack stack) {
        if (groupEntries == null) {
            groupEntries = new LinkedHashMap<>();
            banned.keySet().stream().filter(BannedBlocksScreen::isGroup).forEach(e -> groupEntries.put(e, e));
        }
        if (groupEntries.isEmpty()) {
            return null;
        }
        String hit = covered.computeIfAbsent(stack.getItem(), item -> {
            String m = BannedBlocks.match(groupEntries, stack);
            return m == null ? "" : m;
        });
        return hit.isEmpty() ? null : hit;
    }

    /** The left grid's lines: every tag and whole mod on its own line first, then the single items. */
    private List<Row> rows() {
        if (rowsCache != null) {
            return rowsCache;
        }
        List<Row> out = new ArrayList<>();
        for (String e : banned.keySet()) {
            if (isGroup(e)) {
                out.add(new Row(e, List.of()));
            }
        }
        List<String> line = null;
        for (String e : banned.keySet()) {
            if (isGroup(e)) {
                continue;
            }
            if (line == null || line.size() == LIST_COLS) {
                line = new ArrayList<>();
                out.add(new Row(null, line));
            }
            line.add(e);
        }
        rowsCache = out;
        return out;
    }

    private void closeOpened() {
        opened = null;
        hoverMember = null;
        openScroll = 0;
        openShownFor = "";
    }

    /** Adds a tag typed in the field, with the rule on the bar; refused when the id is bad or no such tag is loaded. */
    private void addTag(String raw) {
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return;
        }
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        ResourceLocation id = ResourceLocation.tryParse(t);
        if (id == null) {
            status = Tr.t("tag_bad_id", "#" + t);
            return;
        }
        String entry = "#" + id;
        boolean loaded = BuiltInRegistries.ITEM.getTag(ItemTags.create(id)).isPresent() || BuiltInRegistries.BLOCK.getTag(BlockTags.create(id)).isPresent();
        if (!loaded) {
            status = Tr.t("tag_not_loaded", entry);
            return;
        }
        ban(entry, brush);
        tagBox.setValue("");
    }

    private void ban(String entry, Rule rule) {
        if (opened != null && !entry.equals(opened)) {
            closeOpened(); // a block dropped while a tag is opened is its own entry: show the entries, where it lands
        }
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
        tagBox = new EditBox(font, x + 186, y + 213, 66, 8, Component.literal(Tr.t("tag_field")));
        tagBox.setBordered(false);
        tagBox.setTextColor(TEXT);
        tagBox.setMaxLength(128);
        tagBox.setHint(Component.literal(Tr.t("tag_field_hint")).withStyle(s -> s.withColor(0x9AA3B5)));
        addRenderableWidget(tagBox);
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
        // the two hints wrap to the space right of the button (154 units), so they never run off the screen
        int hy = y + 199;
        for (String hint : new String[]{Tr.t("banned_hint_1"), Tr.t("banned_hint_2")}) {
            for (var line : font.split(Component.literal(hint), (int) (154 / 0.5f))) {
                g.pose().pushPose();
                g.pose().translate(x + 258, hy, 0);
                g.pose().scale(0.5f, 0.5f, 1f);
                g.drawString(font, line, 0, 0, HINT, false);
                g.pose().popPose();
                hy += 5;
            }
        }
        inset(g, x + 184, y + 212, 70, 10);
        String line = status.isEmpty() ? Tr.t("changes_save_at_once_esc_closes") : status;
        text(g, font.plainSubstrByWidth(line, (int) ((W - 184 - 8) / 0.6f)), x + 184, y + 224, MUTED, 0.6f);

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
        if (opened != null) {
            drawOpened(g, x, y, mouseX, mouseY);
            return;
        }
        hoverMember = null;
        List<Row> rows = rows();
        int total = Math.max(LIST_ROWS, rows.size());
        listScroll = Math.max(0, Math.min(listScroll, total - LIST_ROWS));
        for (int r = 0; r < LIST_ROWS; r++) {
            int sy = y + LIST_Y + r * CELL;
            Row row = listScroll + r < rows.size() ? rows.get(listScroll + r) : null;
            if (row != null && row.group() != null) {
                drawGroupRow(g, x + LIST_X, sy, row.group(), mouseX, mouseY);
                continue;
            }
            for (int c = 0; c < LIST_COLS; c++) {
                int sx = x + LIST_X + c * CELL;
                String entry = row != null && c < row.items().size() ? row.items().get(c) : null;
                slot(g, sx, sy, entry == null ? SLOT_LINE : strongest(banned.get(entry)).colour, entry != null && entry.equals(hoverEntry));
                if (entry == null) {
                    continue;
                }
                g.renderItem(icon(entry), sx, sy);
                ruleMark(g, sx, sy, strongest(banned.get(entry)));
                if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                    hoverEntry = entry;
                }
            }
        }
        scrollBar(g, x, y, total, listScroll);
        if (banned.isEmpty()) {
            text(g, Tr.t("nothing_banned_yet"), x + 14, y + 44, HINT, 0.6f);
        }
    }

    /** A tag or whole mod: one wide line with its picture, name, how many items it covers, and OPEN. */
    private void drawGroupRow(GuiGraphics g, int gx, int sy, String entry, int mouseX, int mouseY) {
        int w = LIST_COLS * CELL - 1;
        Rule s = strongest(banned.get(entry));
        g.fill(gx - 1, sy - 1, gx + w, sy + 17, (s.colour & 0xFFFFFF) | 0x1A000000);
        frame(g, gx - 1, sy - 1, w + 1, 18, entry.equals(hoverEntry) ? 0xFFFFFFFF : s.colour);
        g.renderItem(icon(entry), gx, sy);
        ruleMark(g, gx, sy, s);
        text(g, font.plainSubstrByWidth(groupName(entry), 160), gx + 20, sy + 2, s.colour, 0.6f);
        text(g, countText(members(entry).size()), gx + 20, sy + 10, MUTED, 0.5f);
        chip(g, gx + w - 31, sy + 2, 29, 12, Tr.t("tag_open"), true, TQ, 0.55f, () -> {
            opened = entry;
            openScroll = 0;
            hoverEntry = entry;
        });
        if (mouseX >= gx && mouseX < gx + w && mouseY >= sy && mouseY < sy + 16) {
            hoverEntry = entry;
        }
    }

    /** An opened tag or whole mod: BACK and its name on the first line, its items under it (read-only). */
    private void drawOpened(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        List<ItemStack> items = openedItems();
        int all = members(opened).size();
        Rule s = strongest(banned.get(opened));
        chip(g, x + LIST_X - 1, y + LIST_Y, 30, 12, Tr.t("tag_back"), true, TQ, 0.55f, this::closeOpened);
        text(g, font.plainSubstrByWidth(groupName(opened), 200), x + LIST_X + 34, y + LIST_Y + 1, s.colour, 0.6f);
        String count = items.size() == all ? countText(all)
                : Tr.t("group_count_found", String.format(Locale.ROOT, "%,d", items.size()), String.format(Locale.ROOT, "%,d", all));
        text(g, count, x + LIST_X + 34, y + LIST_Y + 8, MUTED, 0.5f);
        int visible = LIST_ROWS - 1;
        int rows = Math.max(visible, (items.size() + LIST_COLS - 1) / LIST_COLS);
        openScroll = Math.max(0, Math.min(openScroll, rows - visible));
        hoverMember = null;
        for (int r = 0; r < visible; r++) {
            int sy = y + LIST_Y + (r + 1) * CELL;
            for (int c = 0; c < LIST_COLS; c++) {
                int index = (openScroll + r) * LIST_COLS + c;
                if (index >= items.size()) {
                    break;
                }
                int sx = x + LIST_X + c * CELL;
                ItemStack item = items.get(index);
                boolean over = mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16;
                dashedSlot(g, sx, sy, over ? 0xFFFFFFFF : s.colour);
                g.renderItem(item, sx, sy);
                if (over) {
                    hoverMember = item;
                }
            }
        }
        if (items.isEmpty()) {
            String none = all > 0 ? Tr.t("tag_no_match") : opened.startsWith("#") ? Tr.t("tag_no_items") : Tr.t("mod_no_items");
            text(g, none, x + 14, y + LIST_Y + CELL + 4, HINT, 0.6f);
        }
        scrollBar(g, x, y, rows + 1, openScroll);
    }

    /** The rule's first letter in the slot's corner. */
    private void ruleMark(GuiGraphics g, int sx, int sy, Rule s) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.fill(sx + 11, sy + 11, sx + 17, sy + 17, 0xFF000000);
        text(g, s.name().substring(0, 1), sx + 12, sy + 12, s.colour, 0.5f);
        g.pose().popPose();
    }

    private void scrollBar(GuiGraphics g, int x, int y, int rows, int scroll) {
        g.fill(x + 169, y + LIST_Y, x + 173, y + LIST_Y + LIST_ROWS * CELL, SLOT);
        int bar = Math.max(12, LIST_ROWS * CELL * LIST_ROWS / Math.max(LIST_ROWS, rows));
        int by = y + LIST_Y + (rows <= LIST_ROWS ? 0 : (LIST_ROWS * CELL - bar) * scroll / (rows - LIST_ROWS));
        g.fill(x + 169, by, x + 173, by + bar, 0xFF4A5060);
    }

    private void drawCard(GuiGraphics g, int x, int y) {
        g.fill(x + 8, y + 198, x + 176, y + 230, 0xFF0A0D12);
        frame(g, x + 8, y + 198, 168, 32, LINE);
        if (opened != null && hoverMember != null) { // one item of an opened tag: it goes with the tag
            g.renderItem(hoverMember, x + 10, y + 200);
            text(g, font.plainSubstrByWidth(Tr.t("in_entry", hoverMember.getHoverName().getString(), groupName(opened)), 240), x + 28, y + 201, TEXT, 0.6f);
            String id = idOf(hoverMember);
            text(g, id == null ? "" : id, x + 28, y + 208, HINT, 0.5f);
            int ly = y + 216;
            for (var line : font.split(Component.literal(opened.startsWith("#") ? Tr.t("member_of_tag") : Tr.t("member_of_mod")), (int) (160 / 0.5f))) {
                if (ly > y + 222) {
                    break; // the card has room for two lines
                }
                g.pose().pushPose();
                g.pose().translate(x + 12, ly, 0);
                g.pose().scale(0.5f, 0.5f, 1f);
                g.drawString(font, line, 0, 0, MUTED, false);
                g.pose().popPose();
                ly += 6;
            }
            return;
        }
        String entry = opened != null ? opened : hoverEntry != null && banned.containsKey(hoverEntry) ? hoverEntry : null;
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
            String by = rules == null && !s.isEmpty() ? coveredBy(s) : null;
            if (by != null) { // covered by a tag or whole mod: dashed, with its mark
                dashedSlot(g, sx, sy, strongest(banned.get(by)).colour);
            } else {
                slot(g, sx, sy, rules == null ? SLOT_LINE : strongest(rules).colour, false);
            }
            if (!s.isEmpty()) {
                g.renderItem(s, sx, sy);
                if (by != null) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 200);
                    text(g, by.startsWith("#") ? "#" : "*", sx + 1, sy + 1, TEXT, 0.6f);
                    g.pose().popPose();
                }
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
        if (opened != null) {
            return null; // an opened tag's items are not entries
        }
        List<Row> rows = rows();
        int r = (int) (my - y) / CELL + listScroll, c = (int) (mx - x) / CELL;
        if (r >= rows.size()) {
            return null;
        }
        Row row = rows.get(r);
        return row.group() != null ? row.group() : c < row.items().size() ? row.items().get(c) : null;
    }

    /** The item of the opened tag under the mouse, or null. */
    @Nullable
    private ItemStack memberAt(double mx, double my) {
        int x = left() + LIST_X, y = top() + LIST_Y + CELL;
        if (opened == null || mx < x || my < y || mx >= x + LIST_COLS * CELL || my >= y + (LIST_ROWS - 1) * CELL) {
            return null;
        }
        int index = ((int) (my - y) / CELL + openScroll) * LIST_COLS + (int) (mx - x) / CELL;
        List<ItemStack> items = openedItems();
        return index < items.size() ? items.get(index) : null;
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
        ItemStack m = memberAt(mx, my);
        if (m != null) {
            return m;
        }
        String e = entryAt(mx, my);
        return e == null || isGroup(e) ? null : icon(e); // a tag's line names itself; no item tooltip over it
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
            if (opened != null) {
                openScroll -= (int) Math.signum(delta) * 3; // a tag can hold thousands of items
            } else {
                listScroll -= (int) Math.signum(delta);
            }
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
        if (keyCode == 256 && opened != null) { // Esc: out of an opened tag first
            closeOpened();
            return true;
        }
        if (keyCode == 256) { // Esc: back to the region screen
            onClose();
            return true;
        }
        if (search.isFocused()) {
            return search.keyPressed(keyCode, scanCode, modifiers) || search.canConsumeInput() || super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (tagBox.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { // Enter: add the tag
                addTag(tagBox.getValue());
                return true;
            }
            return tagBox.keyPressed(keyCode, scanCode, modifiers) || tagBox.canConsumeInput() || super.keyPressed(keyCode, scanCode, modifiers);
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

    /** A slot whose frame is dashed: an item banned as part of a tag or whole mod. */
    private static void dashedSlot(GuiGraphics g, int x, int y, int colour) {
        g.fill(x, y, x + 16, y + 16, SLOT);
        for (int i = 0; i < 18; i += 3) {
            int a = x - 1 + i, b = Math.min(a + 2, x + 17);
            g.fill(a, y - 1, b, y, colour);
            g.fill(a, y + 16, b, y + 17, colour);
            int c = y - 1 + i, d = Math.min(c + 2, y + 17);
            g.fill(x - 1, c, x, d, colour);
            g.fill(x + 16, c, x + 17, d, colour);
        }
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
