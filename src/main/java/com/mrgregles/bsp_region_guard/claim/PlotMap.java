package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.LocationFlag;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The plot map's server side (owner decisions D57 to D65): copy a plot and paste it elsewhere as a new plot for sale,
 * move an existing plot once it is unlocked, and undo or redo the last 15 of these per admin.
 *
 * <p>Rules: only BSP-Core admins, only in the world they stand in (D62). A paste or a move keeps the plot's size and
 * height span, turned 90 degrees or not. It may not partly overlap another plot, claim or open area; it may sit wholly
 * inside one whose priority is lower (D59), and may not swallow one. Plots are locked unless an admin unlocked them
 * (stored on the plot); a confirmed move locks it again (D60). A claim someone owns moves only when the admin has
 * confirmed that its builds stay where they are.
 */
public final class PlotMap {
    /** Type-data key: present and true while an admin has unlocked the plot for moving. Absent means locked. */
    public static final String UNLOCKED = "unlocked";
    public static final int HISTORY = 15;
    /** The kinds of region a plot may not partly overlap. */
    private static final Set<RegionType> PLOTS = Set.of(RegionType.CLAIM, RegionType.PLOT_FOR_SALE, RegionType.OPEN_FOR_BUYING);
    /** Claim data that belongs to a sale and is not copied (who bought it, when, for how much). */
    private static final Set<String> SALE_ONLY = Set.of(ClaimData.BOUGHT_FOR, ClaimData.BOUGHT_AT, ClaimData.ORIGIN, ClaimData.ORIGINAL_PRICE, UNLOCKED);

    /** What happened. {@code askOwner} means a claim someone owns was not moved until the admin confirms. */
    public record Result(boolean ok, String message, boolean askOwner) {
        static Result ok(String m) {
            return new Result(true, m, false);
        }

        static Result fail(String m) {
            return new Result(false, m, false);
        }
    }

    /** What an undo step was: a paste, a move, new corners, a region drawn on the map, or one deleted there (D88). */
    private enum Kind { PASTE, MOVE, CORNERS, CREATE, DELETE }

    /** One confirmed action: the region as it was before (null for a paste or a create) and after. */
    private record Step(String dimension, String id, @Nullable CompoundTag before, CompoundTag after,
                        @Nullable String source, int rotation, @Nullable PlotBlocks.Job blocks, Kind kind) {
        Step(String dimension, String id, @Nullable CompoundTag before, CompoundTag after, @Nullable String source, int rotation, @Nullable PlotBlocks.Job blocks) {
            this(dimension, id, before, after, source, rotation, blocks, before == null ? Kind.PASTE : Kind.MOVE);
        }
    }

    private static final Map<UUID, Deque<Step>> UNDO = new HashMap<>(), REDO = new HashMap<>();

    private PlotMap() {
    }

    // ------------------------------------------------------------------ checks

    public static boolean isPlot(Region r) {
        return PLOTS.contains(r.type());
    }

    public static boolean locked(Region r) {
        return !r.typeData().getBoolean(UNLOCKED);
    }

    /** Null when the box may hold a plot of this priority; otherwise why not (D59). */
    @Nullable
    public static String overlapProblem(RegionStore store, Cuboid box, int priority, @Nullable String ignore) {
        for (Region r : store.intersecting(box)) {
            if (r.isGlobal() || r.bounds() == null || !isPlot(r) || r.id().equals(ignore)) {
                continue;
            }
            Cuboid b = r.bounds();
            boolean inside = box.minX() >= b.minX() && box.minY() >= b.minY() && box.minZ() >= b.minZ()
                    && box.maxX() <= b.maxX() && box.maxY() <= b.maxY() && box.maxZ() <= b.maxZ();
            if (inside) {
                if (priority <= r.priority()) {
                    return "it sits inside " + r.id() + ", so its priority must be above " + r.priority() + ".";
                }
                continue;
            }
            boolean covers = b.minX() >= box.minX() && b.minY() >= box.minY() && b.minZ() >= box.minZ()
                    && b.maxX() <= box.maxX() && b.maxY() <= box.maxY() && b.maxZ() <= box.maxZ();
            return covers ? "it would cover " + r.id() + "." : "it partly overlaps " + r.id() + ".";
        }
        return null;
    }

