package com.mrgregles.bsp_region_guard.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mrgregles.bsp_region_guard.network.PlotMapActionPacket;
import com.mrgregles.bsp_region_guard.network.PlotMapStatePacket;
import com.mrgregles.bsp_region_guard.network.PlotMapStatePacket.Plot;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The plot map (owner decisions D57 to D66, mock-up option B, floating cards): the whole window is a map of the world
 * drawn from the server's chunk colours, one block to a few pixels or up to 16; plots for sale, claims and open
 * areas drawn on it. A folding list card finds plots and snaps to them; a card beside the picked plot copies it or
 * unlocks it; the copy or the unlocked plot follows the mouse until a click drops it, then drags; a toolbar under it
 * sets the name, priority and height, turns it, switches SNAP, and confirms or cancels. UNDO and REDO take back the
 * last 15 actions. Only the world the admin stands in can be edited (D62); others show in a gold frame.
 *
 * <p>Corner mode (D71 to D80): opened for one region from the admin screen, the admin clicks its first and second
 * corner (a click that does not drag), sets the height in the corner bar (the whole world at first), and confirms;
 * then the admin screen comes back. The cards are drawn at 80% ({@link #UI}) and fold.
 */
public class PlotMapScreen extends Screen {
    private static final int BG = RegionScreen.BG, INSET = RegionScreen.INSET, LINE = RegionScreen.LINE, TQ = RegionScreen.TQ, VIO = RegionScreen.VIO,
            GOLD = RegionScreen.GOLD, BAD = RegionScreen.BAD, TEXT = RegionScreen.TEXT, MUTED = RegionScreen.MUTED;
    private static final float MIN_SCALE = 1, MAX_SCALE = 16;
    private static final int ROW = 11, LIST_W = 170, LIST_ROWS = 18;
    /** The cards, bars and text are drawn at this size; the map itself at full size (D80). */
    private static final float UI = 0.8f;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** A plot being placed: a copy (source set) or an unlocked plot being moved (moving set). */
    private static final class Ghost {
        String source, moving;
        int x, z, w, d, minY, maxY, priority, turns;
        boolean following, blocks;
    }

    private record Tex(byte[] from, ResourceLocation id, DynamicTexture texture) {
    }

    private PlotMapStatePacket state;
    private float scale = 4;
    private double ox, oz;
    private int mouseBX, mouseBZ;
    @Nullable
    private String picked;
    @Nullable
    private Ghost ghost;
    /** A plot we asked to unlock: once the answer shows it unlocked, it starts following the mouse. */
    @Nullable
    private String unlocking;
    private boolean askOwner, folded, snap = true, panning;
    /** A backup file clicked once: a second click restores it. */
    @Nullable
    private String restoreArmed;
    private int dragDX = Integer.MIN_VALUE, dragDZ, listScroll;
    private String filter = "all";
    private final List<Hit> hits = new ArrayList<>();
    /** The cards drawn this frame (card units): a click on one that hits no button goes no further, so it never reaches the map. */
    private final List<Hit> covers = new ArrayList<>();
    private final Map<Long, Tex> textures = new HashMap<>();
    private EditBox search, name, minY, maxY;
    private String message = "";
    private boolean messageOk = true;
    /** The window in card units ({@link #UI}). */
    private int uw, uh;
    private boolean cardFolded, toolFolded;
    /** Corner mode: the region whose corners are clicked, or null; the corners clicked so far; the world's height. */
    @Nullable
    private String corners;
    @Nullable
    private int[] corner1, corner2;
    private int bottom, top;
    private boolean cornersSent, pressed;
    private double pressX, pressY;

    public PlotMapScreen(PlotMapStatePacket state) {
        super(Component.literal(Tr.t("plot_map")));
        this.state = state;
        if (!state.corners().isEmpty()) {
            startCorners(state);
        }
    }

    private void startCorners(PlotMapStatePacket msg) {
        corners = msg.corners();
        corner1 = corner2 = null;
        bottom = msg.bottom();
        top = msg.top();
        cornersSent = false;
        ghost = null;
        picked = null;
        if (minY != null) {
            minY.setValue(String.valueOf(bottom));
            maxY.setValue(String.valueOf(top));
        }
    }

    /** Opens the screen, or brings the open one up to date with the server's answer. */
    public static void receive(PlotMapStatePacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PlotMapScreen s) {
            s.update(msg);
        } else if (msg.open()) {
            mc.setScreen(new PlotMapScreen(msg));
        }
    }

    private void update(PlotMapStatePacket msg) {
        boolean worldChanged = !msg.dimension().equals(state.dimension());
        state = msg;
        if (!msg.message().isEmpty()) {
            message = msg.message();
            messageOk = msg.ok();
        }
        if (!msg.corners().isEmpty()) {
            startCorners(msg);
        } else if (corners != null && cornersSent) {
            cornersSent = false;
            if (msg.ok() && !msg.message().isEmpty()) {
                backToRegions(corners); // the corners are set: back to the admin screen
                return;
            }
        }
        askOwner = msg.askOwner();
        if (worldChanged) {
            releaseTextures();
            ghost = null;
            centreOn(msg.playerX(), msg.playerZ());
        }
        if (msg.ok() && ghost != null && !askOwner && !msg.message().isEmpty()) {
            ghost = null; // the paste or move went through
        }
        if (unlocking != null) {
            Plot p = plot(unlocking);
            if (p != null && !p.locked()) {
                ghost = new Ghost();
                ghost.moving = p.id();
                ghost.x = p.minX();
                ghost.z = p.minZ();
                ghost.w = p.width();
                ghost.d = p.depth();
                ghost.minY = p.minY();
                ghost.maxY = p.maxY();
                ghost.priority = p.priority();
            }
            unlocking = null;
        }
    }

    @Override
    protected void init() {
        if (search == null) {
            centreOn(state.playerX(), state.playerZ());
        }
        String q = search == null ? "" : search.getValue(), n = name == null ? "" : name.getValue();
        search = new EditBox(font, 14, 26, LIST_W - 12, 12, Component.literal(Tr.t("search_plots")));
        search.setHint(Component.literal(Tr.t("search_plots")).withStyle(s -> s.withColor(0x9AA3B5)));
        search.setValue(q);
        addRenderableWidget(search);
        name = new EditBox(font, 0, 0, 110, 12, Component.literal(Tr.t("plot_name")));
        name.setValue(n);
        name.setMaxLength(32);
        addRenderableWidget(name);
        String lo = minY == null ? String.valueOf(bottom) : minY.getValue(), hi = maxY == null ? String.valueOf(top) : maxY.getValue();
        minY = new EditBox(font, 0, 0, 40, 12, Component.literal(Tr.t("pm_min_y")));
        maxY = new EditBox(font, 0, 0, 40, 12, Component.literal(Tr.t("pm_max_y")));
        minY.setValue(lo);
        maxY.setValue(hi);
        for (EditBox b : new EditBox[]{minY, maxY}) {
            b.setMaxLength(6);
            b.setFilter(t -> t.matches("-?\\d*"));
            addRenderableWidget(b);
        }
    }

    private void centreOn(double bx, double bz) {
        ox = bx - width / scale / 2;
        oz = bz - height / scale / 2;
    }

    @Nullable
    private Plot plot(String id) {
        for (Plot p : state.plots()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    private int sx(double bx) {
        return (int) Math.round((bx - ox) * scale);
    }

    private int sz(double bz) {
        return (int) Math.round((bz - oz) * scale);
    }

    private static int colour(String type) {
        return switch (type) {
            case "claim" -> VIO;
            default -> GOLD;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        hits.clear();
        covers.clear();
        g.fill(0, 0, width, height, 0xFF05070A);
        mouseBX = (int) Math.floor(mouseX / scale + ox);
        mouseBZ = (int) Math.floor(mouseY / scale + oz);
        drawTerrain(g);
        if (corners != null) {
            drawOthers(g);
        }
        drawPlots(g);
        if (corners != null) {
            drawCorners(g);
        }
        if (ghost != null) {
            if (ghost.following) {
                ghost.x = mouseBX - ghost.w / 2;
                ghost.z = mouseBZ - ghost.d / 2;
                snapGhost();
            }
            drawGhost(g);
        }
        if (scale >= 4) {
            frame(g, sx(mouseBX), sz(mouseBZ), Math.max(1, (int) scale), Math.max(1, (int) scale), 0xCCFFFFFF);
        }
        boolean ro = !state.editable();
        if (ro) {
            frame(g, 0, 0, width, height, GOLD);
            frame(g, 1, 1, width - 2, height - 2, GOLD);
        }
        // the cards, bars and text, at 80% (D80); widgets and clicks use the same units
        g.pose().pushPose();
        g.pose().scale(UI, UI, 1);
        uw = (int) Math.ceil(width / UI);
        uh = (int) Math.ceil(height / UI);
        int ux = (int) (mouseX / UI), uy = (int) (mouseY / UI);
        drawList(g, ux, uy);
        drawWorldBar(g, ro);
        minY.visible = maxY.visible = false;
        if (corners != null) {
            drawCornerBar(g);
        } else if (ghost == null) {
            drawPickedCard(g);
        } else {
            drawToolbar(g);
        }
        name.visible = ghost != null && ghost.source != null && !toolFolded;
        search.visible = !folded;
        if (askOwner) {
            drawOwnerQuestion(g);
        }
        String hud = "x " + mouseBX + "  z " + mouseBZ + "   " + (scale >= 1 ? (int) scale + " px a block" : "");
        g.fill(uw - font.width(hud) - 12, uh - 16, uw - 4, uh - 4, 0xC0050608);
        g.drawString(font, hud, uw - font.width(hud) - 8, uh - 14, TEXT, false);
        if (!message.isEmpty()) {
            g.fill(4, uh - 16, 8 + font.width(message), uh - 4, 0xC0050608);
            g.drawString(font, message, 6, uh - 14, messageOk ? TQ : BAD, false);
        }
        super.render(g, ux, uy, partial);
        g.pose().popPose();
    }

    /** Card units: a map pixel position divided by {@link #UI}. */
    private static int ui(int px) {
        return (int) (px / UI);
    }

    private void drawTerrain(GuiGraphics g) {
        int cx0 = Math.floorDiv((int) Math.floor(ox), 16), cz0 = Math.floorDiv((int) Math.floor(oz), 16);
        int cx1 = Math.floorDiv((int) Math.ceil(ox + width / scale), 16), cz1 = Math.floorDiv((int) Math.ceil(oz + height / scale), 16);
        List<Long> missing = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                byte[] colours = MapTileCache.chunk(state.dimension(), key);
                if (colours == null) {
                    missing.add(key);
                    continue;
                }
                if (colours.length < 256) {
                    continue; // nothing there
                }
                Tex t = textures.get(key);
                if (t == null || t.from() != colours) {
                    if (t != null) {
                        minecraft.getTextureManager().release(t.id());
                    }
                    NativeImage img = new NativeImage(16, 16, false);
                    for (int i = 0; i < 256; i++) {
                        int packed = colours[i] & 0xFF;
                        img.setPixelRGBA(i % 16, i / 16, packed == 0 ? 0 : MapColor.getColorFromPackedId(packed));
                    }
                    DynamicTexture tex = new DynamicTexture(img);
                    t = new Tex(colours, minecraft.getTextureManager().register("bsp_rg_plotmap", tex), tex);
                    textures.put(key, t);
                }
                int x0 = sx(cx * 16), z0 = sz(cz * 16), x1 = sx(cx * 16 + 16), z1 = sz(cz * 16 + 16);
                g.blit(t.id(), x0, z0, x1 - x0, z1 - z0, 0, 0, 16, 16, 16, 16);
            }
        }
        if (!missing.isEmpty()) {
            MapTileCache.want(state.dimension(), missing, false);
        }
        if (scale >= 8) { // a block grid for placing to the block
            int bx0 = (int) Math.floor(ox), bz0 = (int) Math.floor(oz);
            for (int bx = bx0; bx <= ox + width / scale; bx++) {
                g.fill(sx(bx), 0, sx(bx) + 1, height, 0x30000000);
            }
            for (int bz = bz0; bz <= oz + height / scale; bz++) {
                g.fill(0, sz(bz), width, sz(bz) + 1, 0x30000000);
            }
        }
    }

    private void drawPlots(GuiGraphics g) {
        for (Plot p : state.plots()) {
            if (ghost != null && p.id().equals(ghost.moving)) {
                continue;
            }
            int c = colour(p.type());
            int x0 = sx(p.minX()), z0 = sz(p.minZ()), x1 = sx(p.maxX() + 1), z1 = sz(p.maxZ() + 1);
            if (!p.type().equals("open_for_buying")) {
                g.fill(x0, z0, x1, z1, (c & 0xFFFFFF) | 0x30000000);
            }
            frame(g, x0, z0, x1 - x0, z1 - z0, p.id().equals(picked) ? 0xFFFFFFFF : c);
            if (x1 - x0 > 30 && z1 - z0 > 10) {
                g.drawString(font, font.plainSubstrByWidth(p.id() + (p.locked() ? "" : "  " + Tr.t("unlocked")), x1 - x0 - 4), x0 + 3, z0 + 2, c, false);
            }
        }
    }

    /** Corner mode: the world's protected areas and mines as grey dotted outlines, to line up against (D80). */
    private void drawOthers(GuiGraphics g) {
        for (Plot p : state.others()) {
            int x0 = sx(p.minX()), z0 = sz(p.minZ()), x1 = sx(p.maxX() + 1), z1 = sz(p.maxZ() + 1);
            for (int x = x0; x < x1; x += 4) {
                g.fill(x, z0, Math.min(x + 2, x1), z0 + 1, 0xC09AA3B5);
                g.fill(x, z1 - 1, Math.min(x + 2, x1), z1, 0xC09AA3B5);
            }
            for (int z = z0; z < z1; z += 4) {
                g.fill(x0, z, x0 + 1, Math.min(z + 2, z1), 0xC09AA3B5);
                g.fill(x1 - 1, z, x1, Math.min(z + 2, z1), 0xC09AA3B5);
            }
            if (x1 - x0 > 30 && z1 - z0 > 10) {
                g.drawString(font, font.plainSubstrByWidth(p.id(), x1 - x0 - 4), x0 + 3, z0 + 2, MUTED, false);
            }
        }
    }

    /** Corner mode: the box from the first corner to the second, or to the block under the mouse until it is clicked. */
    private void drawCorners(GuiGraphics g) {
        if (corner1 == null) {
            return;
        }
        int[] other = corner2 != null ? corner2 : snapCorner(mouseBX, mouseBZ);
        int bx0 = Math.min(corner1[0], other[0]), bz0 = Math.min(corner1[1], other[1]);
        int bx1 = Math.max(corner1[0], other[0]) + 1, bz1 = Math.max(corner1[1], other[1]) + 1;
        int x0 = sx(bx0), z0 = sz(bz0), x1 = sx(bx1), z1 = sz(bz1);
        g.fill(x0, z0, x1, z1, (TQ & 0xFFFFFF) | 0x40000000);
        frame(g, x0, z0, x1 - x0, z1 - z0, TQ);
        frame(g, x0 - 1, z0 - 1, x1 - x0 + 2, z1 - z0 + 2, 0x80FFFFFF);
        for (int[] c : corner2 != null ? new int[][]{corner1, corner2} : new int[][]{corner1}) {
            int cx = sx(c[0] + 0.5), cz = sz(c[1] + 0.5);
            g.fill(cx - 4, cz - 4, cx + 5, cz + 5, 0xFFFFFFFF);
            g.fill(cx - 3, cz - 3, cx + 4, cz + 4, TQ);
        }
    }

    /** With SNAP on (SHIFT flips it), a corner within two blocks of a plot's or region's side lands just outside it, touching. */
    private int[] snapCorner(int bx, int bz) {
        if (snap == hasShiftDown()) {
            return new int[]{bx, bz};
        }
        int x = bx, z = bz;
        List<Plot> all = new ArrayList<>(state.plots());
        all.addAll(state.others());
        for (Plot p : all) {
            for (int edge : new int[]{p.minX() - 1, p.maxX() + 1}) {
                if (Math.abs(bx - edge) <= 2) {
                    x = edge;
                }
            }
            for (int edge : new int[]{p.minZ() - 1, p.maxZ() + 1}) {
                if (Math.abs(bz - edge) <= 2) {
                    z = edge;
                }
            }
        }
        return new int[]{x, z};
    }

    /** A corner clicked on the map: the first, then the second; a click after both starts again. */
    private void cornerClick(int bx, int bz) {
        int[] c = snapCorner(bx, bz);
        if (corner1 == null || corner2 != null) {
            corner1 = c;
            corner2 = null;
        } else {
            corner2 = c;
        }
    }

    @Nullable
    private Integer yValue(EditBox box) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Why the corners can't be confirmed yet, or null. */
    @Nullable
    private String cornerProblem() {
        if (corner1 == null) {
            return Tr.t("pm_click_first_corner", corners);
        }
        if (corner2 == null) {
            return Tr.t("pm_click_second_corner");
        }
        Integer lo = yValue(minY), hi = yValue(maxY);
        if (lo == null || hi == null || lo > hi || lo < bottom || hi > top) {
            return Tr.t("pm_heights_run", bottom, top);
        }
        return null;
    }

    /** The corner bar (D71, D72, option A of D80): title with the steps, the height, VERTICAL FILL, SNAP, CONFIRM, CANCEL. */
    private void drawCornerBar(GuiGraphics g) {
        int w = 420, h = toolFolded ? 16 : 48, x = 8, y = uh - h - 22;
        String problem = cornerProblem();
        boolean ready = problem == null;
        card(g, x, y, w, h, corner2 != null && !ready ? BAD : TQ);
        String title = Tr.t("pm_corners_for", corners);
        g.drawString(font, title, x + 5, y + 4, TEXT, false);
        String steps = (corner1 == null ? "1 ●  2 ○" : corner2 == null ? "1 ✓  2 ●" : "1 ✓  2 ✓");
        g.drawString(font, steps, x + 12 + font.width(title), y + 4, MUTED, false);
        chip(g, x + w - 16, y + 2, 12, 12, toolFolded ? "▸" : "▾", false, MUTED, () -> toolFolded = !toolFolded);
        if (toolFolded) {
            return;
        }
        int ry = y + 16;
        g.drawString(font, "Y", x + 5, ry + 3, MUTED, false);
        minY.setX(x + 14);
        minY.setY(ry);
        minY.visible = true;
        g.drawString(font, Tr.t("pm_to"), x + 59, ry + 3, MUTED, false);
        maxY.setX(x + 59 + font.width(Tr.t("pm_to")) + 4);
        maxY.setY(ry);
        maxY.visible = true;
        int cx = maxY.getX() + 46;
        chip(g, cx, ry, 76, 12, Tr.t("vertical_fill"), true, GOLD, () -> {
            minY.setValue(String.valueOf(bottom));
            maxY.setValue(String.valueOf(top));
        });
        chip(g, cx + 80, ry, 36, 12, Tr.t("pm_snap"), snap, TQ, () -> snap = !snap);
        chip(g, x + w - 112, ry, 54, 12, Tr.t("pm_confirm"), ready && !cornersSent, TQ, ready && !cornersSent ? this::confirmCorners : null);
        chip(g, x + w - 54, ry, 48, 12, Tr.t("cancel"), false, BAD, this::cancelCorners);
        String line = problem;
        if (ready) {
            int wide = Math.abs(corner1[0] - corner2[0]) + 1, deep = Math.abs(corner1[1] - corner2[1]) + 1;
            line = Tr.t("pm_corners_size", wide, deep, yValue(minY), yValue(maxY));
        }
        g.drawString(font, font.plainSubstrByWidth(line, w - 10), x + 5, ry + 17, ready ? MUTED : corner2 != null ? BAD : TQ, false);
    }

    private void confirmCorners() {
        if (corners == null || cornerProblem() != null) {
            return;
        }
        int lo = yValue(minY), hi = yValue(maxY);
        send(new PlotMapActionPacket(PlotMapActionPacket.CORNERS, corners, "", "", Math.min(corner1[0], corner2[0]), lo, Math.min(corner1[1], corner2[1]),
                Math.max(corner1[0], corner2[0]), hi, Math.max(corner1[1], corner2[1]), 0, false, false, 0));
        cornersSent = true;
    }

    /** CANCEL or Esc in corner mode: nothing is changed; back to the admin screen the admin came from. */
    private void cancelCorners() {
        if (corners != null) {
            backToRegions(corners);
        }
    }

    private void backToRegions(String id) {
        corners = null;
        if (minecraft != null) {
            minecraft.setScreen(new RegionScreen(id));
        }
        RegionNetwork.CHANNEL.sendToServer(com.mrgregles.bsp_region_guard.network.RegionActionPacket.of(com.mrgregles.bsp_region_guard.network.RegionActionPacket.LIST, id));
        RegionNetwork.CHANNEL.sendToServer(com.mrgregles.bsp_region_guard.network.RegionActionPacket.of(com.mrgregles.bsp_region_guard.network.RegionActionPacket.SELECT, id));
    }

    private void drawGhost(GuiGraphics g) {
        String problem = problem();
        int c = problem == null ? TQ : BAD;
        int x0 = sx(ghost.x), z0 = sz(ghost.z), x1 = sx(ghost.x + ghost.w), z1 = sz(ghost.z + ghost.d);
        g.fill(x0, z0, x1, z1, (c & 0xFFFFFF) | 0x50000000);
        frame(g, x0, z0, x1 - x0, z1 - z0, c);
        frame(g, x0 - 1, z0 - 1, x1 - x0 + 2, z1 - z0 + 2, 0x80FFFFFF);
    }

    /** The same rule as the server's (D59), so the copy shows red or green before it is sent. */
    @Nullable
    private String problem() {
        if (ghost == null) {
            return null;
        }
        int gx1 = ghost.x + ghost.w - 1, gz1 = ghost.z + ghost.d - 1;
        for (Plot p : state.plots()) {
            if (p.id().equals(ghost.moving)) {
                continue;
            }
            boolean meets = ghost.x <= p.maxX() && gx1 >= p.minX() && ghost.z <= p.maxZ() && gz1 >= p.minZ()
                    && ghost.minY <= p.maxY() && ghost.maxY >= p.minY();
            if (!meets) {
                continue;
            }
            boolean inside = ghost.x >= p.minX() && gx1 <= p.maxX() && ghost.z >= p.minZ() && gz1 <= p.maxZ()
                    && ghost.minY >= p.minY() && ghost.maxY <= p.maxY();
            if (inside && ghost.priority > p.priority()) {
                continue;
            }
            return inside ? Tr.t("pm_priority_above", p.id(), p.priority()) : Tr.t("pm_overlaps", p.id());
        }
        return null;
    }

    /** SNAP (D66): edges within two blocks of a neighbour's line up with it; SHIFT flips the switch while held. */
    private void snapGhost() {
        if (ghost == null || snap == hasShiftDown()) {
            return;
        }
        for (Plot p : state.plots()) {
            if (p.id().equals(ghost.moving)) {
                continue;
            }
            for (int edge : new int[]{p.minX(), p.maxX() + 1}) {
                if (Math.abs(ghost.x - edge) <= 2) {
                    ghost.x = edge;
                } else if (Math.abs(ghost.x + ghost.w - edge) <= 2) {
                    ghost.x = edge - ghost.w;
                }
            }
            for (int edge : new int[]{p.minZ(), p.maxZ() + 1}) {
                if (Math.abs(ghost.z - edge) <= 2) {
                    ghost.z = edge;
                } else if (Math.abs(ghost.z + ghost.d - edge) <= 2) {
                    ghost.z = edge - ghost.d;
                }
            }
        }
    }

    private void drawList(GuiGraphics g, int mouseX, int mouseY) {
        int x = 8, y = 8, h = folded ? 20 : 58 + LIST_ROWS * ROW + 6;
        card(g, x, y, LIST_W, h, TQ);
        g.drawString(font, Tr.t("plots"), x + 6, y + 6, TQ, false);
        chip(g, x + LIST_W - 18, y + 4, 12, 12, folded ? "▸" : "▾", false, MUTED, () -> folded = !folded);
        if (folded) {
            return;
        }
        String[][] filters = {{"all", Tr.t("filter.all")}, {"plot_for_sale", Tr.t("filter.sale")}, {"claim", Tr.t("filter.claim")}, {"open_for_buying", Tr.t("pm_open")}};
        int fx = x + 6;
        for (String[] f : filters) {
            int w = font.width(f[1]) + 8;
            chip(g, fx, y + 34, w, 12, f[1], filter.equals(f[0]), TQ, () -> filter = f[0]);
            fx += w + 3;
        }
        List<Plot> rows = new ArrayList<>();
        String q = search.getValue().toLowerCase(Locale.ROOT);
        for (Plot p : state.plots()) {
            if ((filter.equals("all") || p.type().equals(filter)) && (q.isEmpty() || p.id().contains(q) || p.name().toLowerCase(Locale.ROOT).contains(q))) {
                rows.add(p);
            }
        }
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rows.size() - LIST_ROWS)));
        int ry = y + 58; // below the search box (26 to 38) and the filters (42 to 54)
        g.fill(x + 4, ry - 1, x + LIST_W - 4, ry + LIST_ROWS * ROW + 1, INSET);
        for (int i = 0; i < LIST_ROWS && i + listScroll < rows.size(); i++) {
            Plot p = rows.get(i + listScroll);
            int yy = ry + i * ROW;
            if (p.id().equals(picked)) {
                g.fill(x + 4, yy, x + LIST_W - 4, yy + ROW, 0x3319D3B0);
            }
            g.fill(x + 7, yy + 3, x + 12, yy + 8, colour(p.type()));
            g.drawString(font, font.plainSubstrByWidth(p.id(), LIST_W - 60), x + 16, yy + 2, TEXT, false);
            String lock = p.locked() ? "" : Tr.t("unlocked");
            g.drawString(font, lock, x + LIST_W - 8 - font.width(lock), yy + 2, BAD, false);
            hits.add(new Hit(x + 4, yy, LIST_W - 8, ROW, () -> snapTo(p)));
        }
        if (rows.isEmpty()) {
            g.drawString(font, Tr.t("pm_no_plots"), x + 8, ry + 2, MUTED, false);
        }
    }

    private void snapTo(Plot p) {
        picked = p.id();
        float fit = Math.min(width / (p.width() * 3f), height / (p.depth() * 3f));
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, fit));
        centreOn(p.minX() + p.width() / 2.0, p.minZ() + p.depth() / 2.0);
    }

    /** The world card, top right: the world switcher, editable or read-only, UNDO and REDO (D80, kept clear of the other cards), refresh. */
    private void drawWorldBar(GuiGraphics g, boolean ro) {
        String world = state.dimension().replace("minecraft:", "");
        String access = state.editable() ? Tr.t("pm_editable") : Tr.t("read_only");
        String u = Tr.t("pm_undo", state.undo()), r = Tr.t("pm_redo", state.redo());
        int undoW = ro ? 0 : font.width(u) + font.width(r) + 30;
        int w = 70 + font.width(world) + font.width(access) + 40 + undoW;
        int x = uw - w - 8, y = 8;
        card(g, x, y, w, 20, state.editable() ? TQ : GOLD);
        chip(g, x + 4, y + 4, 12, 12, "<", false, TEXT, () -> switchWorld(-1));
        g.drawString(font, world, x + 20, y + 6, TEXT, false);
        chip(g, x + 24 + font.width(world), y + 4, 12, 12, ">", false, TEXT, () -> switchWorld(1));
        g.drawString(font, access, x + 42 + font.width(world), y + 6, state.editable() ? MUTED : GOLD, false);
        if (!ro) {
            int ux = x + w - 30 - undoW;
            g.fill(ux - 4, y + 4, ux - 3, y + 16, LINE);
            chip(g, ux, y + 4, font.width(u) + 10, 12, u, state.undo() > 0, TQ, state.undo() > 0 ? () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.UNDO, "", false)) : null);
            chip(g, ux + font.width(u) + 14, y + 4, font.width(r) + 10, 12, r, state.redo() > 0, TQ, state.redo() > 0 ? () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.REDO, "", false)) : null);
        }
        chip(g, x + w - 26, y + 4, 22, 12, "↻", false, MUTED, () -> {
            releaseTextures();
            visibleChunks(true);
        });
    }

    private void visibleChunks(boolean refresh) {
        List<Long> all = new ArrayList<>();
        for (int cx = Math.floorDiv((int) ox, 16); cx <= Math.floorDiv((int) (ox + width / scale), 16); cx++) {
            for (int cz = Math.floorDiv((int) oz, 16); cz <= Math.floorDiv((int) (oz + height / scale), 16); cz++) {
                all.add(ChunkPos.asLong(cx, cz));
            }
        }
        MapTileCache.want(state.dimension(), all, refresh);
    }

    private void switchWorld(int step) {
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        List<String> worlds = new ArrayList<>();
        for (ResourceKey<Level> k : minecraft.getConnection().levels()) {
            worlds.add(k.location().toString());
        }
        worlds.sort(null);
        int i = Math.max(0, worlds.indexOf(state.dimension()));
        String next = worlds.get(Math.floorMod(i + step, worlds.size()));
        send(PlotMapActionPacket.simple(PlotMapActionPacket.VIEW, next, false));
    }

    private void drawPickedCard(GuiGraphics g) {
        Plot p = picked == null ? null : plot(picked);
        if (p == null) {
            return;
        }
        int w = 150, h = cardFolded ? 18 : state.editable() ? 78 : 46;
        int x = Math.max(LIST_W + 16, Math.min(uw - w - 8, ui(sx(p.maxX() + 1)) + 8)), y = Math.max(32, Math.min(uh - h - 24, ui(sz(p.minZ()))));
        card(g, x, y, w, h, colour(p.type()));
        g.drawString(font, font.plainSubstrByWidth(p.name().equals(p.id()) ? p.id() : p.name() + " (" + p.id() + ")", w - 26), x + 5, y + 5, TEXT, false);
        chip(g, x + w - 16, y + 3, 12, 12, cardFolded ? "▸" : "▾", false, MUTED, () -> cardFolded = !cardFolded);
        if (cardFolded) {
            return;
        }
        g.drawString(font, Tr.t("pm_size", p.width(), p.depth(), p.minY(), p.maxY(), p.priority()), x + 5, y + 17, MUTED, false);
        g.drawString(font, p.owner().isEmpty() ? Tr.t("type." + p.type()) : Tr.t("pm_owner", p.owner()), x + 5, y + 29, MUTED, false);
        if (state.editable()) {
            chip(g, x + 5, y + 44, 50, 13, Tr.t("pm_copy"), true, GOLD, () -> startCopy(p));
            chip(g, x + 60, y + 44, 70, 13, p.locked() ? Tr.t("pm_unlock") : Tr.t("pm_lock"), !p.locked(), BAD, () -> {
                if (p.locked()) {
                    unlocking = p.id();
                    send(PlotMapActionPacket.simple(PlotMapActionPacket.UNLOCK, p.id(), false));
                } else {
                    send(PlotMapActionPacket.simple(PlotMapActionPacket.LOCK, p.id(), false));
                }
            });
            // D69: backups of the plot's blocks
            chip(g, x + 5, y + 60, 50, 13, Tr.t("pm_backup"), false, MUTED, () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.BACKUP, p.id(), false)));
            chip(g, x + 60, y + 60, 70, 13, Tr.t("pm_backups"), p.id().equals(state.backupsOf()), TQ,
                    () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.BACKUPS, p.id(), false)));
            if (p.id().equals(state.backupsOf())) {
                drawBackups(g, x, y + h + 2, w);
            }
        }
    }

    private void drawBackups(GuiGraphics g, int x, int y, int w) {
        List<String> files = state.backups();
        int h = 8 + Math.max(1, Math.min(files.size(), 8)) * 12;
        card(g, x, y, w + 60, h, LINE);
        if (files.isEmpty()) {
            g.drawString(font, Tr.t("pm_no_backups"), x + 5, y + 5, MUTED, false);
            return;
        }
        for (int i = 0; i < Math.min(files.size(), 8); i++) {
            String f = files.get(i);
            String[] parts = f.replace(".nbt", "").split("__");
            String label = parts.length >= 3 ? parts[1] + "  " + parts[2] : f;
            boolean armed = f.equals(restoreArmed);
            chip(g, x + 4, y + 4 + i * 12, w + 52, 11, armed ? Tr.t("pm_restore_sure", label) : label, armed, BAD, () -> {
                if (f.equals(restoreArmed)) {
                    restoreArmed = null;
                    send(PlotMapActionPacket.simple(PlotMapActionPacket.RESTORE, f, false));
                } else {
                    restoreArmed = f;
                }
            });
        }
    }

    private void startCopy(Plot p) {
        ghost = new Ghost();
        ghost.source = p.id();
        ghost.w = p.width();
        ghost.d = p.depth();
        ghost.minY = p.minY();
        ghost.maxY = p.maxY();
        ghost.priority = p.priority();
        ghost.following = true;
        name.setValue(suggestName(p.id()));
    }

    private String suggestName(String id) {
        for (int n = 2; n < 1000; n++) {
            String candidate = (id.length() > 28 ? id.substring(0, 28) : id) + "_" + n;
            if (plot(candidate) == null) {
                return candidate;
            }
        }
        return id + "_copy";
    }

    private void drawToolbar(GuiGraphics g) {
        boolean paste = ghost.source != null;
        int w = (paste ? 120 : 0) + 384, h = toolFolded ? 16 : 48;
        int x = Math.max(4, Math.min(uw - w - 4, ui(sx(ghost.x)))), y = Math.max(32, Math.min(uh - h - 22, ui(sz(ghost.z + ghost.d)) + 6));
        String problem = problem();
        card(g, x, y, w, h, problem == null ? TQ : BAD);
        String title = paste ? Tr.t("pm_copy_of", ghost.source) : Tr.t("pm_moving", ghost.moving);
        g.drawString(font, title, x + 5, y + 4, TEXT, false);
        if (toolFolded) {
            String line = problem == null ? Tr.t("pm_fits", ghost.x, ghost.z, ghost.minY, ghost.maxY) : problem;
            g.drawString(font, font.plainSubstrByWidth(line, w - 30 - font.width(title)), x + 12 + font.width(title), y + 4, problem == null ? MUTED : BAD, false);
        }
        chip(g, x + w - 16, y + 2, 12, 12, toolFolded ? "▸" : "▾", false, MUTED, () -> toolFolded = !toolFolded);
        if (toolFolded) {
            return;
        }
        y += 14;
        int cx = x + 5;
        if (paste) {
            name.setX(cx);
            name.setY(y + 4);
            cx += 116;
        }
        g.drawString(font, "P " + ghost.priority, cx, y + 6, TEXT, false);
        chip(g, cx + 28, y + 4, 12, 12, "-", false, TEXT, () -> ghost.priority = Math.max(0, ghost.priority - 1));
        chip(g, cx + 42, y + 4, 12, 12, "+", false, TEXT, () -> ghost.priority++);
        chip(g, cx + 60, y + 4, 14, 12, "▲", false, TEXT, () -> {
            ghost.minY++;
            ghost.maxY++;
        });
        chip(g, cx + 76, y + 4, 14, 12, "▼", false, TEXT, () -> {
            ghost.minY--;
            ghost.maxY--;
        });
        chip(g, cx + 94, y + 4, 44, 12, Tr.t("pm_rotate"), false, TEXT, this::rotate);
        chip(g, cx + 142, y + 4, 36, 12, Tr.t("pm_snap"), snap, TQ, () -> snap = !snap);
        // D67: the plot's blocks, contents and entities go along (backed up first; a moved plot's old spot is rebuilt as ground)
        chip(g, cx + 182, y + 4, 74, 12, paste ? Tr.t("pm_copy_blocks") : Tr.t("pm_move_blocks"), ghost.blocks, GOLD, () -> ghost.blocks = !ghost.blocks);
        chip(g, cx + 260, y + 4, 54, 12, Tr.t("pm_confirm"), problem == null, TQ, problem == null ? () -> confirm(false) : null);
        chip(g, cx + 318, y + 4, 48, 12, Tr.t("cancel"), false, BAD, this::cancel);
        String line = problem == null ? Tr.t("pm_fits", ghost.x, ghost.z, ghost.minY, ghost.maxY) : problem;
        g.drawString(font, font.plainSubstrByWidth(line, w - 10), x + 5, y + 21, problem == null ? MUTED : BAD, false);
    }

    private void rotate() {
        if (ghost != null) {
            ghost.turns = (ghost.turns + 1) % 4;
            int cx = ghost.x + ghost.w / 2, cz = ghost.z + ghost.d / 2, t = ghost.w;
            ghost.w = ghost.d;
            ghost.d = t;
            ghost.x = cx - ghost.w / 2;
            ghost.z = cz - ghost.d / 2;
        }
    }

    private void confirm(boolean ownerConfirmed) {
        if (ghost == null) {
            return;
        }
        int x1 = ghost.x + ghost.w - 1, z1 = ghost.z + ghost.d - 1;
        if (ghost.source != null) {
            send(new PlotMapActionPacket(PlotMapActionPacket.PASTE, ghost.source, name.getValue().trim(), "", ghost.x, ghost.minY, ghost.z, x1, ghost.maxY, z1,
                    ghost.priority, false, ghost.blocks, ghost.turns));
        } else {
            send(new PlotMapActionPacket(PlotMapActionPacket.MOVE, ghost.moving, "", "", ghost.x, ghost.minY, ghost.z, x1, ghost.maxY, z1, ghost.priority,
                    ownerConfirmed, ghost.blocks, ghost.turns));
        }
    }

    private void cancel() {
        if (ghost != null && ghost.moving != null) {
            send(PlotMapActionPacket.simple(PlotMapActionPacket.LOCK, ghost.moving, false));
        }
        ghost = null;
        askOwner = false;
    }

    private void drawOwnerQuestion(GuiGraphics g) {
        int w = 280, h = 54, x = (uw - w) / 2, y = (uh - h) / 2;
        card(g, x, y, w, h, BAD);
        for (var line : font.split(Component.literal(message), w - 12)) {
            g.drawString(font, line, x + 6, y + 6, TEXT, false);
            y += 10;
        }
        y = (uh - h) / 2;
        chip(g, x + 6, y + h - 18, 80, 13, Tr.t("pm_move_anyway"), true, BAD, () -> {
            askOwner = false;
            confirm(true);
        });
        chip(g, x + 92, y + h - 18, 60, 13, Tr.t("cancel"), false, MUTED, () -> askOwner = false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double ux = mx / UI, uy = my / UI;
        if (super.mouseClicked(ux, uy, button)) {
            return true;
        }
        setFocused(null);
        for (EditBox b : new EditBox[]{search, name, minY, maxY}) {
            b.setFocused(false);
        }
        for (Hit h : new ArrayList<>(hits)) {
            if (h.contains(ux, uy)) {
                h.action().run();
                return true;
            }
        }
        for (Hit h : covers) {
            if (h.contains(ux, uy)) {
                return true;
            }
        }
        if (askOwner || button != 0) {
            return false;
        }
        if (corners != null) { // a click that does not drag places a corner (on release); a drag pans
            pressed = true;
            pressX = mx;
            pressY = my;
            panning = true;
            return true;
        }
        if (ghost != null) {
            if (ghost.following) {
                ghost.following = false; // dropped; drag it from here
                return true;
            }
            if (mouseBX >= ghost.x && mouseBX < ghost.x + ghost.w && mouseBZ >= ghost.z && mouseBZ < ghost.z + ghost.d) {
                dragDX = mouseBX - ghost.x;
                dragDZ = mouseBZ - ghost.z;
                return true;
            }
        } else {
            Plot best = null;
            for (Plot p : state.plots()) {
                if (mouseBX >= p.minX() && mouseBX <= p.maxX() && mouseBZ >= p.minZ() && mouseBZ <= p.maxZ()
                        && (best == null || p.width() * p.depth() < best.width() * best.depth())) {
                    best = p;
                }
            }
            picked = best == null ? picked : best.id();
        }
        panning = true;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (getFocused() != null && super.mouseDragged(mx / UI, my / UI, button, dx / UI, dy / UI)) {
            return true;
        }
        if (dragDX != Integer.MIN_VALUE && ghost != null) {
            ghost.x = (int) Math.floor(mx / scale + ox) - dragDX;
            ghost.z = (int) Math.floor(my / scale + oz) - dragDZ;
            snapGhost();
            return true;
        }
        if (panning) {
            ox -= dx / scale;
            oz -= dy / scale;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragDX = Integer.MIN_VALUE;
        panning = false;
        if (pressed && corners != null && Math.abs(mx - pressX) < 4 && Math.abs(my - pressY) < 4) {
            cornerClick((int) Math.floor(mx / scale + ox), (int) Math.floor(my / scale + oz));
        }
        pressed = false;
        return super.mouseReleased(mx / UI, my / UI, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!folded && mx / UI >= 8 && mx / UI < 8 + LIST_W && my / UI >= 8 && my / UI < 72 + LIST_ROWS * ROW) {
            listScroll -= (int) Math.signum(delta);
            return true;
        }
        double bx = mx / scale + ox, bz = my / scale + oz;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, delta > 0 ? scale * 1.25f : scale / 1.25f));
        ox = bx - mx / scale;
        oz = bz - my / scale;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) { // Esc: drop the copy first, then close
            if (askOwner) {
                askOwner = false;
                return true;
            }
            if (corners != null) {
                cancelCorners();
                return true;
            }
            if (ghost != null) {
                cancel();
                return true;
            }
            onClose();
            return true;
        }
        if (search.isFocused() || name.isFocused() || minY.isFocused() || maxY.isFocused()) {
            if ((key == 257 || key == 335) && corners != null && (minY.isFocused() || maxY.isFocused())) {
                confirmCorners(); // Enter in a height field confirms
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
        if (key == 82 && ghost != null) { // R
            rotate();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // ------------------------------------------------------------------ helpers

    private static void send(PlotMapActionPacket p) {
        RegionNetwork.CHANNEL.sendToServer(p);
    }

    private void releaseTextures() {
        if (minecraft != null) {
            textures.values().forEach(t -> minecraft.getTextureManager().release(t.id()));
        }
        textures.clear();
    }

    @Override
    public void removed() {
        releaseTextures();
        super.removed();
    }

    private void card(GuiGraphics g, int x, int y, int w, int h, int border) {
        covers.add(new Hit(x, y, w, h, () -> { }));
        g.fill(x, y, x + w, y + h, BG);
        frame(g, x, y, w, h, border);
    }

    private static void frame(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private void chip(GuiGraphics g, int x, int y, int w, int h, String label, boolean on, int colour, @Nullable Runnable action) {
        g.fill(x, y, x + w, y + h, on ? (colour & 0xFFFFFF) | 0x26000000 : INSET);
        frame(g, x, y, w, h, on ? colour : LINE);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, action == null ? 0xFF4A5060 : on ? colour : MUTED, false);
        if (action != null) {
            hits.add(new Hit(x, y, w, h, action));
        }
    }
}
