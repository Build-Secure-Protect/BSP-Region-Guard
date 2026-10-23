package com.mrgregles.bsp_region_guard.command;

import com.mrgregles.bsp_region_guard.api.Border;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent;
import com.mrgregles.bsp_region_guard.claim.ClaimData;
import com.mrgregles.bsp_region_guard.claim.ClaimFlags;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagContext;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.mine.MineData;
import com.mrgregles.bsp_region_guard.mine.Mines;
import com.mrgregles.bsp_region_guard.protection.TotemRule;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import com.mrgregles.bsp_region_guard.selection.Selection;
import com.mrgregles.bsp_region_guard.selection.Selections;
import com.mrgregles.bsp_region_guard.trigger.Trigger;
import com.mrgregles.bsp_region_guard.trigger.Triggers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The region edits that both the {@code /rg} commands and the admin screen make. Each one checks its own rules,
 * changes the region and answers with a plain sentence, so the two front ends only find the region and show the
 * answer, and a rule lives in one place.
 */
public final class RegionEdits {
    public static final String NO_SELECTION = "Select two corners first (wand, or /rg pos1 and /rg pos2).";

    private RegionEdits() {
    }

    /** What happened: {@code ok} false means nothing changed; {@code message} is a sentence for the player. */
    public record Result(boolean ok, String message) {
        static Result ok(String message) {
            return new Result(true, message);
        }

        static Result fail(String message) {
            return new Result(false, message);
        }
    }

    /** The player's wand selection as a box, when both corners are set in the world they stand in; otherwise null. */
    @Nullable
    public static Cuboid selectionIn(ServerPlayer player) {
        Selection sel = Selections.of(player);
        Cuboid box = sel.cuboid();
        return box != null && player.serverLevel().dimension().equals(sel.dimension()) ? box : null;
    }

    // ------------------------------------------------------------------ create, change, remove