    /** True when {@code box} has the same height span as {@code from} and the same footprint, turned or not. */
    static boolean sameShape(Cuboid from, Cuboid box) {
        boolean straight = box.sizeX() == from.sizeX() && box.sizeZ() == from.sizeZ();
        boolean turned = box.sizeX() == from.sizeZ() && box.sizeZ() == from.sizeX();
        return box.sizeY() == from.sizeY() && (straight || turned);
    }

    @Nullable
    private static String refuse(ServerPlayer admin, @Nullable Region r, String what) {
        if (!BspCore.isAdmin(admin)) {
            return "Only BSP-Core admins can use the plot map.";
        }
        if (r == null) {
            return "No region called '" + what + "' in this world. The plot map edits only the world you are in.";
        }
        if (!isPlot(r) || r.bounds() == null) {
            return r.id() + " is not a plot, claim or open area.";
        }
        return null;
    }

    // ------------------------------------------------------------------ lock

    /** Unlocks a plot for moving, or locks it again. */
    public static Result setLocked(ServerPlayer admin, String id, boolean locked) {
        RegionStore store = RegionStore.get(admin.serverLevel());
        Region r = store.get(id);
        String no = refuse(admin, r, id);
        if (no != null) {
            return Result.fail(no);
        }
        if (locked) {
            r.typeData().remove(UNLOCKED);
        } else {
            r.typeData().putBoolean(UNLOCKED, true);
        }
        store.touch(r);
        return Result.ok(r.id() + (locked ? " is locked." : " is unlocked: drag it, then confirm."));
    }

    // ------------------------------------------------------------------ paste

    /**
     * Copies {@code sourceId} into a new plot for sale called {@code newId} at {@code box} with {@code priority}. The
     * copy keeps the flags (banned blocks included), the buyer's allowed flags, the price, messages and triggers;
     * never the owners or members.
     */
    public static Result paste(ServerPlayer admin, String sourceId, String newId, String displayName, Cuboid box, int priority) {
        return paste(admin, sourceId, newId, displayName, box, priority, false, 0);
    }

    /**
     * As {@link #paste(ServerPlayer, String, String, String, Cuboid, int)}; with {@code blocks} the source's blocks
     * (and their contents and entities) are copied too, turned {@code rotation} quarter turns, after a backup of what
     * is at the new spot (D67, D69).
     */
    public static Result paste(ServerPlayer admin, String sourceId, String newId, String displayName, Cuboid box, int priority, boolean blocks, int rotation) {
        ServerLevel level = admin.serverLevel();
        if (PlotBlocks.busy(sourceId)) {
            return Result.fail("The blocks of " + sourceId + " are still being worked on.");
        }
        RegionStore store = RegionStore.get(level);
        Region src = store.get(sourceId);
        String no = refuse(admin, src, sourceId);
        if (no != null) {
            return Result.fail(no);
        }
        String id;
        try {
            id = Region.validateId(newId.trim());
        } catch (IllegalArgumentException e) {
            return Result.fail("Name: " + e.getMessage() + ".");
        }
        if (store.has(id)) {
            return Result.fail("A region called " + id + " already exists here.");
        }
        if (!sameShape(src.bounds(), box)) {
            return Result.fail("The copy must keep " + src.id() + "'s size: " + src.bounds().sizeX() + " x " + src.bounds().sizeZ() + ", " + src.bounds().sizeY() + " high.");
        }
        String overlap = overlapProblem(store, box, priority, null);
        if (overlap != null) {
            return Result.fail("Can't place it here: " + overlap);
        }
        Region copy = new Region(id, level.dimension(), box, RegionType.PLOT_FOR_SALE);
        copy.setName(displayName == null || displayName.isBlank() ? id : displayName.trim());
        copy.setPriority(priority);
        Region parent = store.parentOf(src);
        if (parent != null && parent.bounds() != null && contains(parent.bounds(), box)) {
            copy.setParentId(parent.id());
        }
        src.flags().forEach((flag, value) -> setAny(copy, flag, value));
        moveLocations(copy, src.bounds(), box);
        CompoundTag data = src.typeData().copy();
        long price = data.contains(ClaimData.PRICE) ? data.getLong(ClaimData.PRICE)
                : data.contains(ClaimData.ORIGINAL_PRICE) ? data.getLong(ClaimData.ORIGINAL_PRICE) : ClaimData.boughtFor(src);
        SALE_ONLY.forEach(data::remove);
        copy.typeData().merge(data);
        if (price > 0) {
            copy.typeData().putLong(ClaimData.PRICE, price);
        }
        store.add(copy);
        PlotBlocks.Job job = blocks ? PlotBlocks.transfer(admin, id, src.bounds(), box, rotation, false, null) : null;
        record(admin, new Step(level.dimension().location().toString(), id, null, copy.save(), src.id(), rotation, job));
        return Result.ok(id + " is pasted: a copy of " + src.id() + ", for sale" + (price > 0 ? " at " + price + " pieces" : "")
                + (blocks ? "; its blocks are being copied." : "."));
    }

