package com.mrgregles.bsp_region_guard.network;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.compat.LuckPermsBridge;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagContext;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.TotemRule;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import com.mrgregles.bsp_region_guard.registry.ModItems;
import com.mrgregles.bsp_region_guard.selection.Selection;
import com.mrgregles.bsp_region_guard.selection.Selections;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The server side of the screen: builds the list and detail packets and applies the actions the client sends.
 * Every action is checked against the same rule as the commands: the sender must be an operator or a BSP Core admin.
 */
public final class RegionSync {
    private RegionSync() {
    }

    public static boolean mayEdit(ServerPlayer player) {
        return player.hasPermissions(2) || BspCore.isAdmin(player);
    }

    /** Opens the screen for a player on the region they stand in (or the first one). */
    public static void open(ServerPlayer player) {
        if (!mayEdit(player)) {
            player.displayClientMessage(Component.literal("Only operators and BSP Core admins can open the region screen."), true);
            return;
        }
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        String select = null;
        for (Region r : store.at(player.blockPosition())) {
            if (!r.isGlobal()) {
                select = r.id();
                break;
            }
        }
        if (select == null && store.size() > 0) {
            select = store.all().iterator().next().id();
        }
        sendList(player, select, true);
        if (select != null) {
            sendDetail(player, store.get(select));
        }
    }

    /** Admins see every region; other players only the regions they belong to and the plots for sale, so the client can draw them. */
    public static List<RegionListPacket.Summary> listFor(ServerPlayer player) {
        RegionStore store = RegionStore.get(player.serverLevel());
        boolean all = mayEdit(player);
        List<RegionListPacket.Summary> list = new ArrayList<>();
        for (Region r : store.all()) {
            boolean visible = all || r.isMember(player.getUUID())
                    || r.type() == RegionType.PLOT_FOR_SALE || r.type() == RegionType.OPEN_FOR_BUYING;
            if (visible) {
                list.add(new RegionListPacket.Summary(r.id(), r.name(), r.type().key(), r.priority(), r.parentId(), r.bounds(),
                        r.owners().size(), r.members().size(), r.flags().size()));
            }
        }
        return list;
    }

    public static void sendList(ServerPlayer player, @Nullable String select, boolean open) {
        RegionNetwork.sendTo(player, new RegionListPacket(player.serverLevel().dimension().location(), listFor(player), select, open));
    }

    /** Every player in the store's world gets a fresh list (after a region changed). */
    public static void broadcast(RegionStore store) {
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(store.dimension());
        if (level == null) {
            return;
        }
        for (ServerPlayer p : level.players()) {
            sendList(p, null, false);
        }
    }