    /** Makes a region from {@code box}; without one it has no corners yet and applies nowhere until it gets some (D73, D74). */
    public static Result create(ServerLevel level, String id, @Nullable Cuboid box, int priority) {
        try {
            Region r = new Region(Region.validateId(id), level.dimension(), box == null ? Region.NO_CORNERS : box, RegionType.PROTECTED);
            r.setPriority(priority);
            RegionStore.get(level).add(r);
            if (box == null) {
                return Result.ok("Region " + r.id() + " created without corners; it protects nothing yet. Set them on the plot map (SET CORNERS), "
                        + "with the wand and /rg redefine " + r.id() + ", or by typing them: /rg redefine " + r.id() + " <x1 y1 z1> <x2 y2 z2>.");
            }
            return Result.ok("Region " + r.id() + " created: " + box + ", " + box.volume() + " blocks, priority " + priority + ".");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** Null when {@code box} lies within the world's height; otherwise why not. */
    @Nullable
    public static String heightProblem(ServerLevel level, Cuboid box) {
        int bottom = level.getMinBuildHeight(), top = level.getMaxBuildHeight() - 1;
        return box.minY() < bottom || box.maxY() > top ? "Heights in " + level.dimension().location() + " run from " + bottom + " to " + top + "." : null;
    }

    public static Result redefine(ServerLevel level, RegionStore store, Region r, @Nullable Cuboid box) {
        if (r.isGlobal()) {
            return Result.fail("The world default has no bounds.");
        }
        if (box == null) {
            return Result.fail(NO_SELECTION);
        }
        String height = heightProblem(level, box);
        if (height != null) {
            return Result.fail(height);
        }
        r.setBounds(box);
        store.touch(r);
        return Result.ok("Region " + r.id() + " now covers " + box + ": " + box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ() + ".");
    }

    /** The size fields of the screen: six whole numbers, "x1 y1 z1 x2 y2 z2", corners in any order. */
    public static Result resize(ServerLevel level, RegionStore store, Region r, String corners) {
        String[] parts = corners.trim().split("[\\s,]+");
        if (parts.length != 6) {
            return Result.fail("Give both corners as six numbers: x1 y1 z1 x2 y2 z2.");
        }
        int[] v = new int[6];
        try {
            for (int i = 0; i < 6; i++) {
                v[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return Result.fail("Corners are whole numbers: x1 y1 z1 x2 y2 z2.");
        }
        return redefine(level, store, r, Cuboid.of(new net.minecraft.core.BlockPos(v[0], v[1], v[2]), new net.minecraft.core.BlockPos(v[3], v[4], v[5])));
    }

    /** The box stretched from the world's lowest block to its highest buildable block (D77). */
    public static Cuboid verticalFill(ServerLevel level, Cuboid box) {
        return new Cuboid(box.minX(), level.getMinBuildHeight(), box.minZ(), box.maxX(), level.getMaxBuildHeight() - 1, box.maxZ());
    }

    /** Stretches a region from the bottom to the top of its world. */
    public static Result verticalFill(ServerLevel level, RegionStore store, Region r) {
        if (r.isGlobal()) {
            return Result.fail("The world default already covers the whole world.");
        }
        if (!r.hasCorners()) {
            return Result.fail(r.id() + " has no corners yet. Set them first; corners clicked on the plot map span the whole height anyway.");
        }
        return redefine(level, store, r, verticalFill(level, r.bounds()));
    }

    /** Stretches the player's wand selection from the bottom to the top of the world they stand in. */
    public static Result verticalFillSelection(ServerPlayer player) {
        Cuboid box = selectionIn(player);
        if (box == null) {
            return Result.fail(NO_SELECTION);
        }
        Cuboid tall = verticalFill(player.serverLevel(), box);
        Selections.of(player).set(player.serverLevel().dimension(), tall);
        Selections.sync(player);
        return Result.ok("Selection stretched to the whole height: " + tall + ".");
    }

    /** Null when the region has corners; otherwise a refusal for {@code what} (D73). */
    @Nullable
    private static Result needsCorners(Region r, String what) {
        return r.hasCorners() ? null : Result.fail(r.id() + " has no corners yet, so it can't " + what + ". Set its corners first (SET CORNERS on the INFO tab, or /rg redefine).");
    }

    public static Result remove(RegionStore store, Region r) {
        try {
            store.remove(r.id());
            return Result.ok("Region " + r.id() + " removed.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    public static Result priority(RegionStore store, Region r, int priority) {
        if (r.isGlobal()) {
            return Result.fail("The world default always has the lowest priority.");
        }
        r.setPriority(priority);
        store.touch(r);
        return Result.ok("Region " + r.id() + " priority is now " + priority + ".");
    }

    /** {@code parentId} empty, {@code none} or {@code -} removes the parent. */
    public static Result parent(RegionStore store, Region r, String parentId) {
        String p = parentId.trim();
        if (p.isEmpty() || p.equalsIgnoreCase("none") || p.equals("-")) {
            r.setParentId(null);
            store.touch(r);
            return Result.ok("Region " + r.id() + " has no parent now.");
        }
        Region parent = store.get(p);
        if (parent == null) {
            return Result.fail("No region named '" + p + "' in this world.");
        }
        if (parent == r || store.isAncestor(r, parent)) {
            return Result.fail("That would make a loop of parents.");
        }
        r.setParentId(parent.id());
        store.touch(r);
        return Result.ok("Region " + r.id() + " now inherits from " + parent.id() + ".");
    }

    public static Result type(ServerLevel level, RegionStore store, Region r, String typeKey) {
        RegionType type = RegionType.byKey(typeKey.trim().toLowerCase(Locale.ROOT));
        if (type == null || type == RegionType.GLOBAL || r.isGlobal()) {
            return Result.fail("Types: protected, plot_for_sale, open_for_buying, claim, mine.");
        }
        if (type != RegionType.PROTECTED && !r.hasCorners()) {
            return needsCorners(r, "be a " + type.key().replace('_', ' ') + " region");
        }
        if ((type == RegionType.CLAIM || type == RegionType.PLOT_FOR_SALE || type == RegionType.OPEN_FOR_BUYING) && !TotemRule.claimsAllowedIn(level)) {
            return Result.fail(TotemRule.plotsRefused(level));
        }
        r.setType(type);
        store.touch(r);
        return Result.ok("Region " + r.id() + " is now a " + type.key().replace('_', ' ') + " region.");
    }

    /** An empty name goes back to the id. */
    public static Result rename(RegionStore store, Region r, String name) {
        r.setName(name.trim().isEmpty() ? r.id() : name.trim());
        store.touch(r);
        return Result.ok("Region " + r.id() + " is now called \"" + r.name() + "\".");
    }

    /** Adds or removes an owner or member; true when something changed. Saves the region. */
    public static boolean person(RegionStore store, Region r, UUID who, boolean owner, boolean add) {
        boolean changed = owner ? (add ? r.addOwner(who) : r.removeOwner(who)) : (add ? r.addMember(who) : r.removeMember(who));
        store.touch(r);
        return changed;
    }

    public static Result rank(RegionStore store, Region r, String rank, boolean add) {
        boolean changed = add ? r.addRank(rank) : r.removeRank(rank);
        store.touch(r);
        return Result.ok(changed ? "Rank " + rank.toLowerCase(Locale.ROOT) + (add ? " may now enter " : " removed from ") + r.id() + "." : "Nothing changed.");
    }

    /** Sets a flag from typed text; null or blank clears it. */
    public static Result flag(RegionStore store, Region r, Flag<?> flag, @Nullable String value, FlagContext context) {
        if (value == null || value.isBlank()) {
            r.setFlag(flag, null);
            store.touch(r);
            return Result.ok(r.id() + " " + flag.name() + " cleared.");
        }
        try {
            setParsed(r, flag, value.trim(), context);
            store.touch(r);
            return Result.ok(r.id() + " " + flag.name() + " set to " + formatAny(flag, r.flag(flag)) + ".");
        } catch (IllegalArgumentException e) {
            return Result.fail(flag.name() + ": " + e.getMessage());
        }
    }

    private static <T> void setParsed(Region r, Flag<T> flag, String value, FlagContext context) {
        r.setFlag(flag, flag.parse(value, context));
    }

    @SuppressWarnings("unchecked")
    static <T> String formatAny(Flag<T> flag, Object value) {
        return flag.format((T) value);
    }

    /** Makes a region's bounds the player's wand selection. */
    public static Result selectBounds(ServerPlayer player, Region r) {
        if (r.bounds() == null) {
            return Result.fail("The world default has no corners to select.");
        }
        if (!r.hasCorners()) {
            return needsCorners(r, "be selected");
        }
        Selections.of(player).set(player.serverLevel().dimension(), r.bounds());
        Selections.sync(player);
        return Result.ok("Selection set to " + r.id() + ": " + r.bounds() + ".");
    }

    /** To the region's teleport point, or onto the surface at its middle. */
    public static Result teleport(ServerPlayer p, RegionStore store, Region r) {
        if (!r.hasCorners()) {
            return needsCorners(r, "be teleported to");
        }
        FlagLocation target = r.flag(Flags.TELEPORT);
        if (target != null) {
            ServerLevel dest = p.server.getLevel(target.dimension());
            if (dest == null) {
                return Result.fail("The teleport point is in a world that does not exist: " + target.dimension().location());
            }
            p.teleportTo(dest, target.x(), target.y(), target.z(), target.yaw(), target.pitch());
        } else if (r.bounds() != null) {
            ServerLevel level = p.serverLevel();
            Cuboid b = r.bounds();
            double x = (b.minX() + b.maxX() + 1) / 2.0, z = (b.minZ() + b.maxZ() + 1) / 2.0;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            y = Math.max(b.minY(), Math.min(b.maxY() + 1, y));
            p.teleportTo(level, x, y, z, p.getYRot(), p.getXRot());
        } else {
            return Result.fail("The world default has nowhere to teleport to; set its teleport flag.");
        }
        String message = RegionQuery.effective(store, r, Flags.TELEPORT_MESSAGE);
        return Result.ok(message != null ? message : "Teleported to " + r.id() + ".");
    }

    // ------------------------------------------------------------------ claims

    @Nullable
    private static String claimProblem(ServerLevel level, Region r) {
        if (r.isGlobal()) {
            return "The world default cannot be sold or opened for buying.";
        }
        if (!TotemRule.claimsAllowedIn(level)) {
            return TotemRule.plotsRefused(level);
        }
        if (!r.hasCorners()) {
            return r.id() + " has no corners yet, so it can't be sold or opened for buying. Set its corners first.";
        }
        if (r.type() == RegionType.CLAIM) {
            return r.id() + " is already owned; the owner can abandon it, or an admin can with /claim abandon.";
        }
        return null;
    }

    /** Puts a region up for sale at {@code price} pieces; 0 takes a plot off sale. */
    public static Result sale(ServerLevel level, RegionStore store, Region r, long price) {
        String problem = claimProblem(level, r);
        if (problem != null) {
            return Result.fail(problem);
        }
        ClaimData.set(r, ClaimData.PRICE, price);
        if (price > 0) {
            r.setType(RegionType.PLOT_FOR_SALE);
        } else if (r.type() == RegionType.PLOT_FOR_SALE) {
            r.setType(RegionType.PROTECTED);
        }
        store.touch(r);
        return Result.ok(price > 0 ? r.id() + " is for sale at " + com.mrgregles.bsp_region_guard.claim.Prices.text(com.mrgregles.bsp_region_guard.claim.Prices.tier(r), price) + "." : r.id() + " is not for sale.");
    }

    /** Opens a region for players to outline their own plots at {@code cost} pieces per block; 0 closes it. */
    public static Result open(ServerLevel level, RegionStore store, Region r, long cost) {
        String problem = claimProblem(level, r);
        if (problem != null) {
            return Result.fail(problem);
        }
        ClaimData.set(r, ClaimData.COST_PER_BLOCK, cost);
        if (cost > 0) {
            r.setType(RegionType.OPEN_FOR_BUYING);
        } else if (r.type() == RegionType.OPEN_FOR_BUYING) {
            r.setType(RegionType.PROTECTED);
        }
        store.touch(r);
        return Result.ok(cost > 0 ? r.id() + " is open for buying at " + com.mrgregles.bsp_region_guard.claim.Prices.text(com.mrgregles.bsp_region_guard.claim.Prices.tier(r), cost) + " per block." : r.id() + " is closed for buying.");
    }

    /** An open area's own plot size limits; 0 leaves a limit to the config. */
    public static Result plotSizes(RegionStore store, Region r, int min, int max) {
        ClaimData.set(r, ClaimData.MIN_SIZE, min);
        ClaimData.set(r, ClaimData.MAX_SIZE, max);
        store.touch(r);
        return Result.ok(min > 0 || max > 0 ? "Plots in " + r.id() + " are " + min + " to " + max + " blocks a side." : "Plot sizes in " + r.id() + " follow the config.");
    }

    /** The coin tier a plot or open area prices in (D95); {@code default} follows the config's {@code claims.coinTier}. */
    public static Result coinTier(RegionStore store, Region r, String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        List<String> tiers = BspCore.coinTiers();
        if (r.isGlobal()) {
            return Result.fail("The world default has no prices.");
        }
        if (v.isEmpty() || v.equals("default")) {
            r.typeData().remove(com.mrgregles.bsp_region_guard.claim.Prices.COIN_TIER);
        } else if (!tiers.contains(v)) {
            return Result.fail(tiers.isEmpty() ? "This BSP Core has no Shatter Coins." : "Coin tiers: " + String.join(", ", tiers) + ", or default.");
        } else {
            r.typeData().putString(com.mrgregles.bsp_region_guard.claim.Prices.COIN_TIER, v);
        }
        store.touch(r);
        String tier = com.mrgregles.bsp_region_guard.claim.Prices.tier(r);
        return Result.ok(tier == null ? r.id() + " will price in " + (v.isEmpty() || v.equals("default") ? "the config's tier" : v) + " coins once claims.currency is coins."
                : r.id() + " prices in " + tier + " coins.");
    }

    /** A plot's whole look from the PLOT LOOK window (D105 to D114), {@code arrow;#FFD23A;1.0;1.0;3}. */
    public static Result plotLook(RegionStore store, Region r, String line) {
        if (r.isGlobal()) {
            return Result.fail("The world default has no look; set it on a plot for sale or an open area.");
        }
        com.mrgregles.bsp_region_guard.claim.PlotLook look = com.mrgregles.bsp_region_guard.claim.PlotLook.parse(line);
        if (look == null) {
            return Result.fail("That look could not be read.");
        }
        look.save(r);
        store.touch(r);
        return Result.ok(r.id() + " now shows " + lookText(look) + ".");
    }

    /** One part of a plot's look by command: marker, colour, size, namesize or height. */
    public static Result plotLookPart(RegionStore store, Region r, String part, String value) {
        if (r.isGlobal()) {
            return Result.fail("The world default has no look; set it on a plot for sale or an open area.");
        }
        var look = com.mrgregles.bsp_region_guard.claim.PlotLook.of(r);
        String v = value.trim().toLowerCase(Locale.ROOT);
        try {
            look = switch (part.toLowerCase(Locale.ROOT)) {
                case "marker" -> {
                    if (!com.mrgregles.bsp_region_guard.claim.PlotLook.MARKERS.contains(v)) {
                        throw new IllegalArgumentException("Markers: " + String.join(", ", com.mrgregles.bsp_region_guard.claim.PlotLook.MARKERS) + ".");
                    }
                    yield look.withMarker(v);
                }
                case "colour", "color" -> {
                    Integer c = com.mrgregles.bsp_region_guard.claim.PlotLook.parseColour(v);
                    if (c == null) {
                        throw new IllegalArgumentException("A colour is six hex digits, for example #FFD23A.");
                    }
                    yield look.withColour(c);
                }
                case "size" -> look.withSize(Float.parseFloat(v));
                case "namesize" -> look.withText(Float.parseFloat(v));
                case "height" -> look.withHeight(Integer.parseInt(v));
                default -> throw new IllegalArgumentException("Parts: marker, colour, size, namesize, height.");
            };
        } catch (NumberFormatException e) {
            return Result.fail("Give a number, for example 1.5 for a size or 4 for a height.");
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        look.save(r);
        store.touch(r);
        return Result.ok(r.id() + " now shows " + lookText(look) + ".");
    }

    public static String lookText(com.mrgregles.bsp_region_guard.claim.PlotLook look) {
        return (look.marker().equals("outline") ? "just the outline" : "the " + look.marker()) + " in " + look.hex() + ", size " + look.size()
                + ", name size " + look.text() + ", " + look.height() + " block" + (look.height() == 1 ? "" : "s") + " above the ground";
    }

    /** The most plots one player may own inside this region (D100); empty or {@code none} removes the limit. */
    public static Result plotLimit(RegionStore store, Region r, String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("none")) {
            r.typeData().remove(ClaimData.PLOT_LIMIT);
            store.touch(r);
            return Result.ok(r.isGlobal() ? "Plots per player in this world follow the config and rank limits again."
                    : "No limit on plots per player inside " + r.id() + ".");
        }
        int n;
        try {
            n = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return Result.fail("Give a number of plots (0 or more), or none.");
        }
        if (n < 0 || n > 1000) {
            return Result.fail("Give a number of plots from 0 to 1000, or none.");
        }
        r.typeData().putInt(ClaimData.PLOT_LIMIT, n);
        store.touch(r);
        return Result.ok(r.isGlobal() ? "A player may own " + n + " plot" + (n == 1 ? "" : "s") + " in this world (rank limits can raise it)."
                : "A player may own " + n + " plot" + (n == 1 ? "" : "s") + " inside " + r.id() + ".");
    }

    /** The flags buyers of this plot (or of plots in this area) may set; {@code default} means the config's list. */
    public static Result plotFlags(RegionStore store, Region r, String flags) {
        List<String> unknown = ClaimFlags.setAllowed(r, flags);
        store.touch(r);
        String now = String.join(", ", ClaimFlags.allowedFor(store, r));
        return Result.ok("Buyers of " + r.id() + " may set: " + now + (unknown.isEmpty() ? "" : " (not flags, ignored: " + String.join(", ", unknown) + ")"));
    }

    // ------------------------------------------------------------------ triggers

    public static Result addTrigger(ServerPlayer player, RegionStore store, Region r, String causeName, String destination) {
        RegionTriggerEvent.Cause cause;
        try {
            cause = RegionTriggerEvent.Cause.valueOf(causeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Result.fail("Causes: enter, exit, timer, shutdown.");
        }
        if (cause == RegionTriggerEvent.Cause.EDGE) {
            return Result.fail("Edge travel is set with /rg border link, not as a trigger. Causes: enter, exit, timer, shutdown.");
        }
        try {
            Trigger trigger = Triggers.parse(player, cause, destination);
            List<Trigger> triggers = Trigger.of(r.typeData());
            triggers.add(trigger);
            Trigger.store(r.typeData(), triggers);
            store.touch(r);
            return Result.ok("Trigger added to " + r.id() + ": " + trigger.describe() + (trigger.server().isEmpty() ? "" : " (cross-server: BSP Teleport carries it out)"));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** {@code index} counts from 1, as {@code /rg trigger list} shows. */
    public static Result removeTrigger(RegionStore store, Region r, int index) {
        List<Trigger> triggers = Trigger.of(r.typeData());
        if (index < 1 || index > triggers.size()) {
            return Result.fail(r.id() + " has " + triggers.size() + " trigger" + (triggers.size() == 1 ? "" : "s") + "; there is no number " + index + ".");
        }
        triggers.remove(index - 1);
        Trigger.store(r.typeData(), triggers);
        store.touch(r);
        return Result.ok("Trigger " + index + " removed from " + r.id() + ".");
    }

    // ------------------------------------------------------------------ mines

    @Nullable
    private static Result notAMine(Region r) {
        return r.type() == RegionType.MINE ? null : Result.fail(r.id() + " is not a mine. Use /rg mine create " + r.id() + " first.");
    }

    public static Result makeMine(RegionStore store, Region r) {
        if (r.isGlobal()) {
            return Result.fail("The world default cannot be a mine.");
        }
        if (!r.hasCorners()) {
            return needsCorners(r, "be a mine");
        }
        Mines.makeMine(store, r);
        return Result.ok(r.id() + " is now a mine: breaking is allowed inside, placing is not. Add blocks with /rg mine add " + r.id() + " <block> <weight>.");
    }

    /** Sets a block's weight in the fill table; 0 removes it. */
    public static Result mineBlock(RegionStore store, Region r, @Nullable ResourceLocation block, int weight) {
        Result notMine = notAMine(r);
        if (notMine != null) {
            return notMine;
        }
        if (block == null || weight > 0 && !ForgeRegistries.BLOCKS.containsKey(block)) {
            return Result.fail("No block called " + block + ".");
        }
        MineData.put(r, block, weight);
        store.touch(r);
        return Result.ok(weight > 0 ? block + " added to " + r.id() + " with weight " + weight + "." : block + " removed from " + r.id() + ".");
    }

    /** "coal_ore" means "minecraft:coal_ore"; null when the text is not a block id. */
    @Nullable
    public static ResourceLocation blockId(String text) {
        String t = text.trim();
        return ResourceLocation.tryParse(t.contains(":") ? t : "minecraft:" + t);
    }

    public static Result mineInterval(RegionStore store, Region r, int seconds) {
        Result notMine = notAMine(r);
        if (notMine != null) {
            return notMine;
        }
        Mines.schedule(store, r, seconds);
        return Result.ok(seconds > 0 ? r.id() + " resets every " + seconds + " seconds; the first reset is in " + seconds + " seconds." : r.id() + " no longer resets on its own.");
    }

    /** Where players inside are moved on a reset: where the player stands, or (clear) just above the mine. */
    public static Result mineSpawn(ServerPlayer p, RegionStore store, Region r, boolean clear) {
        Result notMine = notAMine(r);
        if (notMine != null) {
            return notMine;
        }
        MineData.setSpawn(r, clear ? null : FlagLocation.of(p.serverLevel(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
        store.touch(r);
        return Result.ok(clear ? "Reset spawn of " + r.id() + " cleared." : "Players in " + r.id() + " are moved here when it resets.");
    }

    public static Result mineShell(ServerLevel level, RegionStore store, Region r, int radius) {
        Result notMine = notAMine(r);
        if (notMine != null) {
            return notMine;
        }
        int clamped = Math.max(1, Math.min(64, radius));
        Region shell = Mines.makeShell(level, store, r, clamped);
        return Result.ok(shell.id() + " surrounds " + r.id() + " by " + clamped + " blocks: only the mine can be dug there, nothing can be placed.");
    }

    public static Result mineReset(ServerLevel level, RegionStore store, Region r) {
        Result notMine = notAMine(r);
        return notMine != null ? notMine : Result.ok(Mines.reset(level, store, r));
    }

    // ------------------------------------------------------------------ the world border (D36 to D39)

    /** Sets this world's border: a square {@code width} blocks across around {@code (centerX, centerZ)}. */
    public static Result border(ServerLevel level, int width, double centerX, double centerZ) {
        if (width < 16) {
            return Result.fail("A world border must be at least 16 blocks across.");
        }
        Border b = new Border(centerX, centerZ, width);
        com.mrgregles.bsp_region_guard.protection.WorldBorders.set(level, b);
        return Result.ok("World border of " + level.dimension().location() + ": " + b + "."
                + (level.dimension() == net.minecraft.world.level.Level.OVERWORLD ? " The wall shows in game." : ""));
    }

    public static Result borderOff(ServerLevel level) {
        if (com.mrgregles.bsp_region_guard.protection.WorldBorders.of(level) == null) {
            return Result.fail(level.dimension().location() + " has no world border.");
        }
        com.mrgregles.bsp_region_guard.protection.WorldBorders.set(level, null);
        return Result.ok("World border of " + level.dimension().location() + " removed.");
    }

    /** Plots in this world while it counts as a PvP world (D92): allow, deny, or default (the config decides). */
    public static Result plotsInPvp(ServerLevel level, String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (!v.equals("allow") && !v.equals("deny") && !v.equals("default")) {
            return Result.fail("Use allow, deny or default.");
        }
        RegionStore store = RegionStore.get(level);
        Region global = store.global();
        if (v.equals("default")) {
            global.typeData().remove(TotemRule.PLOTS_IN_PVP);
        } else {
            global.typeData().putString(TotemRule.PLOTS_IN_PVP, v);
        }
        store.touch(global);
        String now = switch (TotemRule.plotRule(level)) {
            case NOT_PVP -> "Plots are allowed: " + level.dimension().location() + " is not a PvP world.";
            case PVP_DENIED -> "Plots are allowed: pvp is denied on the world default.";
            case WORLD_ALLOWS, CONFIG_ALLOWS -> "Plots are allowed in " + level.dimension().location() + ".";
            default -> "Plots are refused in " + level.dimension().location() + " (a PvP world).";
        };
        return Result.ok(now);
    }

    // ------------------------------------------------------------------ edge links (D40 to D42)

    /** Links an edge of this world to an edge of a world on another server; two-way. */
    public static Result edgeLink(ServerLevel level, String side, String server, String world, String theirSide) {
        var mine = com.mrgregles.bsp_region_guard.protection.EdgeLinks.Side.parse(side);
        var theirs = com.mrgregles.bsp_region_guard.protection.EdgeLinks.Side.parse(theirSide);
        if (mine == null || theirs == null) {
            return Result.fail("Sides: north, east, south, west.");
        }
        String dim = world.contains(":") ? world : "minecraft:" + world;
        var out = com.mrgregles.bsp_region_guard.protection.EdgeLinks.link(level, mine, new com.mrgregles.bsp_region_guard.protection.EdgeLinks.Place(server, dim, theirs));
        return new Result(out.ok(), out.message());
    }

    public static Result edgeUnlink(ServerLevel level, String side) {
        var mine = com.mrgregles.bsp_region_guard.protection.EdgeLinks.Side.parse(side);
        if (mine == null) {
            return Result.fail("Sides: north, east, south, west.");
        }
        var out = com.mrgregles.bsp_region_guard.protection.EdgeLinks.unlink(level, mine);
        return new Result(out.ok(), out.message());
    }
}
