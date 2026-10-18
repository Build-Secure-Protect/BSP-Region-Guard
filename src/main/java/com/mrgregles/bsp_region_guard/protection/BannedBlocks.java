package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.flag.StringSetFlag;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Banned blocks per region (owner decisions D51 to D56). Four list flags name blocks and items: {@code deny-place},
 * {@code deny-use}, {@code deny-break} and {@code deny-carry}; CARRY also counts as the other three. An entry is an
 * id ({@code minecraft:tnt}), a whole mod ({@code @create} or {@code create:*}) or a tag ({@code #minecraft:beds}).
 *
 * <p>Unlike other flags, the lists add up: a block is banned at a spot when any region covering it (or any of those
 * regions' parents, or the world default) bans it, so a child region can never lift a ban its parent makes. The
 * rules apply to everyone, owners and members included; only admins with bypass on, and the exemptions that keep a
 * Shatter Totem steal possible, pass.
 */
public final class BannedBlocks {
    /** What a ban stops, with the list flag and the ordinary flag whose exemptions also lift it. */
    public enum Rule {
        PLACE(Flags.DENY_PLACE, Flags.BLOCK_PLACE, "placed"),
        USE(Flags.DENY_USE, Flags.USE, "used"),
        BREAK(Flags.DENY_BREAK, Flags.BLOCK_BREAK, "broken"),
        CARRY(Flags.DENY_CARRY, Flags.ENTRY, "brought");

        public final StringSetFlag flag;
        final StateFlag exemptAs;
        final String verb;

        Rule(StringSetFlag flag, StateFlag exemptAs, String verb) {
            this.flag = flag;
            this.exemptAs = exemptAs;
            this.verb = verb;
        }
    }

    /** One banned thing a player carries: the stack, where it is ("in a backpack"), and the region that bans it. */
    public record Carried(ItemStack stack, String where, String regionId) {
    }

    /** How deep carried containers are searched (a shulker box in a backpack is two). */
    private static final int NEST_DEPTH = 3;
    /** How often a player inside is checked for banned items picked up some other way, in ticks. */
    static final int CARRY_CHECK_TICKS = 20;

    private BannedBlocks() {
    }

    // ------------------------------------------------------------------ the lists

    /** Every entry for a rule at a spot, each mapped to the region that bans it. CARRY entries count for every rule. */
    public static Map<String, String> entries(ServerLevel level, BlockPos pos, Rule rule) {
        RegionStore store = RegionStore.get(level);
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Region r : store.at(pos)) {
            Region a = r;
            for (int i = 0; i < 32 && a != null && seen.add(a.id()); i++) {
                add(out, a, rule.flag);
                if (rule != Rule.CARRY) {
                    add(out, a, Flags.DENY_CARRY);
                }
                a = store.parentOf(a);
            }
        }
        return out;
    }

    private static void add(Map<String, String> out, Region r, StringSetFlag flag) {
        Set<String> v = r.flag(flag);
        if (v != null) {
            for (String e : v) {
                out.putIfAbsent(e.toLowerCase(Locale.ROOT), r.id());
            }
        }
    }

    // ------------------------------------------------------------------ matching

    /** The entry that matches this item, or {@code null}. */
    @Nullable
    public static String match(Map<String, String> entries, ItemStack stack) {
        if (stack.isEmpty() || entries.isEmpty()) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        Block block = stack.getItem() instanceof BlockItem bi ? bi.getBlock() : null;
        for (String e : entries.keySet()) {
            if (e.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(e.substring(1));
                if (tag != null && (stack.is(ItemTags.create(tag)) || (block != null && block.defaultBlockState().is(BlockTags.create(tag))))) {
                    return e;
                }
            } else if (idMatches(e, id) || (block != null && idMatches(e, ForgeRegistries.BLOCKS.getKey(block)))) {
                return e;
            }
        }
        return null;
    }

    /** The entry that matches this block, by its own id, its item's id or a tag, or {@code null}. */
    @Nullable
    public static String match(Map<String, String> entries, BlockState state) {
        if (entries.isEmpty()) {
            return null;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        ItemStack asItem = new ItemStack(state.getBlock().asItem());
        for (String e : entries.keySet()) {
            if (e.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(e.substring(1));
                if (tag != null && state.is(BlockTags.create(tag))) {
                    return e;
                }
            } else if (idMatches(e, id)) {
                return e;
            }
        }
        // a wall torch is placed from the torch item: ban the item, the block goes too
        return asItem.isEmpty() ? null : match(entries, asItem);
    }

    /**
     * The BANNED BLOCKS screen's search, as JEI's: {@code @mod} (or {@code @mod name}) shows that mod's things,
     * anything else matches the name or the id, ignoring case. An empty search matches everything.
     */
    public static boolean searchMatches(String query, ResourceLocation id, String name) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return true;
        }
        if (q.startsWith("@")) {
            int space = q.indexOf(' ');
            String mod = space < 0 ? q.substring(1) : q.substring(1, space);
            if (!id.getNamespace().startsWith(mod)) {
                return false;
            }
            q = space < 0 ? "" : q.substring(space + 1).trim();
            if (q.isEmpty()) {
                return true;
            }
        }
        return name.toLowerCase(Locale.ROOT).contains(q) || id.toString().contains(q);
    }

    static boolean idMatches(String entry, @Nullable ResourceLocation id) {
        if (id == null) {
            return false;
        }
        if (entry.startsWith("@")) {
            return id.getNamespace().equals(entry.substring(1));
        }
        if (entry.endsWith(":*")) {
            return id.getNamespace().equals(entry.substring(0, entry.length() - 2));
        }
        return entry.equals(id.toString());
    }

    // ------------------------------------------------------------------ place, use, break

    /**
     * Refuses a player's action on a banned block or item and tells them. Returns true when refused. {@code state}
     * or {@code stack} may be null when only the other one applies.
     */
    public static boolean refuses(ServerLevel level, BlockPos pos, Rule rule, @Nullable BlockState state, @Nullable ItemStack stack, Player player) {
        if (RegionQuery.bypasses(player) || RegionQuery.isExempt(level, pos, rule.exemptAs, player)) {
            return false;
        }
        Map<String, String> entries = entries(level, pos, rule);
        if (entries.isEmpty()) {
            return false;
        }
        String hit = state != null ? match(entries, state) : null;
        Component name = state != null ? state.getBlock().getName() : null;
        if (hit == null && stack != null) {
            hit = match(entries, stack);
            name = stack.getHoverName();
        }
        if (hit == null) {
            return false;
        }
        Protection.tellOnce(player, name.getString() + " can't be " + rule.verb + " in " + entries.get(hit) + ".");
        return true;
    }

    // ------------------------------------------------------------------ carrying

    /** Whether CARRY bans are in force for this player here (not for admins with bypass, nor near a stolen totem). */
    static boolean carryApplies(ServerLevel level, BlockPos pos, Player player) {
        return !RegionQuery.bypasses(player) && !RegionQuery.isExempt(level, pos, Flags.ENTRY, player);
    }

    /** True when the player carries anything not allowed at this spot. For {@code teleport-in}. */
    public static boolean carriesBanned(ServerLevel level, BlockPos pos, ServerPlayer player) {
        return carryApplies(level, pos, player) && !carried(level, pos, player).isEmpty();
    }

    /** The banned things a player carries that this spot does not allow, one per kind of item. */
    public static List<Carried> carried(ServerLevel level, BlockPos pos, ServerPlayer player) {
        Map<String, String> entries = entries(level, pos, Rule.CARRY);
        List<Carried> out = new ArrayList<>();
        if (entries.isEmpty()) {
            return out;
        }
        Set<String> found = new HashSet<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            scan(entries, inv.getItem(i), "", 0, out, found);
        }
        scan(entries, player.containerMenu.getCarried(), "", 0, out, found);
        for (ItemStack s : com.mrgregles.bsp_region_guard.compat.Curios.equipped(player)) {
            scan(entries, s, "in a Curios slot", 0, out, found);
        }
        return out;
    }

    /** The first banned thing in a stack or anything packed inside it, or {@code null}. */
    @Nullable
    public static Carried bannedIn(Map<String, String> entries, ItemStack stack) {
        List<Carried> out = new ArrayList<>();
        scan(entries, stack, "", 0, out, new HashSet<>());
        return out.isEmpty() ? null : out.get(0);
    }

    private static void scan(Map<String, String> entries, ItemStack stack, String where, int depth, List<Carried> out, Set<String> found) {
        if (stack.isEmpty()) {
            return;
        }
        String hit = match(entries, stack);
        if (hit != null) {
            String key = String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
            if (found.add(key)) {
                out.add(new Carried(stack.copy(), where, entries.get(hit)));
            }
        }
        if (depth >= NEST_DEPTH) {
            return;
        }
        String inside = "in " + stack.getHoverName().getString();
        // shulker boxes and other blocks that keep their contents when broken
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            if (tag.contains("BlockEntityTag", Tag.TAG_COMPOUND)) {
                scanList(entries, tag.getCompound("BlockEntityTag").getList("Items", Tag.TAG_COMPOUND), inside, depth, out, found);
            }
            scanList(entries, tag.getList("Items", Tag.TAG_COMPOUND), inside, depth, out, found); // bundles
        }
        // backpacks and other items that hold an inventory of their own
        stack.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            for (int i = 0; i < handler.getSlots(); i++) {
                scan(entries, handler.getStackInSlot(i), inside, depth + 1, out, found);
            }
        });
    }

    private static void scanList(Map<String, String> entries, ListTag list, String where, int depth, List<Carried> out, Set<String> found) {
        for (int i = 0; i < list.size(); i++) {
            scan(entries, ItemStack.of(list.getCompound(i)), where, depth + 1, out, found);
        }
    }

    /** The line shown when a player is turned away for carrying banned things. */
    public static String carryMessage(List<Carried> carried) {
        Carried first = carried.get(0);
        String what = first.stack().getHoverName().getString() + (carried.size() > 1 ? " and " + (carried.size() - 1) + " more" : "");
        return "Remove " + what + " from your inventory to enter " + first.regionId() + ".";
    }

    /** How often one player gets the pop-up at most; in between, the action bar line alone. */
    private static final long POPUP_GAP_MS = 5000;
    private static final Map<java.util.UUID, Long> LAST_POPUP = new java.util.HashMap<>();

    /** Tells a player what they carry that keeps them out: the action bar line and, at most every 5 s, the pop-up. */
    static void tellCarried(ServerPlayer player, List<Carried> carried) {
        player.displayClientMessage(Component.literal(carryMessage(carried)), true);
        long now = System.currentTimeMillis();
        Long last = LAST_POPUP.get(player.getUUID());
        if (last != null && now - last < POPUP_GAP_MS) {
            return;
        }
        LAST_POPUP.put(player.getUUID(), now);
        com.mrgregles.bsp_region_guard.network.RegionNetwork.sendTo(player, popup(carried));
    }

    /** The pop-up for what a player carries: each thing, and where it is ("x 12", "in Shulker Box"). */
    public static com.mrgregles.bsp_region_guard.network.BannedPopupPacket popup(List<Carried> carried) {
        List<ItemStack> items = new ArrayList<>();
        List<String> where = new ArrayList<>();
        for (Carried c : carried) {
            items.add(c.stack());
            where.add(c.where().isEmpty() ? "x " + c.stack().getCount() : c.where());
        }
        return new com.mrgregles.bsp_region_guard.network.BannedPopupPacket(carried.get(0).regionId(), items, where);
    }

    public static void forget(java.util.UUID player) {
        LAST_POPUP.remove(player);
    }

    /**
     * For a player already inside (logged in there, arrived by some route the movement check did not see, or a ban
     * added while they stood there): moves them just outside the region that bans what they carry. Returns true when
     * moved.
     */
    static boolean moveOutIfCarrying(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        if (!carryApplies(level, pos, player)) {
            return false;
        }
        List<Carried> carried = carried(level, pos, player);
        if (carried.isEmpty()) {
            return false;
        }
        Region r = RegionStore.get(level).get(carried.get(0).regionId());
        Cuboid b = r == null ? null : r.bounds();
        // a child may carry its parent's ban: step out of the outermost box that bans it
        RegionStore store = RegionStore.get(level);
        for (Region a = r; a != null; a = store.parentOf(a)) {
            if (a.bounds() != null && a.bounds().contains(pos)) {
                b = a.bounds();
            }
        }
        tellCarried(player, carried);
        if (b == null) {
            return false; // banned by the world default: there is nowhere to step out to
        }
        Vec3 out = MovementTracker.nearestOutside(player.position(), b);
        Vec3 spot = WorldBorders.standingSpot(level, out.x, out.z, out.y);
        player.teleportTo(level, spot.x, spot.y, spot.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        return true;
    }

    // ------------------------------------------------------------------ containers (mixin into AbstractContainerMenu.clicked)

    /**
     * Inside a region that bans carrying something, a player may not take it out of any container, the ender chest
     * included (D54): the click is refused and the screen put back as it was. Returns true when refused.
     */
    public static boolean refusesClick(AbstractContainerMenu menu, int slotId, int button, ClickType type, Player player) {
        if (!(player instanceof ServerPlayer sp) || !RegionGuardConfig.getOr(RegionGuardConfig.MIXIN_CONTAINERS, true)) {
            return false;
        }
        ServerLevel level = sp.serverLevel();
        BlockPos pos = sp.blockPosition();
        RegionStore store = RegionStore.get(level);
        if (!store.hasGlobal() && store.nothingAt(pos.getX(), pos.getZ())) {
            return false;
        }
        if (slotId < 0 || slotId >= menu.slots.size() || !carryApplies(level, pos, sp)) {
            return false;
        }
        Slot slot = menu.slots.get(slotId);
        if (slot.container == sp.getInventory()) {
            return false; // moving things around their own inventory
        }
        Map<String, String> entries = entries(level, pos, Rule.CARRY);
        Carried hit = entries.isEmpty() ? null : bannedIn(entries, slot.getItem());
        if (hit == null) {
            return false;
        }
        sp.displayClientMessage(Component.literal(hit.stack().getHoverName().getString() + " can't be taken out here: it is not allowed in " + hit.regionId() + "."), true);
        menu.sendAllDataToRemote();
        return true;
    }
}