    public static void onLogin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sendList(p, null, false);
            com.mrgregles.bsp_region_guard.selection.Selections.sync(p);
        }
    }

    public static void onChangedDimension(net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sendList(p, null, false);
        }
    }

    public static void onRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            sendList(p, null, false);
        }
    }

    public static void sendDetail(ServerPlayer player, @Nullable Region r) {
        if (r == null) {
            return;
        }
        RegionStore store = RegionStore.get(player.serverLevel());
        Map<String, String> flags = new LinkedHashMap<>();
        for (Map.Entry<Flag<?>, Object> e : r.flags().entrySet()) {
            flags.put(e.getKey().name(), format(e.getKey(), e.getValue()));
        }
        Map<String, String> inherited = new LinkedHashMap<>();
        if (r.parentId() != null) {
            for (Flag<?> f : FlagRegistry.all()) {
                if (r.flag(f) == null) {
                    Object v = RegionQuery.effective(store, r, f);
                    if (v != null) {
                        inherited.put(f.name(), format(f, v));
                    }
                }
            }
        }
        Map<String, String> data = new LinkedHashMap<>();
        for (String key : r.typeData().getAllKeys()) {
            if (!key.equals(com.mrgregles.bsp_region_guard.mine.MineData.FILL) && !key.equals(com.mrgregles.bsp_region_guard.mine.MineData.SPAWN)) {
                data.put(key, r.typeData().get(key).getAsString());
            }
        }
        var triggers = com.mrgregles.bsp_region_guard.trigger.Trigger.of(r.typeData());
        for (int i = 0; i < triggers.size() && i < 20; i++) {
            data.put("trigger." + (i + 1), triggers.get(i).describe());
        }
        data.remove(com.mrgregles.bsp_region_guard.trigger.Trigger.KEY);
        if (r.type() == RegionType.MINE) {
            StringBuilder fill = new StringBuilder();
            for (var e : com.mrgregles.bsp_region_guard.mine.MineData.fill(r)) {
                fill.append(fill.length() == 0 ? "" : ";").append(e.block()).append('=').append(e.weight());
            }
            data.put("fill", fill.toString());
            data.put("secondsToReset", String.valueOf(com.mrgregles.bsp_region_guard.mine.Mines.secondsToReset(r)));
            data.put("hasSpawn", String.valueOf(com.mrgregles.bsp_region_guard.mine.MineData.spawn(r) != null));
            data.put("hasShell", String.valueOf(store.has(r.id() + "_shell")));
        }
        RegionNetwork.sendTo(player, new RegionDetailPacket(r.id(), r.name(), r.type().key(), r.priority(), r.parentId(), r.bounds(),
                people(player, r.owners()), people(player, r.members()), new ArrayList<>(r.ranks()),
                flags, inherited, LuckPermsBridge.groupNames(), r.version(), r.updatedAt(), data));
    }

    @SuppressWarnings("unchecked")
    private static <T> String format(Flag<T> flag, Object value) {
        return flag.format((T) value);
    }

    private static List<RegionDetailPacket.Person> people(ServerPlayer viewer, java.util.Set<UUID> uuids) {
        List<RegionDetailPacket.Person> out = new ArrayList<>();
        for (UUID u : uuids) {
            String name = viewer.server.getProfileCache() == null ? null
                    : viewer.server.getProfileCache().get(u).map(GameProfile::getName).orElse(null);
            if (name == null) {
                ServerPlayer online = viewer.server.getPlayerList().getPlayer(u);
                name = online != null ? online.getGameProfile().getName() : u.toString().substring(0, 8);
            }
            out.add(new RegionDetailPacket.Person(u, name));
        }
        return out;
    }

    // ------------------------------------------------------------------ actions

    public static void handle(ServerPlayer player, RegionActionPacket msg) {
        if (!mayEdit(player)) {
            return;
        }
        String outcome = apply(player, msg);
        if (outcome != null && !outcome.isEmpty()) {
            player.displayClientMessage(Component.literal(outcome), true);
        }
    }

    /**
     * Applies one action and answers with fresh packets. Returns a message for the player, or null. Package-visible
     * for tests, which skip the permission check.
     */
    @Nullable
    public static String apply(ServerPlayer player, RegionActionPacket msg) {
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        String id = msg.regionId();
        Region r = id.isEmpty() ? null : id.equals(Region.GLOBAL_ID) ? store.global() : store.get(id);
        String a = msg.a().trim(), b = msg.b().trim();
        try {
            switch (msg.action()) {
                case RegionActionPacket.LIST -> {
                    sendList(player, r == null ? null : r.id(), false);
                    return null;
                }
                case RegionActionPacket.SELECT -> {
                    if (r != null) {
                        sendDetail(player, r);
                    }
                    return null;
                }
                case RegionActionPacket.LIST_REMOTE -> {
                    var mirror = com.mrgregles.bsp_region_guard.storage.RegionMirror.active();
                    if (mirror == null) {
                        return "Shared storage is off; no other servers to show.";
                    }
                    String select = r == null ? null : r.id();
                    mirror.fetchOthers(level, remote -> {
                        List<RegionListPacket.Summary> list = listFor(player);
                        list.addAll(remote);
                        RegionNetwork.sendTo(player, new RegionListPacket(level.dimension().location(), list, select, false));
                    });
                    return null;
                }
                case RegionActionPacket.WAND -> {
                    ItemStack wand = new ItemStack(ModItems.REGION_WAND.get());
                    if (!player.getInventory().add(wand)) {
                        player.drop(wand, false);
                    }
                    return "Region wand given.";
                }
                case RegionActionPacket.CREATE -> {
                    Selection sel = Selections.of(player);
                    Cuboid box = sel.cuboid();
                    if (box == null || !level.dimension().equals(sel.dimension())) {
                        return "Select two corners with the wand first.";
                    }
                    Region created = new Region(Region.validateId(a), level.dimension(), box, RegionType.PROTECTED);
                    store.add(created);
                    sendList(player, created.id(), false);
                    sendDetail(player, created);
                    return "Region " + created.id() + " created.";
                }
                default -> {
                    if (r == null) {
                        return "No region named '" + id + "'.";
                    }
                }
            }
            String message = edit(player, level, store, r, msg.action(), a, b);
            Region after = store.get(r.id());
            sendList(player, after == null ? null : after.id(), false);
            if (after != null) {
                sendDetail(player, after);
            }
            return message;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return e.getMessage();
        }
    }

    @Nullable
    private static String edit(ServerPlayer player, ServerLevel level, RegionStore store, Region r, String action, String a, String b) {
        switch (action) {
            case RegionActionPacket.FLAG -> {
                Flag<?> flag = FlagRegistry.get(a);
                if (flag == null) {
                    return "No flag named '" + a + "'.";
                }
                if (b.isEmpty()) {
                    r.setFlag(flag, null);
                } else {
                    setParsed(r, flag, b, new FlagContext(level, player));
                }
                store.touch(r);
                return null;
            }
            case RegionActionPacket.ADD_OWNER, RegionActionPacket.REMOVE_OWNER, RegionActionPacket.ADD_MEMBER, RegionActionPacket.REMOVE_MEMBER -> {
                UUID who = resolvePlayer(player, a);
                if (who == null) {
                    return "No player called '" + a + "' is known to this server.";
                }
                switch (action) {
                    case RegionActionPacket.ADD_OWNER -> r.addOwner(who);
                    case RegionActionPacket.REMOVE_OWNER -> r.removeOwner(who);
                    case RegionActionPacket.ADD_MEMBER -> r.addMember(who);
                    default -> r.removeMember(who);
                }
                store.touch(r);
                return null;
            }
            case RegionActionPacket.ADD_RANK -> {
                r.addRank(a);
                store.touch(r);
                return null;
            }
            case RegionActionPacket.REMOVE_RANK -> {
                r.removeRank(a);
                store.touch(r);
                return null;
            }
            case RegionActionPacket.PRIORITY -> {
                r.setPriority(Integer.parseInt(a));
                store.touch(r);
                return null;
            }
            case RegionActionPacket.PARENT -> {
                if (a.isEmpty() || a.equalsIgnoreCase("none")) {
                    r.setParentId(null);
                } else {
                    Region parent = store.get(a);
                    if (parent == null) {
                        return "No region named '" + a + "'.";
                    }
                    if (parent == r || store.isAncestor(r, parent)) {
                        return "That would make a loop of parents.";
                    }
                    r.setParentId(parent.id());
                }
                store.touch(r);
                return null;
            }
            case RegionActionPacket.TYPE -> {
                RegionType type = RegionType.byKey(a.toLowerCase());
                if (type == null || type == RegionType.GLOBAL || r.isGlobal()) {
                    return "Types: protected, plot_for_sale, open_for_buying, claim, mine.";
                }
                if ((type == RegionType.CLAIM || type == RegionType.PLOT_FOR_SALE || type == RegionType.OPEN_FOR_BUYING) && !TotemRule.claimsAllowedIn(level)) {
                    return "Claims are not allowed in a PvP world.";
                }
                r.setType(type);
                store.touch(r);
                return null;
            }
            case RegionActionPacket.RENAME -> {
                r.setName(a.isEmpty() ? r.id() : a);
                store.touch(r);
                return null;
            }
            case RegionActionPacket.DELETE -> {
                store.remove(r.id());
                return "Region " + r.id() + " removed.";
            }
            case RegionActionPacket.REDEFINE -> {
                Selection sel = Selections.of(player);
                Cuboid box = sel.cuboid();
                if (box == null || !level.dimension().equals(sel.dimension())) {
                    return "Select two corners with the wand first.";
                }
                if (r.isGlobal()) {
                    return "The world default has no bounds.";
                }
                r.setBounds(box);
                store.touch(r);
                return "Region " + r.id() + " now covers " + box + ".";
            }
            case RegionActionPacket.SELECT_BOUNDS -> {
                if (r.bounds() != null) {
                    Selections.of(player).set(level.dimension(), r.bounds());
                    Selections.sync(player);
                    return "Selection set to " + r.id() + ".";
                }
                return "The world default has no corners.";
            }
            case RegionActionPacket.TELEPORT -> {
                return teleport(player, level, store, r);
            }
            case RegionActionPacket.MINE_CREATE -> {
                if (r.isGlobal()) {
                    return "The world default cannot be a mine.";
                }
                com.mrgregles.bsp_region_guard.mine.Mines.makeMine(store, r);
                return r.id() + " is now a mine.";
            }
            case RegionActionPacket.MINE_ADD -> {
                net.minecraft.resources.ResourceLocation block = net.minecraft.resources.ResourceLocation.tryParse(a.contains(":") ? a : "minecraft:" + a);
                int weight = b.isEmpty() ? 0 : Integer.parseInt(b);
                if (block == null || weight > 0 && !net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(block)) {
                    return "No block called " + a + ".";
                }
                com.mrgregles.bsp_region_guard.mine.MineData.put(r, block, weight);
                store.touch(r);
                return null;
            }
            case RegionActionPacket.MINE_INTERVAL -> {
                com.mrgregles.bsp_region_guard.mine.Mines.schedule(store, r, a.isEmpty() ? 0 : Integer.parseInt(a));
                return null;
            }
            case RegionActionPacket.MINE_SPAWN -> {
                com.mrgregles.bsp_region_guard.mine.MineData.setSpawn(r, a.equals("clear") ? null
                        : FlagLocation.of(level, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
                store.touch(r);
                return a.equals("clear") ? "Reset spawn cleared." : "Players are moved to where you stand when the mine resets.";
            }
            case RegionActionPacket.MINE_SHELL -> {
                if (r.type() != RegionType.MINE) {
                    return r.id() + " is not a mine.";
                }
                Region shell = com.mrgregles.bsp_region_guard.mine.Mines.makeShell(level, store, r, Math.max(1, Math.min(64, a.isEmpty() ? 5 : Integer.parseInt(a))));
                return shell.id() + " surrounds the mine.";
            }
            case RegionActionPacket.MINE_RESET -> {
                return com.mrgregles.bsp_region_guard.mine.Mines.reset(level, store, r);
            }
            case RegionActionPacket.TRIGGER_ADD -> {
                com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent.Cause cause;
                try {
                    cause = com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent.Cause.valueOf(a.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "Causes: enter, exit, timer, shutdown.";
                }
                var trigger = com.mrgregles.bsp_region_guard.trigger.Triggers.parse(player, cause, b);
                var triggers = com.mrgregles.bsp_region_guard.trigger.Trigger.of(r.typeData());
                triggers.add(trigger);
                com.mrgregles.bsp_region_guard.trigger.Trigger.store(r.typeData(), triggers);
                store.touch(r);
                return "Trigger added: " + trigger.describe();
            }
            case RegionActionPacket.TRIGGER_REMOVE -> {
                var triggers = com.mrgregles.bsp_region_guard.trigger.Trigger.of(r.typeData());
                int index = Integer.parseInt(a);
                if (index < 1 || index > triggers.size()) {
                    return "No trigger " + index + ".";
                }
                triggers.remove(index - 1);
                com.mrgregles.bsp_region_guard.trigger.Trigger.store(r.typeData(), triggers);
                store.touch(r);
                return null;
            }
            case RegionActionPacket.SET_DATA -> {
                if (r.isGlobal()) {
                    return "The world default cannot be sold.";
                }
                String key = a;
                if (key.equals(com.mrgregles.bsp_region_guard.claim.ClaimData.PLAYER_FLAGS)) {
                    var unknown = com.mrgregles.bsp_region_guard.claim.ClaimFlags.setAllowed(r, b);
                    store.touch(r);
                    return unknown.isEmpty() ? null : "Not flags, ignored: " + String.join(", ", unknown);
                }
                long value = a.isEmpty() ? 0 : Long.parseLong(b.isEmpty() ? "0" : b);
                boolean sale = key.equals(com.mrgregles.bsp_region_guard.claim.ClaimData.PRICE);
                boolean open = key.equals(com.mrgregles.bsp_region_guard.claim.ClaimData.COST_PER_BLOCK);
                if ((sale || open) && !TotemRule.claimsAllowedIn(level)) {
                    return "Claims are not allowed in a PvP world.";
                }
                if (r.type() == RegionType.CLAIM && (sale || open)) {
                    return r.id() + " is owned; abandon it first.";
                }
                com.mrgregles.bsp_region_guard.claim.ClaimData.set(r, key, value);
                if (sale && value > 0) {
                    r.setType(RegionType.PLOT_FOR_SALE);
                } else if (open && value > 0) {
                    r.setType(RegionType.OPEN_FOR_BUYING);
                } else if ((sale || open) && value <= 0) {
                    r.setType(RegionType.PROTECTED);
                }
                store.touch(r);
                return null;
            }
            default -> {
                return "Unknown action " + action;
            }
        }
    }

    private static <T> void setParsed(Region r, Flag<T> flag, String value, FlagContext context) {
        r.setFlag(flag, flag.parse(value, context));
    }

    @Nullable
    public static UUID resolvePlayer(ServerPlayer viewer, String nameOrUuid) {
        try {
            return UUID.fromString(nameOrUuid);
        } catch (IllegalArgumentException ignored) {
            // a name
        }
        ServerPlayer online = viewer.server.getPlayerList().getPlayerByName(nameOrUuid);
        if (online != null) {
            return online.getUUID();
        }
        if (viewer.server.getProfileCache() != null) {
            Optional<GameProfile> cached = viewer.server.getProfileCache().get(nameOrUuid);
            if (cached.isPresent()) {
                return cached.get().getId();
            }
        }
        return null;
    }

    static String teleport(ServerPlayer p, ServerLevel level, RegionStore store, Region r) {
        FlagLocation target = r.flag(Flags.TELEPORT);
        if (target != null) {
            ServerLevel dest = p.server.getLevel(target.dimension());
            if (dest == null) {
                return "The teleport point is in a world that does not exist.";
            }
            p.teleportTo(dest, target.x(), target.y(), target.z(), target.yaw(), target.pitch());
        } else if (r.bounds() != null) {
            Cuboid bnd = r.bounds();
            double x = (bnd.minX() + bnd.maxX() + 1) / 2.0, z = (bnd.minZ() + bnd.maxZ() + 1) / 2.0;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            y = Math.max(bnd.minY(), Math.min(bnd.maxY() + 1, y));
            p.teleportTo(level, x, y, z, p.getYRot(), p.getXRot());
        } else {
            return "The world default has nowhere to teleport to.";
        }
        String message = RegionQuery.effective(store, r, Flags.TELEPORT_MESSAGE);
        return message != null ? message : "Teleported to " + r.id() + ".";
    }

    /** For the map: the surface height at a column, or {@code Integer.MIN_VALUE} when the chunk is not loaded. */
    public static int surface(ServerLevel level, BlockPos pos) {
        return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) : Integer.MIN_VALUE;
    }
}