    @SuppressWarnings("unchecked")
    private static <T> void setAny(Region r, com.mrgregles.bsp_region_guard.flag.Flag<T> flag, Object value) {
        r.setFlag(flag, (T) value);
    }

    private static boolean contains(Cuboid outer, Cuboid box) {
        return box.minX() >= outer.minX() && box.minY() >= outer.minY() && box.minZ() >= outer.minZ()
                && box.maxX() <= outer.maxX() && box.maxY() <= outer.maxY() && box.maxZ() <= outer.maxZ();
    }

    /**
     * The plot's teleport and respawn points move with it: by the same offset, or, when it was turned, to the same
     * height above the middle of the new box.
     */
    private static void moveLocations(Region r, Cuboid from, Cuboid to) {
        boolean turned = from.sizeX() != to.sizeX();
        for (LocationFlag f : new LocationFlag[]{Flags.TELEPORT, Flags.SPAWN}) {
            FlagLocation p = r.flag(f);
            if (p == null || !p.dimension().equals(r.dimension())) {
                continue;
            }
            double y = p.y() - from.minY() + to.minY();
            FlagLocation moved = turned
                    ? new FlagLocation(p.dimension(), to.minX() + to.sizeX() / 2.0, y, to.minZ() + to.sizeZ() / 2.0, p.yaw(), p.pitch())
                    : new FlagLocation(p.dimension(), p.x() - from.minX() + to.minX(), y, p.z() - from.minZ() + to.minZ(), p.yaw(), p.pitch());
            r.setFlag(f, moved);
        }
    }

    // ------------------------------------------------------------------ move

    /**
     * Moves an unlocked plot to {@code box} with {@code priority}, then locks it. A claim someone owns needs
     * {@code ownerConfirmed} (its builds stay where they are); without it the answer asks for that.
     */
    public static Result move(ServerPlayer admin, String id, Cuboid box, int priority, boolean ownerConfirmed) {
        return move(admin, id, box, priority, ownerConfirmed, false, 0);
    }

