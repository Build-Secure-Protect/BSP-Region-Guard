package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.ClaimResult;
import com.mrgregles.bsp_region_guard.api.ClaimService;
import com.mrgregles.bsp_region_guard.api.event.ClaimAbandonedEvent;
import com.mrgregles.bsp_region_guard.api.event.ClaimPurchasedEvent;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.compat.LuckPermsBridge;
import com.mrgregles.bsp_region_guard.protection.TotemRule;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** The claim rules. See {@link ClaimService} for the contract. */
public final class Claims implements ClaimService {
    public static final Claims INSTANCE = new Claims();

    private Claims() {
    }

    // ------------------------------------------------------------------ listings

    private static Listing listing(Region r) {
        return new Listing(r.id(), r.name(), r.bounds(), ClaimData.price(r), ClaimData.costPerBlock(r), ClaimData.minSize(r), ClaimData.maxSize(r),
                r.type() == RegionType.OPEN_FOR_BUYING);
    }

    @Override
    public List<Listing> plotsForSale(ServerLevel level) {
        List<Listing> out = new ArrayList<>();
        for (Region r : RegionStore.get(level).all()) {
            if (r.type() == RegionType.PLOT_FOR_SALE && r.bounds() != null) {
                out.add(listing(r));
            }
        }
        return out;
    }

    @Override
    public List<Listing> openAreas(ServerLevel level) {
        List<Listing> out = new ArrayList<>();
        for (Region r : RegionStore.get(level).all()) {
            if (r.type() == RegionType.OPEN_FOR_BUYING && r.bounds() != null) {
                out.add(listing(r));
            }
        }
        return out;
    }

    @Override
    public List<String> claimsOf(ServerLevel level, UUID player) {
        List<String> out = new ArrayList<>();
        for (Region r : RegionStore.get(level).all()) {
            if (r.type() == RegionType.CLAIM && r.isOwner(player)) {
                out.add(r.id());
            }
        }
        return out;
    }

