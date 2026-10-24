package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.protection.BannedBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.CreativeModeTabRegistry;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A creative-style block browser (owner decision D118): the creative tabs that hold blocks across the top, a search box
 * (names, ids and {@code @mod}, as in the BANNED BLOCKS editor) and a grid of blocks. Only blocks are shown. The screen
 * that owns it places it, passes clicks and wheel turns on, and is told which block was clicked.
 */
final class BlockBrowser {
    static final int CELL = 17, TAB = 16;
    private static final int SLOT = 0xFF171B22, SLOT_LINE = 0xFF262B35;

    private final Font font;
    private final EditBox search;
    private List<CreativeModeTab> tabs = List.of();
    private List<ItemStack> shown = List.of();
    private int tab, tabScroll, scroll;
    /** Where it is drawn (screen units) and how many slots across and down. */
    private int x, y, cols, rows;

    BlockBrowser(Font font, EditBox search) {
        this.font = font;
        this.search = search;
        search.setResponder(v -> refresh());
    }

    void place(int x, int y, int cols, int rows) {
        this.x = x;
        this.y = y;
        this.cols = cols;
        this.rows = rows;
    }

    /** Reads the creative tabs again (the game builds their contents on demand) and keeps those with blocks in them. */
    void load() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null) {
            CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),
                    mc.options.operatorItemsTab().get() && mc.player.canUseGameMasterBlocks(), mc.level.registryAccess());
        }
        tabs = CreativeModeTabRegistry.getSortedCreativeModeTabs().stream()
                .filter(t -> t.getType() == CreativeModeTab.Type.CATEGORY && t.shouldDisplay() && t.getDisplayItems().stream().anyMatch(BlockBrowser::isBlock))
                .toList();
        tab = Math.min(tab, Math.max(0, tabs.size() - 1));
        refresh();
    }

    static boolean isBlock(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock() != Blocks.AIR;
    }

    /** The block an item places, or null. */
    @Nullable
    static ResourceLocation blockOf(ItemStack s) {
        return s.getItem() instanceof BlockItem bi ? BuiltInRegistries.BLOCK.getKey(bi.getBlock()) : null;
    }

    /** The item that shows a block, or an empty stack when the block has none. */
    static ItemStack iconOf(ResourceLocation block) {
        var b = ForgeRegistries.BLOCKS.getValue(block);
        return b == null ? ItemStack.EMPTY : new ItemStack(b.asItem());
    }

    private void refresh() {
        String q = search.getValue();
        List<ItemStack> out = new ArrayList<>();
        if (!q.isBlank()) {
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                ItemStack s = new ItemStack(item);
                if (id != null && isBlock(s) && BannedBlocks.searchMatches(q, id, s.getHoverName().getString())) {
                    out.add(s);
                }
            }
        } else if (!tabs.isEmpty()) {
            for (ItemStack s : tabs.get(tab).getDisplayItems()) {
                if (isBlock(s)) {
                    out.add(s);
                }
            }
        }
        shown = out;
        scroll = 0;
    }

    /** The tabs row is {@link #TAB} high at {@code y}; the search box and heading below it; the grid below that. */
    void draw(GuiGraphics g, int mouseX, int mouseY, Predicate<ResourceLocation> marked, HitSink hits) {
        int tabsShown = Math.max(1, (cols * CELL - 20) / (TAB + 1));
        tabScroll = Math.max(0, Math.min(tabScroll, Math.max(0, tabs.size() - tabsShown)));
        boolean arrows = tabs.size() > tabsShown;
        int tx = x + (arrows ? 10 : 0);
        if (arrows) {
            hits.chip(g, x, y, 9, TAB, "<", () -> tabScroll--);
            hits.chip(g, x + cols * CELL - 9, y, 9, TAB, ">", () -> tabScroll++);
        }
        int fit = arrows ? (cols * CELL - 20) / (TAB + 1) : tabsShown;
        for (int i = 0; i < fit && tabScroll + i < tabs.size(); i++) {
            int ti = tabScroll + i, sx = tx + i * (TAB + 1);
            CreativeModeTab t = tabs.get(ti);
            boolean on = ti == tab && search.getValue().isBlank();
            g.fill(sx, y, sx + TAB, y + TAB, on ? RegionScreen.INSET : SLOT);
            frame(g, sx, y, TAB, TAB, on ? RegionScreen.TQ : SLOT_LINE);
            g.pose().pushPose();
            g.pose().translate(sx + 1, y + 1, 0);
            g.pose().scale(0.875f, 0.875f, 1f);
            g.renderItem(t.getIconItem(), 0, 0);
            g.pose().popPose();
            hits.add(sx, y, TAB, TAB, () -> {
                tab = ti;
                search.setValue("");
                refresh();
            });
            if (mouseX >= sx && mouseX < sx + TAB && mouseY >= y && mouseY < y + TAB) {
                g.renderTooltip(font, t.getDisplayName(), mouseX, mouseY);
            }
        }
        // the search box sits at (x, y + TAB + 4); the heading beside it
        int sy = y + TAB + 4;
        g.fill(x, sy - 1, x + 104, sy + 11, 0xFF050608);
        frame(g, x, sy - 1, 104, 12, RegionScreen.LINE);
        search.setX(x + 3);
        search.setY(sy + 1);
        search.setWidth(98);
        String heading = !search.getValue().isBlank() ? Tr.t("search_results", shown.size())
                : tabs.isEmpty() ? "" : tabs.get(tab).getDisplayName().getString();
        RegionScreen.text(g, font, font.plainSubstrByWidth(heading, (int) ((cols * CELL - 110) / 0.6f)), x + 108, sy + 2, RegionScreen.TEXT, 0.6f);
        int gy = gridY();
        int total = Math.max(rows, (shown.size() + cols - 1) / cols);
        scroll = Math.max(0, Math.min(scroll, total - rows));
        ItemStack hover = null;
        for (int i = 0; i < cols * rows; i++) {
            int cx = x + (i % cols) * CELL, cy = gy + (i / cols) * CELL, index = i + scroll * cols;
            ItemStack s = index < shown.size() ? shown.get(index) : ItemStack.EMPTY;
            ResourceLocation id = s.isEmpty() ? null : blockOf(s);
            g.fill(cx, cy, cx + 16, cy + 16, SLOT);
            frame(g, cx - 1, cy - 1, 18, 18, id != null && marked.test(id) ? RegionScreen.GOLD : SLOT_LINE);
            if (!s.isEmpty()) {
                g.renderItem(s, cx, cy);
                if (mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16) {
                    hover = s;
                }
            }
        }
        if (hover != null) {
            g.renderTooltip(font, hover, mouseX, mouseY);
        }
    }

    private int gridY() {
        return y + TAB + 20;
    }

    /** The block under the mouse in the grid, or null. */
    @Nullable
    ItemStack at(double mx, double my) {
        int gy = gridY();
        if (mx < x || my < gy || mx >= x + cols * CELL || my >= gy + rows * CELL) {
            return null;
        }
        int index = ((int) (my - gy) / CELL + scroll) * cols + (int) (mx - x) / CELL;
        return index < shown.size() ? shown.get(index) : null;
    }

    /** A wheel turn over the grid or the tabs; true when it was used. */
    boolean scroll(double mx, double my, double delta) {
        if (mx >= x && mx < x + cols * CELL && my >= gridY() && my < gridY() + rows * CELL) {
            scroll -= (int) Math.signum(delta);
            return true;
        }
        if (mx >= x && mx < x + cols * CELL && my >= y && my < y + TAB) {
            tabScroll -= (int) Math.signum(delta);
            return true;
        }
        return false;
    }

    /** Where the owning screen keeps its click areas and draws its chips. */
    interface HitSink {
        void add(int x, int y, int w, int h, Runnable action);

        void chip(GuiGraphics g, int x, int y, int w, int h, String label, Runnable action);
    }

    static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
    }

    static Component hint() {
        return Component.literal(Tr.t("search_blocks_hint")).withStyle(s -> s.withColor(0x9AA3B5));
    }
}