    /**
     * As {@link #move(ServerPlayer, String, Cuboid, int, boolean)}; with {@code blocks} its blocks, contents and
     * entities go along (turned {@code rotation} quarter turns), the old spot is rebuilt as natural ground, and both
     * areas are backed up first (D67 to D70).
     */
    public static Result move(ServerPlayer admin, String id, Cuboid box, int priority, boolean ownerConfirmed, boolean blocks, int rotation) {
        RegionStore store = RegionStore.get(admin.serverLevel());
        if (PlotBlocks.busy(id)) {
            return Result.fail("The blocks of " + id + " are still being worked on.");
        }
        Region r = store.get(id);
        String no = refuse(admin, r, id);
        if (no != null) {
            return Result.fail(no);
        }
        if (locked(r)) {
            return Result.fail(r.id() + " is locked. Unlock it first.");
        }
        if (!sameShape(r.bounds(), box)) {
            return Result.fail(r.id() + " keeps its size when moved: " + r.bounds().sizeX() + " x " + r.bounds().sizeZ() + ", " + r.bounds().sizeY() + " high.");
        }
        String overlap = overlapProblem(store, box, priority, r.id());
        if (overlap != null) {
            return Result.fail("Can't move it here: " + overlap);
        }
        if (!r.owners().isEmpty() && !ownerConfirmed) {
            return new Result(false, "Move " + r.id() + ", which someone owns? Their builds stay where they are.", true);
        }
        CompoundTag before = r.save();
        Cuboid was = r.bounds();
        moveLocations(r, r.bounds(), box);
        r.setBounds(box);
        r.setPriority(priority);
        Region parent = store.parentOf(r);
        if (parent != null && parent.bounds() != null && !contains(parent.bounds(), box)) {
            r.setParentId(null); // it has left its parent's ground
        }
        r.typeData().remove(UNLOCKED);
        store.touch(r);
        PlotBlocks.Job job = blocks ? PlotBlocks.transfer(admin, r.id(), was, box, rotation, true, null) : null;
        record(admin, new Step(admin.serverLevel().dimension().location().toString(), r.id(), before, r.save(), null, rotation, job));
        return Result.ok(r.id() + (blocks ? " is moved and locked; its blocks are on their way." : " is moved and locked."));
    }

    // ------------------------------------------------------------------ corners (D71 to D79)

    /**
     * Gives a region the corners clicked on the map: any region of the admin's own world but the world default, any
     * size. Plots, claims and open areas keep to the overlap rule (D59, D78); the change can be undone (D79).
     */
    public static Result setCorners(ServerPlayer admin, String id, Cuboid box) {
        if (!BspCore.isAdmin(admin)) {
            return Result.fail("Only BSP-Core admins can use the plot map.");
        }
        ServerLevel level = admin.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region r = store.get(id);
        if (r == null || r.isGlobal()) {
            return Result.fail(r == null ? "No region called '" + id + "' in this world. The plot map edits only the world you are in." : "The world default has no corners.");
        }
        if (PlotBlocks.busy(r.id())) {
            return Result.fail("The blocks of " + r.id() + " are still being worked on.");
        }
        String height = com.mrgregles.bsp_region_guard.command.RegionEdits.heightProblem(level, box);
        if (height != null) {
            return Result.fail(height);
        }
        if (isPlot(r)) {
            String overlap = overlapProblem(store, box, r.priority(), r.id());
            if (overlap != null) {
                return Result.fail("Can't put it here: " + overlap);
            }
        }
        CompoundTag before = r.save();
        r.setBounds(box);
        store.touch(r);
        record(admin, new Step(level.dimension().location().toString(), r.id(), before, r.save(), null, 0, null, Kind.CORNERS));
        return Result.ok(r.id() + " now covers " + box + ": " + box.sizeX() + " x " + box.sizeZ() + ", " + box.minY() + " to " + box.maxY() + ".");
    }

    /**
     * Where a corner clicked on the plot map lands with SNAP on: within two blocks of a side of one of {@code boxes},
     * just outside it (touching); otherwise where it was clicked. The region whose corners are being set
     * ({@code skip}) is never a target, so clicking its old corners again keeps them exact (B9).
     */
    public static int[] snapCorner(int x, int z, java.util.Collection<com.mrgregles.bsp_region_guard.network.PlotMapStatePacket.Plot> boxes, @Nullable String skip) {
        int sx = x, sz = z, bestX = 3, bestZ = 3;
        for (var p : boxes) {
            if (p.id().equals(skip)) {
                continue;
            }
            for (int edge : new int[]{p.minX() - 1, p.maxX() + 1}) {
                if (Math.abs(x - edge) < bestX) {
                    bestX = Math.abs(x - edge);
                    sx = edge;
                }
            }
            for (int edge : new int[]{p.minZ() - 1, p.maxZ() + 1}) {
                if (Math.abs(z - edge) < bestZ) {
                    bestZ = Math.abs(z - edge);
                    sz = edge;
                }
            }
        }
        return new int[]{sx, sz};
    }

