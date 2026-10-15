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

    public static Result create(ServerLevel level, String id, @Nullable Cuboid box, int priority) {
        if (box == null) {
            return Result.fail(NO_SELECTION);
        }
        try {
            Region r = new Region(Region.validateId(id), level.dimension(), box, RegionType.PROTECTED);
            r.setPriority(priority);
            RegionStore.get(level).add(r);
            return Result.ok("Region " + r.id() + " created: " + box + ", " + box.volume() + " blocks, priority " + priority + ".");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
    }

    public static Result redefine(RegionStore store, Region r, @Nullable Cuboid box) {
        if (r.isGlobal()) {
            return Result.fail("The world default has no bounds.");
        }
        if (box == null) {
            return Result.fail(NO_SELECTION);
        }
        r.setBounds(box);
        store.touch(r);
        return Result.ok("Region " + r.id() + " now covers " + box + ".");
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
        if ((type == RegionType.CLAIM || type == RegionType.PLOT_FOR_SALE || type == RegionType.OPEN_FOR_BUYING) && !TotemRule.claimsAllowedIn(level)) {
            return Result.fail("Claims are not allowed in a PvP world (where Shatter Totems can be placed). See the claims section of the config.");
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
        Selections.of(player).set(player.serverLevel().dimension(), r.bounds());
        Selections.sync(player);
        return Result.ok("Selection set to " + r.id() + ": " + r.bounds() + ".");
    }

    /** To the region's teleport point, or onto the surface at its middle. */
    public static Result teleport(ServerPlayer p, RegionStore store, Region r) {
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
            return "Claims are not allowed in a PvP world.";
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
        return Result.ok(price > 0 ? r.id() + " is for sale at " + BspCore.money(price) + "." : r.id() + " is not for sale.");
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
        return Result.ok(cost > 0 ? r.id() + " is open for buying at " + BspCore.money(cost) + " per block." : r.id() + " is closed for buying.");
    }

    /** An open area's own plot size limits; 0 leaves a limit to the config. */
    public static Result plotSizes(RegionStore store, Region r, int min, int max) {
        ClaimData.set(r, ClaimData.MIN_SIZE, min);
        ClaimData.set(r, ClaimData.MAX_SIZE, max);
        store.touch(r);
        return Result.ok(min > 0 || max > 0 ? "Plots in " + r.id() + " are " + min + " to " + max + " blocks a side." : "Plot sizes in " + r.id() + " follow the config.");
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