    @Override
    public int claimLimit(ServerPlayer player) {
        int limit = RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_MAX_PER_PLAYER, 3);
        List<? extends String> byRank = RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_LIMITS_BY_RANK, List.of());
        if (byRank == null || byRank.isEmpty() || !LuckPermsBridge.isPresent()) {
            return limit;
        }
        Set<String> groups = LuckPermsBridge.groupsOf(player.getUUID());
        for (String entry : byRank) {
            int colon = entry.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String group = entry.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            try {
                int n = Integer.parseInt(entry.substring(colon + 1).trim());
                if (groups.contains(group)) {
                    limit = Math.max(limit, n);
                }
            } catch (NumberFormatException ignored) {
                // a bad entry in the config; skipped
            }
        }
        return limit;
    }

    @Override
    public boolean claimsAllowedIn(ServerLevel level) {
        return TotemRule.claimsAllowedIn(level);
    }

    // ------------------------------------------------------------------ plots for sale

    @Override
    public ClaimResult quotePlot(ServerPlayer player, String plotId) {
        ServerLevel level = player.serverLevel();
        Region plot = RegionStore.get(level).get(plotId);
        if (plot == null || plot.type() != RegionType.PLOT_FOR_SALE || plot.bounds() == null) {
            return ClaimResult.fail("That plot is not for sale.");
        }
        if (!claimsAllowedIn(level)) {
            return ClaimResult.fail("Claims are not allowed in this world.");
        }
        int price = ClaimData.price(plot);
        String limit = overLimit(player, level);
        if (limit != null) {
            return ClaimResult.fail(limit);
        }
        if (BspCore.coinValue(player) < price) {
            return new ClaimResult(false, "That plot costs " + price + " coins; you have " + BspCore.coinValue(player) + ".", plot.id(), price);
        }
        return ClaimResult.ok(plot.id() + " costs " + price + " coins (" + plot.bounds().sizeX() + " x " + plot.bounds().sizeZ() + ").", plot.id(), price);
    }

    @Override
    public ClaimResult purchasePlot(ServerPlayer player, String plotId) {
        ClaimResult quote = quotePlot(player, plotId);
        if (!quote.ok()) {
            return quote;
        }
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region plot = store.get(plotId);
        if (!BspCore.charge(player, quote.price())) {
            return ClaimResult.fail("You cannot afford that plot.");
        }
        plot.setType(RegionType.CLAIM);
        plot.addOwner(player.getUUID());
        plot.typeData().putInt(ClaimData.ORIGINAL_PRICE, quote.price());
        plot.typeData().remove(ClaimData.PRICE);
        plot.typeData().putInt(ClaimData.BOUGHT_FOR, quote.price());
        plot.typeData().putLong(ClaimData.BOUGHT_AT, System.currentTimeMillis());
        plot.typeData().putString(ClaimData.ORIGIN, ClaimData.ORIGIN_PLOT);
        store.touch(plot);
        MinecraftForge.EVENT_BUS.post(new ClaimPurchasedEvent(player, plot.id(), level.dimension(), quote.price(), false));
        BSPRegionGuard.LOGGER.info("{} bought plot {} in {} for {}", player.getGameProfile().getName(), plot.id(), level.dimension().location(), quote.price());
        return ClaimResult.ok("You bought " + plot.id() + " for " + quote.price() + " coins.", plot.id(), quote.price());
    }

    // ------------------------------------------------------------------ open areas

    /** The full-height box a ground outline stands for. */
    static Cuboid fullHeight(ServerLevel level, Cuboid ground) {
        return new Cuboid(ground.minX(), level.getMinBuildHeight(), ground.minZ(), ground.maxX(), level.getMaxBuildHeight() - 1, ground.maxZ());
    }

    @Nullable
    private String overLimit(ServerPlayer player, ServerLevel level) {
        int have = claimsOf(level, player.getUUID()).size(), limit = claimLimit(player);
        return have >= limit ? "You already own " + have + " claim" + (have == 1 ? "" : "s") + " here; your limit is " + limit + "." : null;
    }

    /** Checks an outline against an open area; returns the problem, or null when it fits. {@code except} is the claim being enlarged. */
    @Nullable
    private String checkOutline(ServerLevel level, Region open, Cuboid ground, @Nullable Region except) {
        Cuboid area = open.bounds();
        if (area == null || ground.minX() < area.minX() || ground.maxX() > area.maxX() || ground.minZ() < area.minZ() || ground.maxZ() > area.maxZ()) {
            return "Your outline must lie inside " + open.id() + ".";
        }
        int min = ClaimData.minSize(open) > 0 ? ClaimData.minSize(open) : RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_MIN_SIZE, 5);
        int max = ClaimData.maxSize(open) > 0 ? ClaimData.maxSize(open) : RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_MAX_SIZE, 64);
        if (ground.sizeX() < min || ground.sizeZ() < min) {
            return "A plot must be at least " + min + " x " + min + " blocks.";
        }
        if (ground.sizeX() > max || ground.sizeZ() > max) {
            return "A plot may be at most " + max + " x " + max + " blocks.";
        }
        for (Region other : RegionStore.get(level).intersecting(fullHeight(level, ground))) {
            if (other != open && other != except && (other.type() == RegionType.CLAIM || other.type() == RegionType.PLOT_FOR_SALE)) {
                return "That overlaps " + other.id() + ".";
            }
        }
        return null;
    }

    @Override
    public ClaimResult quoteOpen(ServerPlayer player, String openId, Cuboid ground) {
        ServerLevel level = player.serverLevel();
        Region open = RegionStore.get(level).get(openId);
        if (open == null || open.type() != RegionType.OPEN_FOR_BUYING || open.bounds() == null) {
            return ClaimResult.fail("That is not an open area.");
        }
        if (!claimsAllowedIn(level)) {
            return ClaimResult.fail("Claims are not allowed in this world.");
        }
        String limit = overLimit(player, level);
        if (limit != null) {
            return ClaimResult.fail(limit);
        }
        String problem = checkOutline(level, open, ground, null);
        if (problem != null) {
            return ClaimResult.fail(problem);
        }
        int price = (int) Math.min(Integer.MAX_VALUE, ground.area() * ClaimData.costPerBlock(open));
        if (BspCore.coinValue(player) < price) {
            return new ClaimResult(false, "That plot costs " + price + " coins (" + ground.area() + " blocks at " + ClaimData.costPerBlock(open) + "); you have " + BspCore.coinValue(player) + ".", null, price);
        }
        return ClaimResult.ok(ground.sizeX() + " x " + ground.sizeZ() + " blocks at " + ClaimData.costPerBlock(open) + " each: " + price + " coins.", null, price);
    }

    @Override
    public ClaimResult purchaseOpen(ServerPlayer player, String openId, Cuboid ground) {
        ClaimResult quote = quoteOpen(player, openId, ground);
        if (!quote.ok()) {
            return quote;
        }
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region open = store.get(openId);
        if (!BspCore.charge(player, quote.price())) {
            return ClaimResult.fail("You cannot afford that plot.");
        }
        Region claim = new Region(freeId(store, open, player), level.dimension(), fullHeight(level, ground), RegionType.CLAIM);
        claim.setName(player.getGameProfile().getName() + "'s plot");
        claim.setPriority(open.priority() + 1);
        claim.setParentId(open.id());
        claim.addOwner(player.getUUID());
        claim.typeData().putInt(ClaimData.BOUGHT_FOR, quote.price());
        claim.typeData().putLong(ClaimData.BOUGHT_AT, System.currentTimeMillis());
        claim.typeData().putString(ClaimData.ORIGIN, ClaimData.ORIGIN_OPEN);
        store.add(claim);
        MinecraftForge.EVENT_BUS.post(new ClaimPurchasedEvent(player, claim.id(), level.dimension(), quote.price(), false));
        BSPRegionGuard.LOGGER.info("{} bought {} in {} ({}) for {}", player.getGameProfile().getName(), claim.id(), open.id(), level.dimension().location(), quote.price());
        return ClaimResult.ok("You bought " + claim.id() + " for " + quote.price() + " coins.", claim.id(), quote.price());
    }

    /** {@code <area>-<player>-<n>}, kept within the id length limit. */
    static String freeId(RegionStore store, Region open, ServerPlayer player) {
        String base = open.id().substring(0, Math.min(10, open.id().length())) + "-"
                + player.getGameProfile().getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "").substring(0, Math.min(12, player.getGameProfile().getName().length()));
        for (int n = 1; ; n++) {
            String id = base + "-" + n;
            if (!store.has(id)) {
                return id;
            }
        }
    }

    @Override
    public ClaimResult enlarge(ServerPlayer player, String claimId, Cuboid ground) {
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region claim = store.get(claimId);
        if (claim == null || claim.type() != RegionType.CLAIM || claim.bounds() == null) {
            return ClaimResult.fail("No claim named '" + claimId + "'.");
        }
        if (!claim.isOwner(player.getUUID()) && !BspCore.isAdmin(player)) {
            return ClaimResult.fail("Only the owner can enlarge " + claimId + ".");
        }
        if (!ClaimData.ORIGIN_OPEN.equals(ClaimData.origin(claim)) || claim.parentId() == null) {
            return ClaimResult.fail("Only plots outlined in an open area can be enlarged.");
        }
        Region open = store.get(claim.parentId());
        if (open == null || open.type() != RegionType.OPEN_FOR_BUYING) {
            return ClaimResult.fail("The open area this plot was bought in no longer exists.");
        }
        Cuboid old = claim.bounds();
        if (ground.minX() > old.minX() || ground.maxX() < old.maxX() || ground.minZ() > old.minZ() || ground.maxZ() < old.maxZ()) {
            return ClaimResult.fail("The new outline must contain the whole plot.");
        }
        String problem = checkOutline(level, open, ground, claim);
        if (problem != null) {
            return ClaimResult.fail(problem);
        }
        long added = ground.area() - old.area();
        int price = (int) Math.min(Integer.MAX_VALUE, added * ClaimData.costPerBlock(open));
        if (added <= 0) {
            return ClaimResult.fail("The new outline adds no ground.");
        }
        if (!BspCore.charge(player, price)) {
            return new ClaimResult(false, "Adding " + added + " blocks costs " + price + " coins; you have " + BspCore.coinValue(player) + ".", claim.id(), price);
        }
        claim.setBounds(fullHeight(level, ground));
        claim.typeData().putInt(ClaimData.BOUGHT_FOR, ClaimData.boughtFor(claim) + price);
        store.touch(claim);
        MinecraftForge.EVENT_BUS.post(new ClaimPurchasedEvent(player, claim.id(), level.dimension(), price, true));
        return ClaimResult.ok(claim.id() + " is now " + ground.sizeX() + " x " + ground.sizeZ() + "; you paid " + price + " coins for the extra ground.", claim.id(), price);
    }

    @Override
    public ClaimResult abandon(ServerPlayer player, String claimId) {
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region claim = store.get(claimId);
        if (claim == null || claim.type() != RegionType.CLAIM) {
            return ClaimResult.fail("No claim named '" + claimId + "'.");
        }
        boolean admin = BspCore.isAdmin(player);
        if (!claim.isOwner(player.getUUID()) && !admin) {
            return ClaimResult.fail("Only the owner can give up " + claimId + ".");
        }
        int refund = ClaimData.boughtFor(claim) * RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_REFUND_PERCENT, 50) / 100;
        ServerPlayer payee = player;
        if (admin && !claim.isOwner(player.getUUID())) {
            payee = null; // an admin removing someone else's claim: the owner is refunded if online
            for (UUID owner : claim.owners()) {
                ServerPlayer online = player.server.getPlayerList().getPlayer(owner);
                if (online != null) {
                    payee = online;
                    break;
                }
            }
        }
        if (refund > 0 && payee != null) {
            BspCore.refund(payee, refund);
        }
        if (ClaimData.ORIGIN_PLOT.equals(ClaimData.origin(claim))) {
            // an admin plot goes back on sale at its original price
            for (UUID u : new ArrayList<>(claim.owners())) {
                claim.removeOwner(u);
            }
            for (UUID u : new ArrayList<>(claim.members())) {
                claim.removeMember(u);
            }
            claim.setType(RegionType.PLOT_FOR_SALE);
            claim.typeData().putInt(ClaimData.PRICE, claim.typeData().getInt(ClaimData.ORIGINAL_PRICE));
            claim.typeData().remove(ClaimData.BOUGHT_FOR);
            claim.typeData().remove(ClaimData.BOUGHT_AT);
            claim.typeData().remove(ClaimData.ORIGIN);
            store.touch(claim);
        } else {
            store.remove(claim.id());
        }
        MinecraftForge.EVENT_BUS.post(new ClaimAbandonedEvent(player, claimId, level.dimension(), refund));
        return ClaimResult.ok("You gave up " + claimId + (refund > 0 && payee != null ? " and got " + refund + " coins back." : "."), claimId, refund);
    }

    // ------------------------------------------------------------------ owner edits

    /** Whether a player may change a claim's members and flags: its owner, or an admin. */
    public static boolean mayManage(ServerPlayer player, Region claim) {
        return claim.type() == RegionType.CLAIM && (claim.isOwner(player.getUUID()) || BspCore.isAdmin(player));
    }

    /** Flags a claim owner may set on a claim: the plot's own list, its area's, or the config's (D27). */
    public static boolean playerMaySet(RegionStore store, Region claim, String flagName) {
        return ClaimFlags.mayOwnerSet(store, claim, flagName);
    }

    /** The config default list, for suggestions. */
    public static boolean playerMaySet(String flagName) {
        List<? extends String> allowed = RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_PLAYER_FLAGS, List.of());
        return allowed != null && allowed.contains(flagName);
    }

    /**
     * An owner setting a flag on their claim: must be allowed for this plot, and must not make a claim that holds a
     * placed Shatter Totem hinder a steal (D25). {@code value} is the typed text; empty clears.
     */
    public ClaimResult setPlayerFlag(ServerPlayer player, String claimId, String flagName, String value) {
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region claim = store.get(claimId);
        if (claim == null || !mayManage(player, claim)) {
            return ClaimResult.fail("That is not a claim of yours.");
        }
        com.mrgregles.bsp_region_guard.flag.Flag<?> flag = com.mrgregles.bsp_region_guard.flag.FlagRegistry.get(flagName);
        if (flag == null || !ClaimFlags.mayOwnerSet(store, claim, flag.name())) {
            return ClaimResult.fail("You may set these flags on this claim: " + String.join(", ", ClaimFlags.allowedFor(store, claim)));
        }
        try {
            Object parsed = value.isBlank() ? null : parse(flag, value.trim(), new com.mrgregles.bsp_region_guard.flag.FlagContext(level, player));
            if (ClaimFlags.wouldHinder(flag, parsed) && ClaimFlags.holdsTotem(level, claim)) {
                return ClaimResult.fail("A Shatter Totem stands in this claim; a rule that could stop a steal is not allowed while it does.");
            }
            setRaw(claim, flag, parsed);
            store.touch(claim);
            return ClaimResult.ok(claim.id() + " " + flag.name() + (parsed == null ? " cleared." : " set."), claim.id(), 0);
        } catch (IllegalArgumentException e) {
            return ClaimResult.fail(flag.name() + ": " + e.getMessage());
        }
    }

    private static <T> T parse(com.mrgregles.bsp_region_guard.flag.Flag<T> flag, String value, com.mrgregles.bsp_region_guard.flag.FlagContext ctx) {
        return flag.parse(value, ctx);
    }

    @SuppressWarnings("unchecked")
    private static <T> void setRaw(Region r, com.mrgregles.bsp_region_guard.flag.Flag<T> flag, @Nullable Object value) {
        r.setFlag(flag, (T) value);
    }
}