    // ------------------------------------------------------------------ drawing and deleting on the map (D84, D87)

    /**
     * A region drawn on the map: {@code typeKey} protected, plot_for_sale (with a {@code price} above 0) or mine,
     * as the map's mode sets it. Plots keep to D59 and need a world where claims are allowed. Can be undone.
     */
    public static Result create(ServerPlayer admin, String newId, String typeKey, Cuboid box, int priority, long price) {
        if (!BspCore.isAdmin(admin)) {
            return Result.fail("Only BSP-Core admins can use the plot map.");
        }
        ServerLevel level = admin.serverLevel();
        RegionStore store = RegionStore.get(level);
        String id;
        try {
            id = Region.validateId(newId.trim());
        } catch (IllegalArgumentException e) {
            return Result.fail("Id: " + e.getMessage() + ".");
        }
        if (store.has(id) || id.equals(Region.GLOBAL_ID)) {
            return Result.fail("A region called " + id + " already exists here.");
        }
        String height = com.mrgregles.bsp_region_guard.command.RegionEdits.heightProblem(level, box);
        if (height != null) {
            return Result.fail(height);
        }
        RegionType type = RegionType.byKey(typeKey);
        if (type != RegionType.PROTECTED && type != RegionType.PLOT_FOR_SALE && type != RegionType.MINE) {
            return Result.fail("The map makes protected regions, plots for sale and mines.");
        }
        if (type == RegionType.PLOT_FOR_SALE) {
            if (!com.mrgregles.bsp_region_guard.protection.TotemRule.claimsAllowedIn(level)) {
                return Result.fail(com.mrgregles.bsp_region_guard.protection.TotemRule.plotsRefused(level));
            }
            if (price <= 0) {
                return Result.fail("A plot for sale needs a price above 0.");
            }
            String overlap = overlapProblem(store, box, priority, null);
            if (overlap != null) {
                return Result.fail("Can't put it here: " + overlap);
            }
        }
        Region r = new Region(id, level.dimension(), box, RegionType.PROTECTED);
        r.setPriority(priority);
        store.add(r);
        if (type == RegionType.PLOT_FOR_SALE) {
            ClaimData.set(r, ClaimData.PRICE, price);
            r.setType(RegionType.PLOT_FOR_SALE);
            store.touch(r);
        } else if (type == RegionType.MINE) {
            com.mrgregles.bsp_region_guard.mine.Mines.makeMine(store, r);
        }
        record(admin, new Step(level.dimension().location().toString(), id, null, r.save(), null, 0, null, Kind.CREATE));
        return Result.ok(id + " made: " + type.key().replace('_', ' ') + ", " + box.sizeX() + " x " + box.sizeZ() + ", y " + box.minY() + " to " + box.maxY()
                + (type == RegionType.PLOT_FOR_SALE ? ", for sale at " + Prices.text(Prices.tier(r), price) : "") + ".");
    }

    /** Deletes a region from the map; UNDO brings it back with everything it had (D87). */
    public static Result delete(ServerPlayer admin, String id) {
        if (!BspCore.isAdmin(admin)) {
            return Result.fail("Only BSP-Core admins can use the plot map.");
        }
        ServerLevel level = admin.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region r = store.get(id);
        if (r == null || r.isGlobal()) {
            return Result.fail(r == null ? "No region called '" + id + "' in this world." : "The world default can't be deleted.");
        }
        if (PlotBlocks.busy(r.id())) {
            return Result.fail("The blocks of " + r.id() + " are still being worked on.");
        }
        CompoundTag before = r.save();
        var removed = com.mrgregles.bsp_region_guard.command.RegionEdits.remove(store, r);
        if (!removed.ok()) {
            return Result.fail(removed.message());
        }
        record(admin, new Step(level.dimension().location().toString(), r.id(), before, before, null, 0, null, Kind.DELETE));
        return Result.ok(r.id() + " deleted. UNDO brings it back.");
    }

    // ------------------------------------------------------------------ backups (D69)

