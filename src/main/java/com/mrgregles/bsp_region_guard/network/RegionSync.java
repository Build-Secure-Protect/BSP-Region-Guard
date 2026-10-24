package com.mrgregles.bsp_region_guard.network;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_region_guard.claim.ClaimData;
import com.mrgregles.bsp_region_guard.command.RegionEdits;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.compat.LuckPermsBridge;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagContext;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import com.mrgregles.bsp_region_guard.registry.ModItems;
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
        open(player, true);
    }

    /** As {@link #open(ServerPlayer)}; with {@code screen} false the open screen is just refilled (back from another server). */
    static void open(ServerPlayer player, boolean screen) {
        if (screen) {
            RemoteViews.clear(player); // a fresh screen always starts on this server
        }
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
        sendList(player, select, screen);
        if (select != null) {
            sendDetail(player, store.get(select));
        }
        if (screen && com.mrgregles.bsp_region_guard.storage.RegionMirror.active() != null) {
            String keep = select; // re-read which servers exist, so the server picker is current
            com.mrgregles.bsp_region_guard.protection.EdgeLinks.refresh(() -> {
                if (!player.hasDisconnected() && RemoteViews.of(player) == null) {
                    sendList(player, keep, false);
                }
            });
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
                boolean forSale = r.type() == RegionType.PLOT_FOR_SALE || r.type() == RegionType.OPEN_FOR_BUYING;
                list.add(new RegionListPacket.Summary(r.id(), r.name(), r.type().key(), r.priority(), r.parentId(), r.bounds(),
                        r.owners().size(), r.members().size(), r.flags().size(),
                        forSale ? com.mrgregles.bsp_region_guard.claim.PlotLook.of(r) : null, forSale ? priceLine(r) : ""));
            }
        }
        return list;
    }

    /** The price as the for sale sign shows it (D108): {@code 3 gold coins}, or {@code 2 gold coins a block} for an open area. */
    static String priceLine(Region r) {
        String tier = com.mrgregles.bsp_region_guard.claim.Prices.tier(r);
        return r.type() == RegionType.OPEN_FOR_BUYING
                ? com.mrgregles.bsp_region_guard.claim.Prices.text(tier, ClaimData.costPerBlock(r)) + " a block"
                : com.mrgregles.bsp_region_guard.claim.Prices.text(tier, ClaimData.price(r));
    }

    public static void sendList(ServerPlayer player, @Nullable String select, boolean open) {
        RemoteViews.View view = open ? null : RemoteViews.of(player);
        if (view != null) { // looking at another server: a change here does not pull them back
            sendRemoteList(player, view, select);
            return;
        }
        List<String> servers = mayEdit(player) ? RemoteViews.servers() : List.of();
        RegionNetwork.sendTo(player, new RegionListPacket(player.serverLevel().dimension().location(), listFor(player), select, open, "", servers, mayEdit(player)));
    }

    /** Another server's regions in this world, read-only (D44). */
    static void sendRemoteList(ServerPlayer player, RemoteViews.View view, @Nullable String select) {
        List<RegionListPacket.Summary> list = new ArrayList<>();
        for (Region r : view.regions().values()) {
            list.add(new RegionListPacket.Summary(r.id(), r.name(), r.type().key(), r.priority(), r.parentId(), r.bounds(),
                    r.owners().size(), r.members().size(), r.flags().size()));
        }
        RegionNetwork.sendTo(player, new RegionListPacket(player.serverLevel().dimension().location(), list, select, false, view.server(), RemoteViews.servers(), true));
    }

    /** Worlds whose regions changed during this tick; their players get one fresh list when it ends. */
    private static final java.util.Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> CHANGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Every player in the store's world gets a fresh list (after a region changed), once at the end of the tick: an
     * edit that touches several regions, or several edits in one tick, send one list rather than one each.
     */
    public static void broadcast(RegionStore store) {
        CHANGED.add(store.dimension());
    }

    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || CHANGED.isEmpty()) {
            return;
        }
        for (var dimension : List.copyOf(CHANGED)) {
            CHANGED.remove(dimension);
            ServerLevel level = event.getServer().getLevel(dimension);
            if (level == null) {
                continue;
            }
            for (ServerPlayer p : level.players()) {
                sendList(p, null, false);
            }
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

    /** A type-data value as the screen shows it: numbers plain ("0.0", "500"), not NBT text ("0.0d", "500L", bug B2). */
    public static String dataText(net.minecraft.nbt.Tag tag) {
        return tag instanceof net.minecraft.nbt.NumericTag n ? n.getAsNumber().toString() : tag.getAsString();
    }

    public static void sendDetail(ServerPlayer player, @Nullable Region r) {
        sendDetail(player, r, null);
    }

    /** {@code view} non-null: {@code r} is one of another server's regions, inherited values come from its own parents there. */
    static void sendDetail(ServerPlayer player, @Nullable Region r, @Nullable RemoteViews.View view) {
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
                    Object v = view == null ? RegionQuery.effective(store, r, f) : inheritedThere(view, r, f);
                    if (v != null) {
                        inherited.put(f.name(), format(f, v));
                    }
                }
            }
        }
        Map<String, String> data = new LinkedHashMap<>();
        for (String key : r.typeData().getAllKeys()) {
            if (!key.equals(com.mrgregles.bsp_region_guard.mine.MineData.FILL) && !key.equals(com.mrgregles.bsp_region_guard.mine.MineData.SPAWN)) {
                data.put(key, dataText(r.typeData().get(key)));
            }
        }
        var triggers = com.mrgregles.bsp_region_guard.trigger.Trigger.of(r.typeData());
        for (int i = 0; i < triggers.size() && i < 20; i++) {
            data.put("trigger." + (i + 1), triggers.get(i).describe());
        }
        data.remove(com.mrgregles.bsp_region_guard.trigger.Trigger.KEY);
        if (r.type() == RegionType.MINE && view == null) {
            StringBuilder fill = new StringBuilder();
            for (var e : com.mrgregles.bsp_region_guard.mine.MineData.fill(r)) {
                fill.append(fill.length() == 0 ? "" : ";").append(e.block()).append('=').append(e.weight());
            }
            data.put("fill", fill.toString());
            data.put("secondsToReset", String.valueOf(com.mrgregles.bsp_region_guard.mine.Mines.secondsToReset(r)));
            data.put("hasSpawn", String.valueOf(com.mrgregles.bsp_region_guard.mine.MineData.spawn(r) != null));
            data.put("hasShell", String.valueOf(store.has(r.id() + "_shell")));
        }
        if (!r.isGlobal() && view == null) {
            String tier = com.mrgregles.bsp_region_guard.claim.Prices.tier(r);
            data.put("currency", tier == null ? "pieces" : tier);
            if (com.mrgregles.bsp_region_guard.claim.Prices.coins()) {
                data.put("coinTiers", String.join(",", BspCore.coinTiers()));
            }
        }
        if (r.isGlobal() && view == null) {
            borderData(player.serverLevel(), data);
        }
        if (view != null) {
            data.put("remote", view.server());
        }
        RegionNetwork.sendTo(player, new RegionDetailPacket(r.id(), r.name(), r.type().key(), r.priority(), r.parentId(), r.bounds(),
                people(player, r.owners()), people(player, r.members()), new ArrayList<>(r.ranks()),
                flags, inherited, LuckPermsBridge.groupNames(), r.version(), r.updatedAt(), data));
    }

    /**
     * The claim wipe from the admin screen (D45): the same two steps as {@code /rg claims wipe} (ClaimWipe checks the
     * admin, the code and its minute), then the screen gets the new state and the server's answer.
     */
    private static void wipe(ServerPlayer player, ServerLevel level, String action, String a) {
        String message = switch (action) {
            case RegionActionPacket.WIPE_REQUEST -> com.mrgregles.bsp_region_guard.claim.ClaimWipe.request(player, level);
            case RegionActionPacket.WIPE_CONFIRM -> com.mrgregles.bsp_region_guard.claim.ClaimWipe.confirm(player, level, a);
            default -> com.mrgregles.bsp_region_guard.claim.ClaimWipe.restore(player, a);
        };
        sendWipe(player, level, message);
        if (!action.equals(RegionActionPacket.WIPE_REQUEST)) {
            sendList(player, null, false);
        }
    }

    static void sendWipe(ServerPlayer player, ServerLevel level, String message) {
        var st = com.mrgregles.bsp_region_guard.claim.ClaimWipe.state(player, level);
        RegionNetwork.sendTo(player, new ClaimWipePacket(st.claims(), st.owners(), st.code(), (int) st.secondsLeft(), st.backups(), message));
    }

    /** A flag's value from a remote region's parents, as that server would resolve it (parents only, at most 8 deep). */
    @Nullable
    private static Object inheritedThere(RemoteViews.View view, Region r, Flag<?> f) {
        Region p = r.parentId() == null ? null : view.regions().get(r.parentId());
        for (int i = 0; i < 8 && p != null; i++) {
            Object v = p.flag(f);
            if (v != null) {
                return v;
            }
            p = p.parentId() == null ? null : view.regions().get(p.parentId());
        }
        return null;
    }

    /**
     * For the BORDER tab: {@code storage} (this server's id, or "off"), {@code edge.<side>} = "server|world|their side|their
     * width|own or theirs" for each linked edge, and {@code net.<n>} = "server|world|width|free sides" for every other
     * server's world (width 0 when it has no border).
     */
    public static void borderData(ServerLevel level, Map<String, String> data) {
        data.put("plots.rule", com.mrgregles.bsp_region_guard.protection.TotemRule.plotRule(level).name().toLowerCase(java.util.Locale.ROOT));
        data.remove(com.mrgregles.bsp_region_guard.protection.EdgeLinks.KEY);
        String me = com.mrgregles.bsp_region_guard.protection.EdgeLinks.thisServer();
        data.put("storage", me.isEmpty() ? "off" : me);
        var network = com.mrgregles.bsp_region_guard.protection.EdgeLinks.network();
        var own = com.mrgregles.bsp_region_guard.protection.EdgeLinks.own(level);
        com.mrgregles.bsp_region_guard.protection.EdgeLinks.effective(level).forEach((side, p) -> {
            int width = network.stream().filter(w -> w.server().equals(p.server()) && w.dimension().equals(p.dimension()) && w.border() != null)
                    .mapToInt(w -> w.border().size()).findFirst().orElse(0);
            data.put("edge." + side.key(), p.server() + "|" + p.dimension() + "|" + p.side().key() + "|" + width + "|" + (own.containsKey(side) ? "own" : "theirs"));
        });
        int n = 0;
        for (var w : network) {
            if (w.border() == null) {
                continue; // only worlds with a border can be linked (bug B5: 55 worlds overflowed the screen's list)
            }
            StringBuilder free = new StringBuilder();
            for (var s : com.mrgregles.bsp_region_guard.protection.EdgeLinks.freeSides(level, w)) {
                free.append(free.length() == 0 ? "" : ",").append(s.key());
            }
            data.put("net." + (++n), w.server() + "|" + w.dimension() + "|" + (w.border() == null ? 0 : w.border().size()) + "|" + free);
        }
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

    /** Whether the last {@link #apply} on the server thread failed; colours its answer in the screen. */
    private static boolean lastFailed;

    public static void handle(ServerPlayer player, RegionActionPacket msg) {
        if (!mayEdit(player)) {
            return;
        }
        lastFailed = false;
        String outcome = apply(player, msg);
        if (outcome != null && !outcome.isEmpty()) {
            RegionNetwork.sendTo(player, new ActionAnswerPacket(outcome, !lastFailed)); // B14: shown in the open screen
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
        RemoteViews.View view = RemoteViews.of(player);
        if (view != null) { // looking at another server: only reading and switching back are allowed (D44)
            switch (msg.action()) {
                case RegionActionPacket.VIEW_SERVER -> {
                    return RemoteViews.view(player, a);
                }
                case RegionActionPacket.LIST -> {
                    sendRemoteList(player, view, id.isEmpty() ? null : id);
                    return null;
                }
                case RegionActionPacket.SELECT -> {
                    sendDetail(player, view.regions().get(id), view);
                    return null;
                }
                default -> {
                    return "Read-only: these are " + view.server() + "'s regions. Switch back to " + com.mrgregles.bsp_region_guard.protection.EdgeLinks.thisServer()
                            + " to change anything.";
                }
            }
        }
        try {
            switch (msg.action()) {
                case RegionActionPacket.VIEW_SERVER -> {
                    return RemoteViews.view(player, a);
                }
                case RegionActionPacket.WIPE_REQUEST, RegionActionPacket.WIPE_CONFIRM, RegionActionPacket.WIPE_RESTORE -> {
                    wipe(player, level, msg.action(), a);
                    return null;
                }
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
                    RegionEdits.Result made = RegionEdits.create(level, a, RegionEdits.selectionIn(player), 0);
                    Region created = made.ok() ? store.get(Region.validateId(a)) : null;
                    if (created != null) {
                        sendList(player, created.id(), false);
                        sendDetail(player, created);
                        if (!created.hasCorners()) { // no wand selection: click the corners on the plot map (D71, D74)
                            PlotMapSync.openCorners(player, created.id(), "Click the first corner of " + created.id() + ".");
                            return null;
                        }
                    }
                    return made.message();
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

    /** Edits that answer only when they fail: the screen redraws to show the change, so success needs no words. */
    private static final java.util.Set<String> QUIET = java.util.Set.of(RegionActionPacket.FLAG, RegionActionPacket.ADD_OWNER, RegionActionPacket.REMOVE_OWNER,
            RegionActionPacket.ADD_MEMBER, RegionActionPacket.REMOVE_MEMBER, RegionActionPacket.ADD_RANK, RegionActionPacket.REMOVE_RANK,
            RegionActionPacket.PRIORITY, RegionActionPacket.PARENT, RegionActionPacket.TYPE, RegionActionPacket.RENAME, RegionActionPacket.MINE_ADD,
            RegionActionPacket.MINE_INTERVAL, RegionActionPacket.TRIGGER_REMOVE, RegionActionPacket.SET_DATA, RegionActionPacket.EDGE_REFRESH,
            RegionActionPacket.RESIZE, RegionActionPacket.VFILL, RegionActionPacket.CORNERS, RegionActionPacket.PLOTS_PVP);

    /** One edit from the screen, through the same {@link RegionEdits} the commands use. */
    @Nullable
    private static String edit(ServerPlayer player, ServerLevel level, RegionStore store, Region r, String action, String a, String b) {
        RegionEdits.Result result = switch (action) {
            case RegionActionPacket.FLAG -> {
                Flag<?> flag = FlagRegistry.get(a);
                yield flag == null ? new RegionEdits.Result(false, "No flag named '" + a + "'.") : RegionEdits.flag(store, r, flag, b, new FlagContext(level, player));
            }
            case RegionActionPacket.ADD_OWNER, RegionActionPacket.REMOVE_OWNER, RegionActionPacket.ADD_MEMBER, RegionActionPacket.REMOVE_MEMBER -> {
                UUID who = resolvePlayer(player, a);
                if (who == null) {
                    yield new RegionEdits.Result(false, "No player called '" + a + "' is known to this server.");
                }
                boolean owner = action.equals(RegionActionPacket.ADD_OWNER) || action.equals(RegionActionPacket.REMOVE_OWNER);
                boolean add = action.equals(RegionActionPacket.ADD_OWNER) || action.equals(RegionActionPacket.ADD_MEMBER);
                RegionEdits.person(store, r, who, owner, add);
                yield new RegionEdits.Result(true, "");
            }
            case RegionActionPacket.ADD_RANK -> RegionEdits.rank(store, r, a, true);
            case RegionActionPacket.REMOVE_RANK -> RegionEdits.rank(store, r, a, false);
            case RegionActionPacket.PRIORITY -> RegionEdits.priority(store, r, Integer.parseInt(a));
            case RegionActionPacket.PARENT -> RegionEdits.parent(store, r, a);
            case RegionActionPacket.TYPE -> RegionEdits.type(level, store, r, a);
            case RegionActionPacket.RENAME -> RegionEdits.rename(store, r, a);
            case RegionActionPacket.DELETE -> RegionEdits.remove(store, r);
            case RegionActionPacket.REDEFINE -> RegionEdits.redefine(level, store, r, RegionEdits.selectionIn(player));
            case RegionActionPacket.RESIZE -> RegionEdits.resize(level, store, r, a);
            case RegionActionPacket.VFILL -> RegionEdits.verticalFill(level, store, r);
            case RegionActionPacket.CORNERS -> {
                if (r.isGlobal()) {
                    yield new RegionEdits.Result(false, "The world default has no corners.");
                }
                PlotMapSync.openCorners(player, r.id(), "Click the first corner of " + r.id() + ".");
                yield new RegionEdits.Result(true, "");
            }
            case RegionActionPacket.SELECT_BOUNDS -> RegionEdits.selectBounds(player, r);
            case RegionActionPacket.TELEPORT -> RegionEdits.teleport(player, store, r);
            case RegionActionPacket.MINE_CREATE -> RegionEdits.makeMine(store, r);
            case RegionActionPacket.MINE_ADD -> RegionEdits.mineBlock(store, r, RegionEdits.blockId(a), b.isEmpty() ? 0 : Integer.parseInt(b));
            case RegionActionPacket.MINE_INTERVAL -> RegionEdits.mineInterval(store, r, a.isEmpty() ? 0 : Integer.parseInt(a));
            case RegionActionPacket.MINE_SPAWN -> RegionEdits.mineSpawn(player, store, r, a.equals("clear"));
            case RegionActionPacket.MINE_SHELL -> RegionEdits.mineShell(level, store, r, a.isEmpty() ? 5 : Integer.parseInt(a));
            case RegionActionPacket.MINE_RESET -> RegionEdits.mineReset(level, store, r);
            case RegionActionPacket.MINE_FILL -> RegionEdits.mineFill(store, r, b, a);
            case RegionActionPacket.TRIGGER_ADD -> RegionEdits.addTrigger(player, store, r, a, b);
            case RegionActionPacket.TRIGGER_REMOVE -> RegionEdits.removeTrigger(store, r, Integer.parseInt(a));
            case RegionActionPacket.SET_DATA -> setData(level, store, r, a, b);
            case RegionActionPacket.PLOTS_PVP -> r.isGlobal() ? RegionEdits.plotsInPvp(level, a) : new RegionEdits.Result(false, "Plots in a PvP world are set on the world default.");
            case RegionActionPacket.BORDER_SET, RegionActionPacket.BORDER_OFF, RegionActionPacket.EDGE_LINK, RegionActionPacket.EDGE_UNLINK,
                 RegionActionPacket.EDGE_REFRESH -> border(player, level, r, action, a, b);
            default -> new RegionEdits.Result(false, "Unknown action " + action);
        };
        lastFailed = !result.ok();
        return result.ok() && QUIET.contains(action) || result.message().isEmpty() ? null : result.message();
    }

    /** The BORDER tab of the world default: the border, the edge links, and a fresh read of the other servers' worlds. */
    private static RegionEdits.Result border(ServerPlayer player, ServerLevel level, Region r, String action, String a, String b) {
        if (!r.isGlobal()) {
            return new RegionEdits.Result(false, "The border belongs to the world default.");
        }
        switch (action) {
            case RegionActionPacket.BORDER_SET -> {
                String[] centre = b.isBlank() ? new String[0] : b.trim().split("\\s+");
                return RegionEdits.border(level, Integer.parseInt(a), centre.length == 2 ? Double.parseDouble(centre[0]) : 0, centre.length == 2 ? Double.parseDouble(centre[1]) : 0);
            }
            case RegionActionPacket.BORDER_OFF -> {
                return RegionEdits.borderOff(level);
            }
            case RegionActionPacket.EDGE_LINK -> {
                String[] t = b.split("\\|");
                return t.length == 3 ? RegionEdits.edgeLink(level, a, t[0], t[1], t[2]) : new RegionEdits.Result(false, "Pick a world to link to.");
            }
            case RegionActionPacket.EDGE_UNLINK -> {
                return RegionEdits.edgeUnlink(level, a);
            }
            default -> { // EDGE_REFRESH: re-read the network, then show it
                String id = r.id();
                com.mrgregles.bsp_region_guard.protection.EdgeLinks.refresh(() -> {
                    Region now = RegionStore.get(level).get(id);
                    if (now != null && !player.hasDisconnected()) {
                        sendDetail(player, now);
                    }
                });
                return new RegionEdits.Result(true, "");
            }
        }
    }

    /** The SALE tab's fields: price, cost per block, plot sizes, the flags buyers may set. */
    private static RegionEdits.Result setData(ServerLevel level, RegionStore store, Region r, String key, String value) {
        if (key.equals(ClaimData.PLAYER_FLAGS)) {
            return RegionEdits.plotFlags(store, r, value);
        }
        if (key.equals(com.mrgregles.bsp_region_guard.claim.Prices.COIN_TIER)) {
            return RegionEdits.coinTier(store, r, value);
        }
        if (key.equals("look")) {
            return RegionEdits.plotLook(store, r, value);
        }
        if (key.equals(ClaimData.PLOT_LIMIT)) {
            return RegionEdits.plotLimit(store, r, value);
        }
        long n = value.isEmpty() ? 0 : Long.parseLong(value);
        return switch (key) {
            case ClaimData.PRICE -> RegionEdits.sale(level, store, r, n);
            case ClaimData.COST_PER_BLOCK -> RegionEdits.open(level, store, r, n);
            case ClaimData.MIN_SIZE -> RegionEdits.plotSizes(store, r, (int) n, ClaimData.maxSize(r));
            case ClaimData.MAX_SIZE -> RegionEdits.plotSizes(store, r, ClaimData.minSize(r), (int) n);
            default -> new RegionEdits.Result(false, "Unknown field " + key);
        };
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

    /** For the claim screen's TELEPORT HOME button. */
    static String teleport(ServerPlayer p, ServerLevel level, RegionStore store, Region r) {
        return RegionEdits.teleport(p, store, r).message();
    }

    /** For the map: the surface height at a column, or {@code Integer.MIN_VALUE} when the chunk is not loaded. */
    public static int surface(ServerLevel level, BlockPos pos) {
        return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) : Integer.MIN_VALUE;
    }
}
