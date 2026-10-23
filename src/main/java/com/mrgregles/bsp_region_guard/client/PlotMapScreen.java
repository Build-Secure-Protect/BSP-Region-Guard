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
    private static final int ROW = 11, LIST_W = 200, LIST_ROWS = 18;
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

    /** Chunks per side in one terrain picture: 8 by 8 chunks are drawn at once instead of one by one. */
    private static final int GROUP = 8;
    /** Pictures kept while the map is open; beyond this, those not drawn this frame are let go. */
    private static final int MAX_GROUPS = 300;

    /**
     * One terrain picture of {@link #GROUP} by {@link #GROUP} chunks of one world. {@code from} holds the colours each
     * chunk was painted from, so a chunk is painted again only when new colours arrive for it.
     */
    private static final class Group {
        final String dimension;
        final byte[][] from = new byte[GROUP * GROUP][];
        final ResourceLocation id;
        final DynamicTexture texture;
        boolean any;
        int drawnAt;

        Group(String dimension, ResourceLocation id, DynamicTexture texture) {
            this.dimension = dimension;
            this.id = id;
            this.texture = texture;
        }
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
    /** SNAP while placing corners: off at first, so a corner lands exactly on the block clicked (B9). */
    private boolean cornerSnap;
    /** A backup file clicked once: a second click restores it. */
    @Nullable
    private String restoreArmed;
    private int dragDX = Integer.MIN_VALUE, dragDZ, listScroll;
    private String filter = "all";
    private final List<Hit> hits = new ArrayList<>();
    /** The cards drawn this frame (card units): a click on one that hits no button goes no further, so it never reaches the map. */
    private final List<Hit> covers = new ArrayList<>();
    private final Map<Long, Group> groups = new HashMap<>();
    private int frame;
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

    /** The map's modes (D83, D91): REGION shows and picks every region, PLOT and MINE their own kind and dim the rest. */
    private enum Mode { REGION, PLOT, MINE }

    /** The tools of every mode: pick a region, draw a new one, reshape the picked one with handles (D84, D85). */
    private enum Tool { PICK, DRAW, RESHAPE }

    /** Kept between openings of the map. */
    private static Mode mode = Mode.REGION;
    private static Tool tool = Tool.PICK;
    /** The region to centre on and pick when the map next opens (the admin screen's MAP button). */
    @Nullable
    static String focusNext;
    /** The top bar's height and where the list card starts, in card units. */
    private static final int BAR_H = 26, LIST_Y = 34;
    /** DRAW: the box's two corners; waiting for a second click; being dragged out; the CREATE sent and not answered yet. */
    @Nullable
    private int[] draw1, draw2;
    private boolean drawSecond, drawDragging;
    @Nullable
    private String createSent;
    private int draftPriority;
    /** RESHAPE: the picked region's box being changed (minX, minZ, maxX, maxZ); the handle dragged (W 1, E 2, N 4, S 8); DONE sent. */
    @Nullable
    private int[] shape;
    @Nullable
    private String shapeFor;
    private int handle;
    private boolean shapeSent, spaceDown, deleteArmed;
    private EditBox newId, price, cardName;

    public PlotMapScreen(PlotMapStatePacket state) {
        super(Component.literal(Tr.t("plot_map")));
        this.state = state;
        if (!state.corners().isEmpty()) {
            startCorners(state);
        }
    }

    private void startCorners(PlotMapStatePacket msg) {
        Plot had = plot(msg.corners());
        corners = msg.corners();
        corner1 = corner2 = null;
        bottom = msg.bottom();
        top = msg.top();
        cornersSent = false;
        ghost = null;
        picked = null;
        // a new region spans the whole height (D72); one that has corners keeps its own height until changed
        if (minY != null) {
            minY.setValue(String.valueOf(had == null ? bottom : had.minY()));
            maxY.setValue(String.valueOf(had == null ? top : had.maxY()));
        }
    }

    /** Leaves corner mode: back to the admin screen it was started from. */
    private void endCorners() {
        if (corners != null) {
            backToRegions(corners);
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
                endCorners(); // the corners are set
                return;
            }
        }
        if (createSent != null) { // the new region came back: pick it
            if (msg.ok() && plot(createSent) != null) {
                picked = createSent;
                draw1 = draw2 = null;
                drawSecond = false;
                tool = Tool.PICK;
            }
            createSent = null;
        }
        if (shapeSent) {
            shapeSent = false;
            if (msg.ok()) {
                shape = null; // taken from the region's new box next frame
            }
        }
        if (picked != null && plot(picked) == null) {
            picked = null; // deleted
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
        boolean first = search == null;
        if (first) {
            centreOn(state.playerX(), state.playerZ());
        }
        String q = search == null ? "" : search.getValue(), n = name == null ? "" : name.getValue();
        search = new EditBox(font, 14, LIST_Y + 18, LIST_W - 12, 12, Component.literal(Tr.t("search_plots")));
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
        String id = newId == null ? "" : newId.getValue(), pr = price == null ? "" : price.getValue(), cn = cardName == null ? "" : cardName.getValue();
        newId = new EditBox(font, 0, 0, 120, 12, Component.literal(Tr.t("pm_id")));
        newId.setMaxLength(32);
        newId.setValue(id);
        price = new EditBox(font, 0, 0, 70, 12, Component.literal(Tr.t("pm_price")));
        price.setMaxLength(12);
        price.setFilter(t -> t.matches("\\d*"));
        price.setValue(pr);
        cardName = new EditBox(font, 0, 0, 110, 12, Component.literal(Tr.t("name")));
        cardName.setMaxLength(64);
        cardName.setValue(cn);
        for (EditBox b : new EditBox[]{newId, price, cardName}) {
            b.visible = false;
            addRenderableWidget(b);
        }
        if (first && focusNext != null) { // the admin screen's MAP button: centre on its region and pick it
            Plot p = plot(focusNext);
            if (p != null) {
                snapTo(p);
            }
            focusNext = null;
        }
    }

    /** Whether a region belongs to the current mode: everything in REGION, plots in PLOT, mines and their shells in MINE. */
    private boolean inMode(Plot p) {
        return switch (mode) {
            case REGION -> true;
            case PLOT -> isPlot(p);
            case MINE -> p.type().equals("mine") || p.id().endsWith("_shell") && plot(p.id().substring(0, p.id().length() - 6)) != null
                    && plot(p.id().substring(0, p.id().length() - 6)).type().equals("mine");
        };
    }

    private void setMode(Mode m) {
        mode = m;
        filter = "all";
        listScroll = 0;
        Plot p = picked == null ? null : plot(picked);
        if (p != null && !inMode(p)) {
            picked = null;
        }
        resetTools();
    }

    private void setTool(Tool t) {
        tool = t;
        resetTools();
    }

    /** Drops a half-drawn box, a pending reshape and an armed DELETE. */
    private void resetTools() {
        draw1 = draw2 = null;
        drawSecond = drawDragging = false;
        shape = null;
        handle = 0;
        deleteArmed = false;
    }

    private void centreOn(double bx, double bz) {
        ox = bx - width / scale / 2;
        oz = bz - height / scale / 2;
    }

    /** Every region with corners in the world shown: plots first (they draw on top of the rest), then the others. */
    private List<Plot> all() {
        List<Plot> out = new ArrayList<>(state.others());
        out.addAll(state.plots());
        return out;
    }

    private static boolean isPlot(Plot p) {
        return p.type().equals("plot_for_sale") || p.type().equals("claim") || p.type().equals("open_for_buying");
    }

    @Nullable
    private Plot plot(String id) {
        for (Plot p : all()) {
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

    /** The admin screen's colours: claims violet, plots for sale, open areas and mines gold, protected areas teal. */
    private static int colour(String type) {
        return 0xFF000000 | RegionScreen.typeColour(type);
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
        drawPlots(g);
        if (corners != null) {
            drawCorners(g);
        }
        if (draw1 != null) {
            drawDraft(g);
        }
        if (tool == Tool.RESHAPE && corners == null && ghost == null) {
            drawHandles(g);
        }
        if (ghost != null) {
            if (ghost.following) {
                ghost.x = mouseBX - ghost.w / 2;
                ghost.z = mouseBZ - ghost.d / 2;
                snapGhost();
            }
            drawGhost(g);
        }
        if (corners != null) { // at every zoom: the block a click will use, snapped or not
            int[] at = snapCorner(mouseBX, mouseBZ);
            int x0 = sx(at[0]), z0 = sz(at[1]), w0 = sx(at[0] + 1) - x0, h0 = sz(at[1] + 1) - z0;
            if (w0 < 4) { // blocks too small to see one by one: a ring around it
                frame(g, x0 - 2, z0 - 2, w0 + 4, h0 + 4, 0xFFFFFFFF);
            } else {
                frame(g, x0, z0, w0, h0, 0xFFFFFFFF);
            }
        } else if (scale >= 4) {
            frame(g, sx(mouseBX), sz(mouseBZ), sx(mouseBX + 1) - sx(mouseBX), sz(mouseBZ + 1) - sz(mouseBZ), 0xCCFFFFFF);
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
        drawTopBar(g, ro);
        drawList(g, ux, uy);
        minY.visible = maxY.visible = newId.visible = price.visible = cardName.visible = false;
        if (corners != null) {
            drawCornerBar(g);
        } else if (ghost != null) {
            drawToolbar(g);
        } else if (draw2 != null) {
            drawDraftCard(g);
        } else {
            drawPickedCard(g);
            if (tool == Tool.RESHAPE && shapeTarget() != null) {
                drawReshapeBar(g);
            }
        }
        boolean drawHint = tool == Tool.DRAW && draw2 == null, shapeHint = tool == Tool.RESHAPE && shapeTarget() == null;
        if ((drawHint || shapeHint) && state.editable() && corners == null && ghost == null && message.isEmpty()) {
            String hint = shapeHint ? Tr.t("pm_pick_to_reshape") : draw1 == null ? Tr.t("pm_draw_hint") : Tr.t("pm_draw_second", draw1[0], draw1[1], mouseBX, mouseBZ);
            g.fill(4, uh - 16, 8 + font.width(hint), uh - 4, 0xC0050608);
            g.drawString(font, hint, 6, uh - 14, TQ, false);
        }
        name.visible = ghost != null && ghost.source != null && !toolFolded;
        search.visible = !folded;
        if (askOwner) {
            drawOwnerQuestion(g);
        }
        String hud = "x " + mouseBX + "  z " + mouseBZ + "   " + (scale >= 1 ? (int) scale + " px a block" : "") + "   " + Tr.t("pm_space_pans");
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

    /**
     * The terrain, one picture per 8 by 8 chunks (review 2026-10-23): at the widest zoom that is a few dozen draws a
     * frame instead of about two thousand. A picture is painted again only where new chunk colours arrived.
     */
    private void drawTerrain(GuiGraphics g) {
        frame++;
        String dim = state.dimension();
        int cx0 = Math.floorDiv((int) Math.floor(ox), 16), cz0 = Math.floorDiv((int) Math.floor(oz), 16);
        int cx1 = Math.floorDiv((int) Math.ceil(ox + width / scale), 16), cz1 = Math.floorDiv((int) Math.ceil(oz + height / scale), 16);
        List<Long> missing = new ArrayList<>();
        for (int gx = Math.floorDiv(cx0, GROUP); gx <= Math.floorDiv(cx1, GROUP); gx++) {
            for (int gz = Math.floorDiv(cz0, GROUP); gz <= Math.floorDiv(cz1, GROUP); gz++) {
                long gkey = ChunkPos.asLong(gx, gz);
                Group grp = groups.get(gkey);
                if (grp != null && !grp.dimension.equals(dim)) {
                    minecraft.getTextureManager().release(grp.id);
                    groups.remove(gkey);
                    grp = null;
                }
                boolean changed = false;
                for (int i = 0; i < GROUP * GROUP; i++) {
                    int cx = gx * GROUP + i % GROUP, cz = gz * GROUP + i / GROUP;
                    byte[] colours = MapTileCache.chunk(dim, ChunkPos.asLong(cx, cz));
                    if (colours == null) {
                        if (cx >= cx0 && cx <= cx1 && cz >= cz0 && cz <= cz1) {
                            missing.add(ChunkPos.asLong(cx, cz));
                        }
                        continue;
                    }
                    if (grp == null) {
                        DynamicTexture tex = new DynamicTexture(GROUP * 16, GROUP * 16, true); // starts clear
                        grp = new Group(dim, minecraft.getTextureManager().register("bsp_rg_plotmap", tex), tex);
                        groups.put(gkey, grp);
                    }
                    if (grp.from[i] != colours) {
                        grp.from[i] = colours;
                        paint(grp.texture.getPixels(), (i % GROUP) * 16, (i / GROUP) * 16, colours);
                        grp.any |= colours.length >= 256;
                        changed = true;
                    }
                }
                if (grp == null) {
                    continue;
                }
                if (changed) {
                    grp.texture.upload();
                }
                grp.drawnAt = frame;
                if (grp.any) {
                    int x0 = sx(gx * GROUP * 16), z0 = sz(gz * GROUP * 16), x1 = sx((gx + 1) * GROUP * 16), z1 = sz((gz + 1) * GROUP * 16);
                    g.blit(grp.id, x0, z0, x1 - x0, z1 - z0, 0, 0, GROUP * 16, GROUP * 16, GROUP * 16, GROUP * 16);
                }
            }
        }
        if (groups.size() > MAX_GROUPS) { // panned far: let go of the pictures out of sight
            groups.values().removeIf(grp -> {
                boolean old = grp.drawnAt != frame;
                if (old) {
                    minecraft.getTextureManager().release(grp.id);
                }
                return old;
            });
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

    /** Every region: plots filled lightly, open areas, protected areas and mines as outlines (they can be large). */
    private void drawPlots(GuiGraphics g) {
        for (Plot p : all()) {
            if (ghost != null && p.id().equals(ghost.moving)) {
                continue;
            }
            boolean in = inMode(p);
            int c = in ? colour(p.type()) : (colour(p.type()) & 0xFFFFFF) | 0x50000000; // other kinds dimmed (D91)
            int x0 = sx(p.minX()), z0 = sz(p.minZ()), x1 = sx(p.maxX() + 1), z1 = sz(p.maxZ() + 1);
            if (in && (isPlot(p) && !p.type().equals("open_for_buying") || mode == Mode.MINE)) {
                g.fill(x0, z0, x1, z1, (c & 0xFFFFFF) | 0x30000000);
            }
            frame(g, x0, z0, x1 - x0, z1 - z0, p.id().equals(picked) ? 0xFFFFFFFF : c);
            if (in && x1 - x0 > 30 && z1 - z0 > 10) {
                g.drawString(font, font.plainSubstrByWidth(p.id() + (p.locked() ? "" : "  " + Tr.t("unlocked")), x1 - x0 - 4), x0 + 3, z0 + 2, c, false);
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

    /** With corner SNAP on (SHIFT flips it), see {@link PlotMap#snapCorner}; otherwise the block clicked. */
    private int[] snapCorner(int bx, int bz) {
        if (cornerSnap == hasShiftDown()) {
            return new int[]{bx, bz};
        }
        List<Plot> all = new ArrayList<>(state.plots());
        all.addAll(state.others());
        return com.mrgregles.bsp_region_guard.claim.PlotMap.snapCorner(bx, bz, all, corners);
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
        int[] at = snapCorner(mouseBX, mouseBZ);
        if (corner1 == null) {
            return Tr.t("pm_click_first_corner", corners, at[0], at[1]);
        }
        if (corner2 == null) {
            return Tr.t("pm_click_second_corner", corner1[0], corner1[1], at[0], at[1]);
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
        chip(g, cx + 80, ry, 36, 12, Tr.t("pm_snap"), cornerSnap, TQ, () -> cornerSnap = !cornerSnap);
        chip(g, x + w - 112, ry, 54, 12, Tr.t("pm_confirm"), ready && !cornersSent, TQ, ready && !cornersSent ? this::confirmCorners : null);
        chip(g, x + w - 54, ry, 48, 12, Tr.t("cancel"), false, BAD, this::cancelCorners);
        String line = problem;
        if (ready) {
            int wide = Math.abs(corner1[0] - corner2[0]) + 1, deep = Math.abs(corner1[1] - corner2[1]) + 1;
            line = Tr.t("pm_corners_size", corner1[0], corner1[1], corner2[0], corner2[1], wide, deep, yValue(minY), yValue(maxY));
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

    /** CANCEL or Esc in corner mode: nothing is changed; back where corner mode began. */
    private void cancelCorners() {
        endCorners();
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
        int x = 8, y = LIST_Y, h = folded ? 20 : 58 + LIST_ROWS * ROW + 6;
        card(g, x, y, LIST_W, h, TQ);
        g.drawString(font, Tr.t("pm_regions"), x + 6, y + 6, TQ, false);
        chip(g, x + LIST_W - 18, y + 4, 12, 12, folded ? "▸" : "▾", false, MUTED, () -> folded = !folded);
        if (folded) {
            return;
        }
        String[][] filters = switch (mode) {
            case REGION -> new String[][]{{"all", Tr.t("filter.all")}, {"protected", Tr.t("filter.prot")}, {"plot_for_sale", Tr.t("filter.sale")}, {"claim", Tr.t("filter.claim")},
                    {"open_for_buying", Tr.t("pm_open")}, {"mine", Tr.t("filter.mine")}};
            case PLOT -> new String[][]{{"all", Tr.t("filter.all")}, {"plot_for_sale", Tr.t("filter.sale")}, {"claim", Tr.t("filter.claim")}, {"open_for_buying", Tr.t("pm_open")}};
            case MINE -> new String[][]{{"all", Tr.t("filter.all")}};
        };
        int fx = x + 6;
        for (String[] f : filters) {
            int w = font.width(f[1]) + 8;
            chip(g, fx, y + 34, w, 12, f[1], filter.equals(f[0]), TQ, () -> filter = f[0]);
            fx += w + 3;
        }
        List<Plot> rows = new ArrayList<>();
        String q = search.getValue().toLowerCase(Locale.ROOT);
        for (Plot p : all()) {
            if (inMode(p) && (filter.equals("all") || p.type().equals(filter)) && (q.isEmpty() || p.id().contains(q) || p.name().toLowerCase(Locale.ROOT).contains(q))) {
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

    /**
     * The top bar (layout A of D91): REGION, PLOT and MINE; PICK, DRAW and RESHAPE; MINE mode's RESET and SHELL for the
     * picked mine; the world switcher, UNDO and REDO (D80), refresh.
     */
    private void drawTopBar(GuiGraphics g, boolean ro) {
        card(g, 0, 0, uw, BAR_H, state.editable() ? TQ : GOLD);
        int x = 6, y = 7;
        for (Mode m : Mode.values()) {
            String label = Tr.t("mode." + m.name().toLowerCase(Locale.ROOT));
            int w = font.width(label) + 12;
            chip(g, x, y, w, 13, label, mode == m, TQ, () -> setMode(m));
            x += w + 1;
        }
        x += 10;
        if (!ro) {
            for (Tool t : Tool.values()) {
                String label = Tr.t("tool." + t.name().toLowerCase(Locale.ROOT));
                int w = font.width(label) + 12;
                chip(g, x, y, w, 13, label, tool == t, TQ, () -> setTool(t));
                x += w + 1;
            }
            Plot p = picked == null ? null : plot(picked);
            if (mode == Mode.MINE) {
                x += 10;
                boolean mine = p != null && p.type().equals("mine");
                String reset = Tr.t("pm_reset"), shell = Tr.t("pm_shell");
                chip(g, x, y, font.width(reset) + 12, 13, reset, mine, BAD, mine ? () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.RESET, p.id(), false)) : null);
                x += font.width(reset) + 13;
                chip(g, x, y, font.width(shell) + 12, 13, shell, false, MUTED, mine ? () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.SHELL, p.id(), false)) : null);
            }
        }
        String world = state.dimension().replace("minecraft:", "");
        String access = state.editable() ? Tr.t("pm_editable") : Tr.t("read_only");
        String u = Tr.t("pm_undo", state.undo()), r = Tr.t("pm_redo", state.redo());
        int right = uw - 6;
        chip(g, right - 22, y, 22, 13, "↻", false, MUTED, () -> {
            releaseTextures();
            visibleChunks(true);
        });
        right -= 28;
        if (!ro) {
            chip(g, right - font.width(r) - 10, y, font.width(r) + 10, 13, r, state.redo() > 0, TQ,
                    state.redo() > 0 ? () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.REDO, "", false)) : null);
            right -= font.width(r) + 14;
            chip(g, right - font.width(u) - 10, y, font.width(u) + 10, 13, u, state.undo() > 0, TQ,
                    state.undo() > 0 ? () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.UNDO, "", false)) : null);
            right -= font.width(u) + 18;
        }
        g.drawString(font, access, right - font.width(access), y + 3, state.editable() ? MUTED : GOLD, false);
        right -= font.width(access) + 8;
        chip(g, right - 12, y, 12, 13, ">", false, TEXT, () -> switchWorld(1));
        g.drawString(font, world, right - 16 - font.width(world), y + 3, TEXT, false);
        chip(g, right - 32 - font.width(world), y, 12, 13, "<", false, TEXT, () -> switchWorld(-1));
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

    /**
     * The picked region's card (D86): its size and owner; when editable, its name and priority, a row for the mode
     * (REGION: type; PLOT: copy, lock, backups), buttons that open the admin screen's tabs, then VERTICAL FILL,
     * RESHAPE and DELETE (SURE?, undoable, D87). Folds to its name.
     */
    private void drawPickedCard(GuiGraphics g) {
        Plot p = picked == null ? null : plot(picked);
        if (p == null) {
            return;
        }
        boolean plot = isPlot(p), edit = state.editable();
        boolean rowB = edit && (mode == Mode.REGION || mode == Mode.PLOT && plot);
        int w = 210, h = cardFolded ? 18 : !edit ? 46 : rowB ? 108 : 92;
        int x = Math.max(LIST_W + 16, Math.min(uw - w - 8, ui(sx(p.maxX() + 1)) + 8)), y = Math.max(BAR_H + 6, Math.min(uh - h - 24, ui(sz(p.minZ()))));
        if (tool == Tool.RESHAPE) {
            y = BAR_H + 6; // out of the way of the handles
            x = uw - w - 8;
        }
        card(g, x, y, w, h, colour(p.type()));
        g.drawString(font, font.plainSubstrByWidth(p.name().equals(p.id()) ? p.id() : p.name() + " (" + p.id() + ")", w - 26), x + 5, y + 5, TEXT, false);
        chip(g, x + w - 16, y + 3, 12, 12, cardFolded ? "▸" : "▾", false, MUTED, () -> cardFolded = !cardFolded);
        if (cardFolded) {
            return;
        }
        g.drawString(font, Tr.t("pm_size", p.width(), p.depth(), p.minY(), p.maxY(), p.priority()), x + 5, y + 17, MUTED, false);
        g.drawString(font, p.owner().isEmpty() ? Tr.t("type." + p.type()) : Tr.t("pm_owner", p.owner()), x + 5, y + 29, MUTED, false);
        if (!edit) {
            return;
        }
        int ry = y + 42;
        // name (Enter saves) and priority
        if (!p.id().equals(cardNameFor)) {
            cardName.setValue(p.name());
            cardNameFor = p.id();
        }
        cardName.setX(x + 5);
        cardName.setY(ry);
        cardName.setWidth(110);
        cardName.visible = true;
        String priority = "P " + p.priority();
        g.drawString(font, priority, Math.max(x + 117, x + 147 - font.width(priority)), ry + 2, TEXT, false); // ends before the "-"
        chip(g, x + 150, ry, 12, 12, "-", false, TEXT, () -> sendPriority(p, p.priority() - 1));
        chip(g, x + 164, ry, 12, 12, "+", false, TEXT, () -> sendPriority(p, p.priority() + 1));
        ry += 16;
        if (rowB && mode == Mode.REGION) {
            String[][] types = {{"protected", Tr.t("filter.prot")}, {"plot_for_sale", Tr.t("filter.sale")}, {"open_for_buying", Tr.t("pm_open")}, {"mine", Tr.t("filter.mine")}};
            int tx = x + 5;
            for (String[] t : types) {
                int tw = font.width(t[1]) + 10;
                chip(g, tx, ry, tw, 12, t[1], p.type().equals(t[0]), colour(t[0]),
                        p.type().equals(t[0]) ? null : () -> send(new PlotMapActionPacket(PlotMapActionPacket.TYPE, p.id(), t[0], "", 0, 0, 0, 0, 0, 0, 0, false, false, 0)));
                tx += tw + 3;
            }
            ry += 16;
        } else if (rowB) { // PLOT mode, a plot: copy, lock, backups (D57 to D69)
            chip(g, x + 5, ry, 46, 12, Tr.t("pm_copy"), true, GOLD, () -> startCopy(p));
            chip(g, x + 54, ry, 56, 12, p.locked() ? Tr.t("pm_unlock") : Tr.t("pm_lock"), !p.locked(), BAD, () -> {
                if (p.locked()) {
                    unlocking = p.id();
                    send(PlotMapActionPacket.simple(PlotMapActionPacket.UNLOCK, p.id(), false));
                } else {
                    send(PlotMapActionPacket.simple(PlotMapActionPacket.LOCK, p.id(), false));
                }
            });
            chip(g, x + 113, ry, 44, 12, Tr.t("pm_backup"), false, MUTED, () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.BACKUP, p.id(), false)));
            chip(g, x + 160, ry, 46, 12, Tr.t("pm_backups"), p.id().equals(state.backupsOf()), TQ,
                    () -> send(PlotMapActionPacket.simple(PlotMapActionPacket.BACKUPS, p.id(), false)));
            ry += 16;
        }
        // the admin screen's tabs for this region; Esc there comes back here (D86)
        int[] tabs = switch (mode) {
            case REGION -> new int[]{1, 2, 6, 7};
            case PLOT -> new int[]{4, 1, 2, 7};
            case MINE -> new int[]{5, 1, 7};
        };
        String[] names = {"", "tab.flags", "tab.access", "tab.ranks", "tab.sale", "tab.mine", "tab.teleport", "tab.info"};
        int tx = x + 5;
        for (int t : tabs) {
            String label = Tr.t(names[t]);
            int tw = font.width(label) + 10;
            chip(g, tx, ry, tw, 12, label, false, TQ, () -> openAdmin(p.id(), t));
            tx += tw + 3;
        }
        if (mode == Mode.PLOT && (p.type().equals("plot_for_sale") || p.type().equals("open_for_buying"))) {
            String label = Tr.t("look.button");
            chip(g, tx, ry, font.width(label) + 10, 12, label, false, GOLD, () -> {
                if (minecraft != null) {
                    minecraft.setScreen(new PlotLookScreen(p.id(), this)); // D111; Esc comes back to the map
                }
            });
        }
        ry += 16;
        chip(g, x + 5, ry, 76, 12, Tr.t("vertical_fill"), true, GOLD, () -> send(new PlotMapActionPacket(PlotMapActionPacket.CORNERS, p.id(), "", "",
                p.minX(), state.bottom(), p.minZ(), p.maxX(), state.top(), p.maxZ(), 0, false, false, 0)));
        chip(g, x + 84, ry, 60, 12, Tr.t("tool.reshape"), tool == Tool.RESHAPE, TQ, () -> setTool(Tool.RESHAPE));
        chip(g, x + w - 61, ry, 56, 12, deleteArmed ? Tr.t("sure") : Tr.t("delete"), deleteArmed, BAD, () -> {
            if (deleteArmed) {
                deleteArmed = false;
                send(PlotMapActionPacket.simple(PlotMapActionPacket.DELETE, p.id(), false));
            } else {
                deleteArmed = true;
            }
        });
        if (rowB && mode == Mode.PLOT && p.id().equals(state.backupsOf())) {
            drawBackups(g, x, y + h + 2, w - 60);
        }
    }

    /** The region whose name field the card last filled, so typing is not overwritten every frame. */
    @Nullable
    private String cardNameFor;

    private void sendPriority(Plot p, int priority) {
        send(new PlotMapActionPacket(PlotMapActionPacket.PRIORITY, p.id(), "", "", 0, 0, 0, 0, 0, 0, priority, false, false, 0));
    }

    /** Opens one of the admin screen's tabs for a region; Esc there brings this map back on it. */
    private void openAdmin(String id, int tab) {
        if (minecraft != null) {
            minecraft.setScreen(new RegionScreen(id, tab, true));
        }
        RegionNetwork.CHANNEL.sendToServer(com.mrgregles.bsp_region_guard.network.RegionActionPacket.of(com.mrgregles.bsp_region_guard.network.RegionActionPacket.LIST, id));
        RegionNetwork.CHANNEL.sendToServer(com.mrgregles.bsp_region_guard.network.RegionActionPacket.of(com.mrgregles.bsp_region_guard.network.RegionActionPacket.SELECT, id));
    }

    // ------------------------------------------------------------------ DRAW (D84)

    /** The box being drawn: from the first corner to the second, or to the block under the mouse. */
    private void drawDraft(GuiGraphics g) {
        int[] other = draw2 != null ? draw2 : new int[]{mouseBX, mouseBZ};
        int x0 = sx(Math.min(draw1[0], other[0])), z0 = sz(Math.min(draw1[1], other[1]));
        int x1 = sx(Math.max(draw1[0], other[0]) + 1), z1 = sz(Math.max(draw1[1], other[1]) + 1);
        int c = mode == Mode.REGION ? TQ : GOLD;
        g.fill(x0, z0, x1, z1, (c & 0xFFFFFF) | 0x40000000);
        frame(g, x0, z0, x1 - x0, z1 - z0, c);
        frame(g, x0 - 1, z0 - 1, x1 - x0 + 2, z1 - z0 + 2, 0x80FFFFFF);
    }

    /** Starts the new region's card once both corners are known: the height the mode suggests, an id, priority 0. */
    private void draftReady(int[] second) {
        draw2 = second;
        drawSecond = drawDragging = false;
        draftPriority = 0;
        newId.setValue("");
        price.setValue("");
        int lo = state.bottom(), hi = state.top();
        if (mode == Mode.MINE && minecraft != null && minecraft.player != null) {
            // a mine refills its whole box: start 16 blocks below the admin's feet, not the whole column (D91)
            int feet = minecraft.player.getBlockY();
            lo = Math.max(state.bottom(), feet - 16);
            hi = Math.max(lo, Math.min(state.top(), feet - 1));
        }
        minY.setValue(String.valueOf(lo));
        maxY.setValue(String.valueOf(hi));
        setFocused(newId);
        newId.setFocused(true);
    }

    @Nullable
    private String draftProblem() {
        Integer lo = yValue(minY), hi = yValue(maxY);
        if (lo == null || hi == null || lo > hi || lo < state.bottom() || hi > state.top()) {
            return Tr.t("pm_heights_run", state.bottom(), state.top());
        }
        if (newId.getValue().isBlank()) {
            return Tr.t("pm_type_an_id");
        }
        if (plot(newId.getValue().trim().toLowerCase(Locale.ROOT)) != null) {
            return Tr.t("pm_id_taken");
        }
        if (mode == Mode.PLOT && (price.getValue().isBlank() || price.getValue().equals("0"))) {
            return Tr.t("pm_needs_price");
        }
        return null;
    }

    /** NEW REGION / NEW PLOT / NEW MINE (the mode sets the type): id, priority, height, price for a plot, CREATE, CANCEL. */
    private void drawDraftCard(GuiGraphics g) {
        int w = 230, h = mode == Mode.PLOT ? 92 : 76;
        int bx1 = Math.max(draw1[0], draw2[0]) + 1, bz0 = Math.min(draw1[1], draw2[1]);
        int x = Math.max(LIST_W + 16, Math.min(uw - w - 8, ui(sx(bx1)) + 8)), y = Math.max(BAR_H + 6, Math.min(uh - h - 24, ui(sz(bz0))));
        int c = mode == Mode.REGION ? TQ : GOLD;
        card(g, x, y, w, h, c);
        g.drawString(font, Tr.t("pm_new." + mode.name().toLowerCase(Locale.ROOT)), x + 5, y + 5, c, false);
        int wide = Math.abs(draw1[0] - draw2[0]) + 1, deep = Math.abs(draw1[1] - draw2[1]) + 1;
        String size = wide + " x " + deep;
        g.drawString(font, size, x + w - 6 - font.width(size), y + 5, MUTED, false);
        int ry = y + 18;
        g.drawString(font, Tr.t("pm_id"), x + 5, ry + 2, MUTED, false);
        newId.setX(x + 50);
        newId.setY(ry);
        newId.visible = true;
        g.drawString(font, "P " + draftPriority, x + 176, ry + 2, TEXT, false);
        chip(g, x + 200, ry, 12, 12, "-", false, TEXT, () -> draftPriority--);
        chip(g, x + 214, ry, 12, 12, "+", false, TEXT, () -> draftPriority++);
        ry += 16;
        g.drawString(font, "Y", x + 5, ry + 2, MUTED, false);
        minY.setX(x + 50);
        minY.setY(ry);
        minY.visible = true;
        g.drawString(font, Tr.t("pm_to"), x + 94, ry + 2, MUTED, false);
        maxY.setX(x + 98 + font.width(Tr.t("pm_to")));
        maxY.setY(ry);
        maxY.visible = true;
        chip(g, x + 160, ry, 66, 12, Tr.t("vertical_fill"), true, GOLD, () -> {
            minY.setValue(String.valueOf(state.bottom()));
            maxY.setValue(String.valueOf(state.top()));
        });
        ry += 16;
        if (mode == Mode.PLOT) {
            g.drawString(font, Tr.t("pm_price"), x + 5, ry + 2, MUTED, false);
            price.setX(x + 50);
            price.setY(ry);
            price.visible = true;
            ry += 16;
        }
        String problem = draftProblem();
        g.drawString(font, font.plainSubstrByWidth(problem != null ? problem : Tr.t("pm_corners_size", Math.min(draw1[0], draw2[0]), Math.min(draw1[1], draw2[1]),
                Math.max(draw1[0], draw2[0]), Math.max(draw1[1], draw2[1]), wide, deep, yValue(minY), yValue(maxY)), w - 10), x + 5, ry + 1, problem == null ? MUTED : BAD, false);
        ry += 13;
        chip(g, x + w - 116, ry, 54, 12, Tr.t("pm_create"), problem == null && createSent == null, TQ, problem == null && createSent == null ? this::createDraft : null);
        chip(g, x + w - 58, ry, 52, 12, Tr.t("cancel"), false, BAD, this::resetTools);
    }

    private void createDraft() {
        if (draw1 == null || draw2 == null || draftProblem() != null) {
            return;
        }
        String type = switch (mode) {
            case REGION -> "protected";
            case PLOT -> "plot_for_sale";
            case MINE -> "mine";
        };
        String id = newId.getValue().trim().toLowerCase(Locale.ROOT);
        send(new PlotMapActionPacket(PlotMapActionPacket.CREATE, id, type, price.getValue().trim(), Math.min(draw1[0], draw2[0]), yValue(minY), Math.min(draw1[1], draw2[1]),
                Math.max(draw1[0], draw2[0]), yValue(maxY), Math.max(draw1[1], draw2[1]), draftPriority, false, false, 0));
        createSent = id;
    }

    // ------------------------------------------------------------------ RESHAPE (D85)

    /** The picked region RESHAPE works on, when there is one in this mode. */
    @Nullable
    private Plot shapeTarget() {
        Plot p = picked == null ? null : plot(picked);
        return p != null && inMode(p) && state.editable() ? p : null;
    }

    /** The box being reshaped: the pending one, started from the region's own box (and its height) on first use. */
    @Nullable
    private int[] shape() {
        Plot p = shapeTarget();
        if (p == null) {
            shape = null;
            return null;
        }
        if (shape == null || !p.id().equals(shapeFor)) {
            shapeFor = p.id();
            shape = new int[]{p.minX(), p.minZ(), p.maxX(), p.maxZ()};
            minY.setValue(String.valueOf(p.minY()));
            maxY.setValue(String.valueOf(p.maxY()));
        }
        return shape;
    }

    /** Handle positions in map pixels: three across and three down, the middle one left out; each with its sides. */
    private int[][] handles(int[] b) {
        int[] xs = {sx(b[0]), sx((b[0] + b[2] + 1) / 2.0), sx(b[2] + 1)}, zs = {sz(b[1]), sz((b[1] + b[3] + 1) / 2.0), sz(b[3] + 1)};
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (i == 1 && j == 1) {
                    continue;
                }
                out.add(new int[]{xs[i], zs[j], (i == 0 ? 1 : i == 2 ? 2 : 0) | (j == 0 ? 4 : j == 2 ? 8 : 0)});
            }
        }
        return out.toArray(new int[0][]);
    }

    private void drawHandles(GuiGraphics g) {
        int[] b = shape();
        if (b == null) {
            return;
        }
        int x0 = sx(b[0]), z0 = sz(b[1]), x1 = sx(b[2] + 1), z1 = sz(b[3] + 1);
        g.fill(x0, z0, x1, z1, 0x30FFFFFF);
        frame(g, x0, z0, x1 - x0, z1 - z0, 0xFFFFFFFF);
        frame(g, x0 + 1, z0 + 1, x1 - x0 - 2, z1 - z0 - 2, 0xFFFFFFFF);
        for (int[] hd : handles(b)) {
            g.fill(hd[0] - 4, hd[1] - 4, hd[0] + 5, hd[1] + 5, 0xFF000000);
            g.fill(hd[0] - 3, hd[1] - 3, hd[0] + 4, hd[1] + 4, hd[2] == handle ? TQ : 0xFFFFFFFF);
        }
    }

    /** The sides of the handle under the mouse (map pixels), or 0. */
    private int handleAt(double mx, double my) {
        int[] b = shape();
        if (b == null) {
            return 0;
        }
        for (int[] hd : handles(b)) {
            if (Math.abs(mx - hd[0]) <= 6 && Math.abs(my - hd[1]) <= 6) {
                return hd[2];
            }
        }
        return 0;
    }

    private boolean shapeChanged(Plot p) {
        int[] b = shape();
        Integer lo = yValue(minY), hi = yValue(maxY);
        return b != null && (b[0] != p.minX() || b[1] != p.minZ() || b[2] != p.maxX() || b[3] != p.maxZ() || lo == null || hi == null || lo != p.minY() || hi != p.maxY());
    }

    /** The reshape bar: drag a side or corner, the height, VERTICAL FILL, DONE (undoable), RESET. */
    private void drawReshapeBar(GuiGraphics g) {
        Plot p = shapeTarget();
        int[] b = shape();
        if (p == null || b == null) {
            return;
        }
        int w = 440, h = toolFolded ? 16 : 48, x = 8, y = uh - h - 22;
        Integer lo = yValue(minY), hi = yValue(maxY);
        boolean heightOk = lo != null && hi != null && lo <= hi && lo >= state.bottom() && hi <= state.top();
        boolean changed = shapeChanged(p);
        card(g, x, y, w, h, heightOk ? TQ : BAD);
        String title = Tr.t("pm_reshape_of", p.id());
        g.drawString(font, title, x + 5, y + 4, TEXT, false);
        g.drawString(font, Tr.t("pm_reshape_hint"), x + 12 + font.width(title), y + 4, MUTED, false);
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
            minY.setValue(String.valueOf(state.bottom()));
            maxY.setValue(String.valueOf(state.top()));
        });
        boolean ready = changed && heightOk && !shapeSent;
        chip(g, x + w - 112, ry, 54, 12, Tr.t("pm_done"), ready, TQ, ready ? this::reshapeDone : null);
        chip(g, x + w - 54, ry, 48, 12, Tr.t("pm_reset_shape"), false, MUTED, () -> shape = null);
        String line = heightOk ? Tr.t("pm_corners_size", b[0], b[1], b[2], b[3], b[2] - b[0] + 1, b[3] - b[1] + 1, lo, hi) : Tr.t("pm_heights_run", state.bottom(), state.top());
        g.drawString(font, font.plainSubstrByWidth(line, w - 10), x + 5, ry + 17, heightOk ? MUTED : BAD, false);
    }

    private void reshapeDone() {
        Plot p = shapeTarget();
        int[] b = shape();
        Integer lo = yValue(minY), hi = yValue(maxY);
        if (p == null || b == null || lo == null || hi == null) {
            return;
        }
        send(new PlotMapActionPacket(PlotMapActionPacket.CORNERS, p.id(), "", "", b[0], lo, b[1], b[2], hi, b[3], 0, false, false, 0));
        shapeSent = true;
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
        for (EditBox b : new EditBox[]{search, name, minY, maxY, newId, price, cardName}) {
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
        if (askOwner) {
            return false;
        }
        if (button == 1 || button == 0 && spaceDown) { // right-drag, or SPACE held, pans in every mode (D84)
            panning = true;
            return true;
        }
        if (button != 0) {
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
        } else if (tool == Tool.DRAW && state.editable()) {
            pressed = true;
            pressX = mx;
            pressY = my;
            if (!drawSecond) { // a fresh box: drag it out, or click again for the second corner
                draw1 = new int[]{mouseBX, mouseBZ};
                draw2 = null;
                drawDragging = true;
            }
            return true;
        } else {
            if (tool == Tool.RESHAPE) {
                handle = handleAt(mx, my);
                if (handle != 0) {
                    return true;
                }
            }
            Plot best = null;
            for (Plot p : all()) {
                if (inMode(p) && mouseBX >= p.minX() && mouseBX <= p.maxX() && mouseBZ >= p.minZ() && mouseBZ <= p.maxZ()
                        && (best == null || p.width() * p.depth() < best.width() * best.depth())) {
                    best = p;
                }
            }
            if (best != null && !best.id().equals(picked)) {
                picked = best.id();
                shape = null;
                deleteArmed = false;
            }
        }
        panning = true;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (getFocused() != null && super.mouseDragged(mx / UI, my / UI, button, dx / UI, dy / UI)) {
            return true;
        }
        int bx = (int) Math.floor(mx / scale + ox), bz = (int) Math.floor(my / scale + oz);
        if (handle != 0 && shape != null) { // RESHAPE: the dragged sides follow the mouse, a whole block at a time
            if ((handle & 1) != 0) {
                shape[0] = Math.min(bx, shape[2]);
            }
            if ((handle & 2) != 0) {
                shape[2] = Math.max(bx, shape[0]);
            }
            if ((handle & 4) != 0) {
                shape[1] = Math.min(bz, shape[3]);
            }
            if ((handle & 8) != 0) {
                shape[3] = Math.max(bz, shape[1]);
            }
            return true;
        }
        if (drawDragging && pressed) {
            return true; // the box follows the mouse while drawn
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
        boolean click = Math.abs(mx - pressX) < 4 && Math.abs(my - pressY) < 4;
        int bx = (int) Math.floor(mx / scale + ox), bz = (int) Math.floor(my / scale + oz);
        if (pressed && corners != null && click) {
            cornerClick(bx, bz);
        } else if (pressed && tool == Tool.DRAW && draw1 != null && draw2 == null) {
            if (drawSecond && click) {
                draftReady(new int[]{bx, bz}); // the second of two clicks
            } else if (drawDragging && !click) {
                draftReady(new int[]{bx, bz}); // dragged out
            } else if (drawDragging) {
                drawSecond = true; // a click: wait for the second corner (SPACE or right-drag pans meanwhile)
                drawDragging = false;
            }
        }
        handle = 0;
        pressed = false;
        return super.mouseReleased(mx / UI, my / UI, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!folded && mx / UI >= 8 && mx / UI < 8 + LIST_W && my / UI >= LIST_Y && my / UI < LIST_Y + 64 + LIST_ROWS * ROW) {
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
            if (draw1 != null || shape != null || deleteArmed) {
                resetTools();
                return true;
            }
            onClose();
            return true;
        }
        boolean typing = search.isFocused() || name.isFocused() || minY.isFocused() || maxY.isFocused() || newId.isFocused() || price.isFocused() || cardName.isFocused();
        if (typing) {
            if (key == 257 || key == 335) { // Enter: confirm whatever the field belongs to
                if (corners != null && (minY.isFocused() || maxY.isFocused())) {
                    confirmCorners();
                    return true;
                }
                if (draw2 != null && (newId.isFocused() || price.isFocused() || minY.isFocused() || maxY.isFocused())) {
                    createDraft();
                    return true;
                }
                if (tool == Tool.RESHAPE && shape != null && (minY.isFocused() || maxY.isFocused())) {
                    reshapeDone();
                    return true;
                }
                if (cardName.isFocused() && picked != null) {
                    send(new PlotMapActionPacket(PlotMapActionPacket.RENAME, picked, cardName.getValue().trim(), "", 0, 0, 0, 0, 0, 0, 0, false, false, 0));
                    cardName.setFocused(false);
                    setFocused(null);
                    return true;
                }
            }
            return super.keyPressed(key, scan, mods);
        }
        if (key == 32) { // SPACE held pans (D84)
            spaceDown = true;
            return true;
        }
        if (key == 82 && ghost != null) { // R
            rotate();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (key == 32) {
            spaceDown = false;
            panning = false;
        }
        return super.keyReleased(key, scan, mods);
    }

    // ------------------------------------------------------------------ helpers

    private static void send(PlotMapActionPacket p) {
        RegionNetwork.CHANNEL.sendToServer(p);
    }

    /** One chunk's colours into a picture at ({@code x}, {@code y}); empty ground stays clear. */
    private static void paint(@Nullable NativeImage img, int x, int y, byte[] colours) {
        if (img == null) {
            return;
        }
        for (int i = 0; i < 256; i++) {
            int packed = colours.length < 256 ? 0 : colours[i] & 0xFF;
            img.setPixelRGBA(x + i % 16, y + i / 16, packed == 0 ? 0 : MapColor.getColorFromPackedId(packed));
        }
    }

    private void releaseTextures() {
        if (minecraft != null) {
            groups.values().forEach(grp -> minecraft.getTextureManager().release(grp.id));
        }
        groups.clear();
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