    /** Saves the blocks of a plot as they are now. */
    public static Result backup(ServerPlayer admin, String id) {
        Region r = RegionStore.get(admin.serverLevel()).get(id);
        String no = refuse(admin, r, id);
        if (no != null) {
            return Result.fail(no);
        }
        if (PlotBlocks.busy(id)) {
            return Result.fail("The blocks of " + id + " are still being worked on.");
        }
        PlotBlocks.backup(admin, r.id(), r.bounds());
        return Result.ok("Backing up the blocks of " + r.id() + "...");
    }

    /** Puts a backup file's blocks back where they were taken (in the world the admin stands in). */
    public static Result restore(ServerPlayer admin, String file) {
        if (!BspCore.isAdmin(admin)) {
            return Result.fail("Only BSP-Core admins can use the plot map.");
        }
        if (!file.matches("[A-Za-z0-9_.-]+\\.nbt")) {
            return Result.fail("No backup called " + file + ".");
        }
        String[] error = {null};
        PlotBlocks.Job job = PlotBlocks.restore(admin, file, null, m -> error[0] = m);
        return job == null ? Result.fail(error[0]) : Result.ok("Restoring " + file + "...");
    }

    // ------------------------------------------------------------------ undo and redo (D65)

    private static void record(ServerPlayer admin, Step step) {
        Deque<Step> undo = UNDO.computeIfAbsent(admin.getUUID(), k -> new ArrayDeque<>());
        undo.push(step);
        while (undo.size() > HISTORY) {
            undo.removeLast();
        }
        REDO.remove(admin.getUUID()); // a new action ends the redo trail
    }

    public static int undoCount(ServerPlayer admin) {
        Deque<Step> d = UNDO.get(admin.getUUID());
        return d == null ? 0 : d.size();
    }

    public static int redoCount(ServerPlayer admin) {
        Deque<Step> d = REDO.get(admin.getUUID());
        return d == null ? 0 : d.size();
    }

    /** Takes back the admin's last paste or move in this world. */
    public static Result undo(ServerPlayer admin) {
        return replay(admin, UNDO, REDO, true);
    }

    /** Does again the last action the admin took back. */
    public static Result redo(ServerPlayer admin) {
        return replay(admin, REDO, UNDO, false);
    }

    private static Result replay(ServerPlayer admin, Map<UUID, Deque<Step>> from, Map<UUID, Deque<Step>> to, boolean undo) {
        if (!BspCore.isAdmin(admin)) {
            return Result.fail("Only BSP-Core admins can use the plot map.");
        }
        Deque<Step> steps = from.get(admin.getUUID());
        if (steps == null || steps.isEmpty()) {
            return Result.fail(undo ? "Nothing to undo." : "Nothing to redo.");
        }
        Step s = steps.peek();
        ServerLevel level = admin.serverLevel();
        if (PlotBlocks.busy(s.id())) {
            return Result.fail("The blocks of " + s.id() + " are still being worked on; try again when they are done.");
        }
        if (!level.dimension().location().toString().equals(s.dimension())) {
            return Result.fail("That was done in " + s.dimension() + ". Go there to " + (undo ? "undo" : "redo") + " it.");
        }
        RegionStore store = RegionStore.get(level);
        Region now = store.get(s.id());
        String done;
        if (s.kind() == Kind.CREATE || s.kind() == Kind.DELETE) {
            boolean add = s.kind() == Kind.CREATE ? !undo : undo; // undoing a create removes, undoing a delete adds back
            if (add) {
                if (now != null) {
                    return Result.fail("A region called " + s.id() + " exists again, so it can't come back.");
                }
                Region back = Region.load(s.kind() == Kind.CREATE ? s.after() : s.before());
                if (isPlot(back) && back.hasCorners()) {
                    String overlap = overlapProblem(store, back.bounds(), back.priority(), null);
                    if (overlap != null) {
                        return Result.fail("Can't bring it back: " + overlap);
                    }
                }
                store.add(back);
            } else if (now != null) {
                if (isPlot(now) && !now.owners().isEmpty()) {
                    return Result.fail(s.id() + " has been bought since, so it stays. Give it up from the claim first.");
                }
                store.remove(s.id());
            }
            done = (undo ? "Undone" : "Redone") + (s.kind() == Kind.CREATE ? ": making " : ": deleting ") + s.id() + ".";
        } else if (s.before() == null) { // a paste
            if (undo) {
                if (now == null) {
                    done = s.id() + " is already gone.";
                } else if (now.type() != RegionType.PLOT_FOR_SALE || !now.owners().isEmpty()) {
                    return Result.fail(s.id() + " has been bought since, so it stays. Give it up from the claim first.");
                } else {
                    store.remove(s.id());
                    done = "Undone: the paste of " + s.id() + ".";
                    if (s.blocks() != null && !s.blocks().toFile.isEmpty()) {
                        PlotBlocks.restore(admin, s.blocks().toFile, null, m -> { });
                        done += " Its spot is being put back as it was.";
                    }
                }
            } else {
                if (now != null) {
                    return Result.fail("A region called " + s.id() + " exists again; it can't be pasted back.");
                }
                Region back = Region.load(s.after());
                String overlap = overlapProblem(store, back.bounds(), back.priority(), null);
                if (overlap != null) {
                    return Result.fail("Can't paste it back: " + overlap);
                }
                store.add(back);
                done = "Redone: the paste of " + s.id() + ".";
                Region source = s.source() == null ? null : store.get(s.source());
                if (s.blocks() != null && source != null && source.bounds() != null) {
                    PlotBlocks.Job job = PlotBlocks.transfer(admin, s.id(), source.bounds(), back.bounds(), s.rotation(), false, null);
                    s = new Step(s.dimension(), s.id(), s.before(), s.after(), s.source(), s.rotation(), job, s.kind());
                    done += " Its blocks are being copied again.";
                }
            }
        } else { // a move: put back (or forward) its place, priority, parent and points
            if (now == null) {
                steps.pop();
                return Result.fail(s.id() + " no longer exists.");
            }
            Region target = Region.load(undo ? s.before() : s.after());
            if (!target.hasCorners() && now.type() != RegionType.PROTECTED) {
                return Result.fail(s.id() + " is a " + now.type().key().replace('_', ' ') + " region now, which needs corners. Make it protected first to undo this.");
            }
            String overlap = overlapProblem(store, target.bounds(), target.priority(), now.id());
            if (overlap != null) {
                return Result.fail("Can't " + (undo ? "undo" : "redo") + " the move: " + overlap);
            }
            now.setBounds(target.bounds());
            now.setPriority(target.priority());
            now.setParentId(target.parentId());
            now.setFlag(Flags.TELEPORT, target.flag(Flags.TELEPORT));
            now.setFlag(Flags.SPAWN, target.flag(Flags.SPAWN));
            Cuboid leaving = now.bounds();
            store.touch(now);
            done = (undo ? "Undone" : "Redone") + (s.kind() == Kind.CORNERS ? ": the corners of " : ": the move of ") + s.id() + ".";
            if (s.blocks() != null) {
                if (undo) { // both spots back as they were before the move, from that move's backups
                    PlotBlocks.Job moved = s.blocks();
                    if (!moved.toFile.isEmpty()) {
                        PlotBlocks.restore(admin, moved.toFile, () -> {
                            if (!moved.fromFile.isEmpty()) {
                                PlotBlocks.restore(admin, moved.fromFile, null, m -> { });
                            }
                        }, m -> { });
                    }
                    done += " Its blocks are being put back.";
                } else {
                    PlotBlocks.Job job = PlotBlocks.transfer(admin, s.id(), Region.load(s.before()).bounds(), target.bounds(), s.rotation(), true, null);
                    s = new Step(s.dimension(), s.id(), s.before(), s.after(), s.source(), s.rotation(), job, s.kind());
                    done += " Its blocks are moving again.";
                }
            }
        }
        steps.pop();
        to.computeIfAbsent(admin.getUUID(), k -> new ArrayDeque<>()).push(s);
        return Result.ok(done);
    }

    public static void forget(UUID admin) {
        UNDO.remove(admin);
        REDO.remove(admin);
    }
}
