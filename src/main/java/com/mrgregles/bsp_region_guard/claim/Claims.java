package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.ClaimResult;
import com.mrgregles.bsp_region_guard.api.ClaimService;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.api.event.ClaimAbandonedEvent;
import com.mrgregles.bsp_region_guard.api.event.ClaimPurchasedEvent;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.compat.LuckPermsBridge;
import com.mrgregles.bsp_region_guard.protection.TotemRule;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
        int own = ClaimData.plotLimit(RegionStore.get(player.serverLevel()).global());
        int limit = own >= 0 ? own : RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_MAX_PER_PLAYER, 3);
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

    @Override
    public boolean paysInCoins() {
        return Prices.coins();
    }

    @Override
    public String priceText(ServerLevel level, String regionId, long amount) {
        return Prices.text(Prices.tier(RegionStore.get(level).get(regionId)), amount);
    }

    // ------------------------------------------------------------------ money in flight

    /** How long a charge may stay unanswered before its hold stops blocking others (the bank's database is down). */
    private static final long HOLD_MS = 60_000;
    private static final String BUSY = "Your last purchase is still going through; try again in a moment.";

    /** A plot or outline held for a buyer while the bank answers their charge. */
    private record Hold(ResourceKey<Level> dimension, @Nullable String plotId, @Nullable Cuboid ground, UUID buyer, long since) {
        boolean live() {
            return System.currentTimeMillis() - since < HOLD_MS;
        }
    }

    /** One charge in flight per player: buyer to their hold. Server thread only. */
    private final Map<UUID, Hold> holds = new HashMap<>();

    private boolean busy(ServerPlayer player) {
        Hold h = holds.get(player.getUUID());
        return h != null && h.live();
    }

    /** Why another buyer's charge in flight keeps this plot or outline from {@code buyer} now, or null. */
    @Nullable
    private String heldByOther(ServerLevel level, @Nullable String plotId, @Nullable Cuboid ground, UUID buyer) {
        for (Hold h : holds.values()) {
            if (h.buyer().equals(buyer) || !h.live() || !h.dimension().equals(level.dimension())) {
                continue;
            }
            if (plotId != null && plotId.equals(h.plotId())) {
                return "Someone else is buying " + plotId + " right now.";
            }
            if (ground != null && h.ground() != null && fullHeight(level, ground).intersects(fullHeight(level, h.ground()))) {
                return "Someone else is buying ground there right now.";
            }
        }
        return null;
    }

    /**
     * Charges the bank, then on the server thread checks again that the purchase still stands and completes it; if it
     * no longer stands (someone else was quicker, an admin changed the price) the money is paid back.
     */
    private void pay(ServerPlayer player, Hold hold, @Nullable String tier, long price, String reason, Supplier<String> recheck, Supplier<ClaimResult> complete,
                     Consumer<ClaimResult> then) {
        if (tier != null) {
            payCoins(player, hold, tier, price, reason, recheck, complete, then);
            return;
        }
        UUID buyer = player.getUUID();
        holds.put(buyer, hold);
        Consumer<Boolean> answered = paid -> {
            holds.remove(buyer, hold);
            if (!paid) {
                then.accept(new ClaimResult(false, "The bank did not take " + BspCore.money(price)
                        + ": your balance is too low, or the bank could not be reached. Nothing was bought.", hold.plotId(), price));
                return;
            }
            ClaimResult result;
            try {
                String problem = recheck.get();
                if (problem != null) {
                    payBack(player.server, buyer, price, "refund, " + reason);
                    result = new ClaimResult(false, problem + " The " + BspCore.money(price) + " went back to your bank.", hold.plotId(), price);
                } else {
                    result = complete.get();
                }
            } catch (RuntimeException e) {
                // the money is taken but the claim could not be made: never keep it
                BSPRegionGuard.LOGGER.error("Completing {} for {} failed after payment; paying back", reason, player.getGameProfile().getName(), e);
                payBack(player.server, buyer, price, "refund, " + reason);
                result = new ClaimResult(false, "Something went wrong; the " + BspCore.money(price) + " went back to your bank.", hold.plotId(), price);
            }
            then.accept(result);
        };
        try {
            BspCore.charge(player, price, reason, answered);
        } catch (RuntimeException e) {
            holds.remove(buyer, hold);
            BSPRegionGuard.LOGGER.warn("Charging {} for {} failed: {}", player.getGameProfile().getName(), reason, e.toString());
            then.accept(ClaimResult.fail("The bank is not available right now. Nothing was bought."));
        }
    }

    /**
     * Coin mode (D96, D97): the coins are taken from the inventory at once, so the purchase is checked and completed in
     * the same tick; if completing fails the coins go back.
     */
    private void payCoins(ServerPlayer player, Hold hold, String tier, long price, String reason, Supplier<String> recheck,
                          Supplier<ClaimResult> complete, Consumer<ClaimResult> then) {
        String problem = recheck.get();
        if (problem != null) {
            then.accept(new ClaimResult(false, problem, hold.plotId(), price));
            return;
        }
        long worth = Prices.worth(tier, price);
        if (!BspCore.payCoins(player, worth)) {
            then.accept(new ClaimResult(false, "You need " + Prices.text(tier, price) + " (or " + Prices.worthText(worth) + ") in your inventory; "
                    + Prices.holding(player, tier) + ". Nothing was bought.", hold.plotId(), price));
            return;
        }
        ClaimResult result;
        try {
            result = complete.get();
        } catch (RuntimeException e) {
            BSPRegionGuard.LOGGER.error("Completing {} for {} failed after payment in coins; giving them back", reason, player.getGameProfile().getName(), e);
            BspCore.giveCoins(player, worth);
            result = new ClaimResult(false, "Something went wrong; your coins were given back.", hold.plotId(), price);
        }
        then.accept(result);
    }

    /** Records a payment on a claim in the currency it was made in (pieces, or coins as copper units). */
    private static void addPaid(Region claim, @Nullable String tier, long price) {
        if (tier == null) {
            claim.typeData().putLong(ClaimData.BOUGHT_FOR, saturatedAdd(ClaimData.boughtFor(claim), price));
        } else {
            claim.typeData().putLong(ClaimData.BOUGHT_COINS, saturatedAdd(ClaimData.boughtCoins(claim), Prices.worth(tier, price)));
        }
    }

    static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    private static void payBack(MinecraftServer server, UUID player, long pieces, String reason) {
        BspCore.credit(server, player, pieces, reason, ok -> {
            if (!ok) {
                BSPRegionGuard.LOGGER.error("Could not pay {} back to {} ({}); pay it by hand", BspCore.money(pieces), player, reason);
            }
        });
    }

    /** {@code blocks * perBlock}, held at {@link Long#MAX_VALUE} rather than overflowing. */
    static long cost(long blocks, long perBlock) {
        try {
            return Math.multiplyExact(blocks, perBlock);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static ClaimResult shortOf(String what, @Nullable String tier, long price, ServerPlayer player, @Nullable String claimId) {
        return new ClaimResult(false, what + " costs " + Prices.text(tier, price) + "; " + Prices.holding(player, tier) + ".", claimId, price);
    }

    // ------------------------------------------------------------------ plots for sale

    /** Why this player cannot have this plot now, or null. Money is not checked here. */
    @Nullable
    private String plotProblem(ServerLevel level, ServerPlayer player, @Nullable Region plot, String plotId) {
        if (plot == null || plot.type() != RegionType.PLOT_FOR_SALE || plot.bounds() == null) {
            return "That plot is not for sale.";
        }
        if (!claimsAllowedIn(level)) {
            return "Claims are not allowed in this world.";
        }
        String held = heldByOther(level, plotId, null, player.getUUID());
        return held != null ? held : overLimit(player, level, plot.bounds(), plot);
    }

    @Override
    public ClaimResult quotePlot(ServerPlayer player, String plotId) {
        ServerLevel level = player.serverLevel();
        Region plot = RegionStore.get(level).get(plotId);
        String problem = plotProblem(level, player, plot, plotId);
        if (problem != null) {
            return ClaimResult.fail(problem);
        }
        long price = ClaimData.price(plot);
        String tier = Prices.tier(plot);
        if (!Prices.affordable(player, tier, price)) {
            return shortOf("That plot", tier, price, player, plot.id());
        }
        return ClaimResult.ok(plot.id() + " costs " + Prices.text(tier, price) + " (" + plot.bounds().sizeX() + " x " + plot.bounds().sizeZ() + ").", plot.id(), price);
    }

    @Override
    public void purchasePlot(ServerPlayer player, String plotId, Consumer<ClaimResult> then) {
        if (busy(player)) {
            then.accept(ClaimResult.fail(BUSY));
            return;
        }
        ClaimResult quote = quotePlot(player, plotId);
        if (!quote.ok()) {
            then.accept(quote);
            return;
        }
        ServerLevel level = player.serverLevel();
        long price = quote.price();
        String tier = Prices.tier(RegionStore.get(level).get(plotId));
        pay(player, new Hold(level.dimension(), plotId, null, player.getUUID(), System.currentTimeMillis()), tier, price,
                "plot " + plotId + " in " + level.dimension().location(),
                () -> {
                    Region plot = RegionStore.get(level).get(plotId);
                    String problem = plotProblem(level, player, plot, plotId);
                    return problem == null && (ClaimData.price(plot) != price || !java.util.Objects.equals(Prices.tier(plot), tier)) ? "The price of " + plotId + " changed." : problem;
                },
                () -> completePlot(level, player, plotId, tier, price), then);
    }

    private ClaimResult completePlot(ServerLevel level, ServerPlayer player, String plotId, @Nullable String tier, long price) {
        RegionStore store = RegionStore.get(level);
        Region plot = store.get(plotId);
        plot.setType(RegionType.CLAIM);
        plot.addOwner(player.getUUID());
        plot.typeData().putLong(ClaimData.ORIGINAL_PRICE, price);
        plot.typeData().remove(ClaimData.PRICE);
        plot.typeData().remove(ClaimData.BOUGHT_FOR);
        plot.typeData().remove(ClaimData.BOUGHT_COINS);
        addPaid(plot, tier, price);
        plot.typeData().putLong(ClaimData.BOUGHT_AT, System.currentTimeMillis());
        plot.typeData().putString(ClaimData.ORIGIN, ClaimData.ORIGIN_PLOT);
        store.touch(plot);
        MinecraftForge.EVENT_BUS.post(new ClaimPurchasedEvent(player, plot.id(), level.dimension(), price, false, plot.bounds()));
        BSPRegionGuard.LOGGER.info("{} bought plot {} in {} for {}", player.getGameProfile().getName(), plot.id(), level.dimension().location(), Prices.text(tier, price));
        return ClaimResult.ok("You bought " + plot.id() + " for " + Prices.text(tier, price) + ".", plot.id(), price);
    }

    // ------------------------------------------------------------------ open areas

    /** The full-height box a ground outline stands for. */
    static Cuboid fullHeight(ServerLevel level, Cuboid ground) {
        return new Cuboid(ground.minX(), level.getMinBuildHeight(), ground.minZ(), ground.maxX(), level.getMaxBuildHeight() - 1, ground.maxZ());
    }

    /**
     * Why one more plot on this ground would put the player over a limit, or null (D100, D101): the world's limit, then
     * every region whose ground holds the new plot's ground and has its own limit. Plots count by their ground (x and z),
     * so a full-height plot counts inside a region of any height. {@code plot} is the plot being bought (never its own limit).
     */
    @Nullable
    private String overLimit(ServerPlayer player, ServerLevel level, Cuboid ground, @Nullable Region plot) {
        List<Region> owned = new ArrayList<>();
        for (Region r : RegionStore.get(level).all()) {
            if (r.type() == RegionType.CLAIM && r.isOwner(player.getUUID()) && r.bounds() != null) {
                owned.add(r);
            }
        }
        int have = owned.size(), limit = claimLimit(player);
        if (have >= limit) {
            return "You already own " + have + " claim" + (have == 1 ? "" : "s") + " here; your limit is " + limit + ".";
        }
        for (Region r : RegionStore.get(level).all()) {
            int max = ClaimData.plotLimit(r);
            if (max < 0 || r == plot || r.isGlobal() || !r.hasCorners() || !onGround(r.bounds(), ground)) {
                continue;
            }
            long inside = owned.stream().filter(c -> c != r && onGround(r.bounds(), c.bounds())).count();
            if (inside >= max) {
                return max == 0 ? "Nobody may own a plot inside " + r.name() + "."
                        : "You already own " + inside + " plot" + (inside == 1 ? "" : "s") + " inside " + r.name() + "; its limit is " + max + ".";
            }
        }
        return null;
    }

    /** Whether {@code inner}'s ground (x and z) lies wholly within {@code outer}'s. */
    static boolean onGround(Cuboid outer, Cuboid inner) {
        return inner.minX() >= outer.minX() && inner.maxX() <= outer.maxX() && inner.minZ() >= outer.minZ() && inner.maxZ() <= outer.maxZ();
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

    /** Why this player cannot buy this outline in this open area now, or null. Money is not checked here. */
    @Nullable
    private String openProblem(ServerLevel level, ServerPlayer player, @Nullable Region open, Cuboid ground) {
        if (open == null || open.type() != RegionType.OPEN_FOR_BUYING || open.bounds() == null) {
            return "That is not an open area.";
        }
        if (!claimsAllowedIn(level)) {
            return "Claims are not allowed in this world.";
        }
        String limit = overLimit(player, level, ground, null);
        if (limit != null) {
            return limit;
        }
        String held = heldByOther(level, null, ground, player.getUUID());
        return held != null ? held : checkOutline(level, open, ground, null);
    }

    @Override
    public ClaimResult quoteOpen(ServerPlayer player, String openId, Cuboid ground) {
        ServerLevel level = player.serverLevel();
        Region open = RegionStore.get(level).get(openId);
        String problem = openProblem(level, player, open, ground);
        if (problem != null) {
            return ClaimResult.fail(problem);
        }
        long perBlock = ClaimData.costPerBlock(open), price = cost(ground.area(), perBlock);
        String tier = Prices.tier(open);
        if (!Prices.affordable(player, tier, price)) {
            return shortOf("That plot (" + ground.area() + " blocks at " + Prices.text(tier, perBlock) + ")", tier, price, player, null);
        }
        return ClaimResult.ok(ground.sizeX() + " x " + ground.sizeZ() + " blocks at " + Prices.text(tier, perBlock) + " each: " + Prices.text(tier, price) + ".", null, price);
    }

    @Override
    public void purchaseOpen(ServerPlayer player, String openId, Cuboid ground, Consumer<ClaimResult> then) {
        if (busy(player)) {
            then.accept(ClaimResult.fail(BUSY));
            return;
        }
        ClaimResult quote = quoteOpen(player, openId, ground);
        if (!quote.ok()) {
            then.accept(quote);
            return;
        }
        ServerLevel level = player.serverLevel();
        long price = quote.price();
        String tier = Prices.tier(RegionStore.get(level).get(openId));
        pay(player, new Hold(level.dimension(), null, ground, player.getUUID(), System.currentTimeMillis()), tier, price,
                "plot in " + openId + " (" + level.dimension().location() + ")",
                () -> {
                    Region open = RegionStore.get(level).get(openId);
                    String problem = openProblem(level, player, open, ground);
                    return problem == null && (cost(ground.area(), ClaimData.costPerBlock(open)) != price || !java.util.Objects.equals(Prices.tier(open), tier))
                            ? "The cost per block in " + openId + " changed." : problem;
                },
                () -> completeOpen(level, player, openId, ground, tier, price), then);
    }

    private ClaimResult completeOpen(ServerLevel level, ServerPlayer player, String openId, Cuboid ground, @Nullable String tier, long price) {
        RegionStore store = RegionStore.get(level);
        Region open = store.get(openId);
        Region claim = new Region(freeId(store, open, player), level.dimension(), fullHeight(level, ground), RegionType.CLAIM);
        claim.setName(player.getGameProfile().getName() + "'s plot");
        claim.setPriority(open.priority() + 1);
        claim.setParentId(open.id());
        claim.addOwner(player.getUUID());
        addPaid(claim, tier, price);
        claim.typeData().putLong(ClaimData.BOUGHT_AT, System.currentTimeMillis());
        claim.typeData().putString(ClaimData.ORIGIN, ClaimData.ORIGIN_OPEN);
        store.add(claim);
        MinecraftForge.EVENT_BUS.post(new ClaimPurchasedEvent(player, claim.id(), level.dimension(), price, false, claim.bounds()));
        BSPRegionGuard.LOGGER.info("{} bought {} in {} ({}) for {}", player.getGameProfile().getName(), claim.id(), open.id(), level.dimension().location(), Prices.text(tier, price));
        return ClaimResult.ok("You bought " + claim.id() + " for " + Prices.text(tier, price) + ".", claim.id(), price);
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

    // ------------------------------------------------------------------ enlarge

    /** Why this player cannot grow this claim to {@code ground} now, or null. Money is not checked here. */
    @Nullable
    private String enlargeProblem(ServerLevel level, ServerPlayer player, @Nullable Region claim, String claimId, Cuboid ground) {
        RegionStore store = RegionStore.get(level);
        if (claim == null || claim.type() != RegionType.CLAIM || claim.bounds() == null) {
            return "No claim named '" + claimId + "'.";
        }
        if (!claim.isOwner(player.getUUID()) && !BspCore.isAdmin(player)) {
            return "Only the owner can enlarge " + claimId + ".";
        }
        if (!ClaimData.ORIGIN_OPEN.equals(ClaimData.origin(claim)) || claim.parentId() == null) {
            return "Only plots outlined in an open area can be enlarged.";
        }
        Region open = store.get(claim.parentId());
        if (open == null || open.type() != RegionType.OPEN_FOR_BUYING) {
            return "The open area this plot was bought in no longer exists.";
        }
        Cuboid old = claim.bounds();
        if (ground.minX() > old.minX() || ground.maxX() < old.maxX() || ground.minZ() > old.minZ() || ground.maxZ() < old.maxZ()) {
            return "The new outline must contain the whole plot.";
        }
        if (ground.area() <= old.area()) {
            return "The new outline adds no ground.";
        }
        String held = heldByOther(level, claimId, ground, player.getUUID());
        return held != null ? held : checkOutline(level, open, ground, claim);
    }

    /** The price of the ground {@code ground} adds to a claim; only valid when {@link #enlargeProblem} found nothing. */
    private static long enlargeCost(RegionStore store, Region claim, Cuboid ground) {
        return cost(ground.area() - claim.bounds().area(), ClaimData.costPerBlock(store.get(claim.parentId())));
    }

    @Override
    public void enlarge(ServerPlayer player, String claimId, Cuboid ground, Consumer<ClaimResult> then) {
        if (busy(player)) {
            then.accept(ClaimResult.fail(BUSY));
            return;
        }
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region claim = store.get(claimId);
        String problem = enlargeProblem(level, player, claim, claimId, ground);
        if (problem != null) {
            then.accept(ClaimResult.fail(problem));
            return;
        }
        long added = ground.area() - claim.bounds().area(), price = enlargeCost(store, claim, ground);
        String tier = Prices.tier(store.get(claim.parentId()));
        if (!Prices.affordable(player, tier, price)) {
            then.accept(shortOf("Adding " + added + " blocks", tier, price, player, claimId));
            return;
        }
        pay(player, new Hold(level.dimension(), claimId, ground, player.getUUID(), System.currentTimeMillis()), tier, price,
                "enlarging " + claimId + " in " + level.dimension().location(),
                () -> {
                    Region now = RegionStore.get(level).get(claimId);
                    String p = enlargeProblem(level, player, now, claimId, ground);
                    return p == null && enlargeCost(RegionStore.get(level), now, ground) != price ? "The cost per block changed." : p;
                },
                () -> completeEnlarge(level, player, claimId, ground, tier, price), then);
    }

    private ClaimResult completeEnlarge(ServerLevel level, ServerPlayer player, String claimId, Cuboid ground, @Nullable String tier, long price) {
        RegionStore store = RegionStore.get(level);
        Region claim = store.get(claimId);
        claim.setBounds(fullHeight(level, ground));
        addPaid(claim, tier, price);
        store.touch(claim);
        MinecraftForge.EVENT_BUS.post(new ClaimPurchasedEvent(player, claim.id(), level.dimension(), price, true, claim.bounds()));
        return ClaimResult.ok(claim.id() + " is now " + ground.sizeX() + " x " + ground.sizeZ() + "; you paid " + Prices.text(tier, price) + " for the extra ground.", claim.id(), price);
    }

    // ------------------------------------------------------------------ abandon

    @Override
    public ClaimResult abandon(ServerPlayer player, String claimId) {
        ServerLevel level = player.serverLevel();
        RegionStore store = RegionStore.get(level);
        Region claim = store.get(claimId);
        if (claim == null || claim.type() != RegionType.CLAIM) {
            return ClaimResult.fail("No claim named '" + claimId + "'.");
        }
        boolean owner = claim.isOwner(player.getUUID());
        if (!owner && !BspCore.isAdmin(player)) {
            return ClaimResult.fail("Only the owner can give up " + claimId + ".");
        }
        Cuboid bounds = claim.bounds();
        int percent = RegionGuardConfig.getOr(RegionGuardConfig.CLAIM_REFUND_PERCENT, 50);
        long refund = ClaimData.boughtFor(claim) / 100 * percent + ClaimData.boughtFor(claim) % 100 * percent / 100;
        long coinRefund = ClaimData.boughtCoins(claim) / 100 * percent + ClaimData.boughtCoins(claim) % 100 * percent / 100;
        // an admin removing someone else's claim: the refund goes to the owner's bank (coins: at their next login), online or not
        UUID payee = owner ? player.getUUID() : claim.owners().stream().findFirst().orElse(null);
        if (refund > 0 && payee != null) {
            payBack(player.server, payee, refund, "gave up " + claimId + " in " + level.dimension().location());
        } else {
            refund = 0;
        }
        if (coinRefund > 0 && payee != null) {
            PendingCoins.refund(player.server, payee, coinRefund);
        } else {
            coinRefund = 0;
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
            claim.typeData().putLong(ClaimData.PRICE, claim.typeData().getLong(ClaimData.ORIGINAL_PRICE));
            claim.typeData().remove(ClaimData.BOUGHT_FOR);
            claim.typeData().remove(ClaimData.BOUGHT_COINS);
            claim.typeData().remove(ClaimData.BOUGHT_AT);
            claim.typeData().remove(ClaimData.ORIGIN);
            store.touch(claim);
        } else {
            store.remove(claim.id());
        }
        MinecraftForge.EVENT_BUS.post(new ClaimAbandonedEvent(player, claimId, level.dimension(), refund > 0 ? refund : coinRefund, bounds));
        String back = refund <= 0 ? "" : (owner ? "; your bank gets " : "; the owner's bank gets ") + BspCore.money(refund) + " back";
        if (coinRefund > 0) {
            back += (owner ? "; you get " : "; the owner gets ") + Prices.worthText(coinRefund) + " back";
        }
        return ClaimResult.ok("You gave up " + claimId + back + ".", claimId, refund > 0 ? refund : coinRefund);
    }

    // ------------------------------------------------------------------ the Land Office (CR-1 to CR-3)

    /** D46: a finished wand outline opens the Land Office for this player (not for admins drawing regions with bypass on). */
    public static boolean landOfficeOnOutline(ServerPlayer player) {
        return !com.mrgregles.bsp_region_guard.region.RegionQuery.bypasses(player) && TotemRule.claimsAllowedIn(player.serverLevel());
    }

    /** D47: players buy and enlarge at a Land Office; admins keep the commands for support and testing. */
    public static boolean buysByCommand(ServerPlayer player) {
        return BspCore.isAdmin(player);
    }

    @Override
    @Nullable
    public Cuboid selection(ServerPlayer player) {
        var sel = com.mrgregles.bsp_region_guard.selection.Selections.of(player);
        Cuboid box = sel.cuboid();
        return box != null && player.serverLevel().dimension().equals(sel.dimension()) ? box : null;
    }

    @Override
    public List<Nearby> claimsNear(ServerLevel level, Cuboid area) {
        List<Nearby> out = new ArrayList<>();
        var cache = level.getServer().getProfileCache();
        for (Region r : RegionStore.get(level).all()) {
            Cuboid b = r.bounds();
            boolean kind = r.type() == RegionType.CLAIM || r.type() == RegionType.PLOT_FOR_SALE || r.type() == RegionType.OPEN_FOR_BUYING;
            // on the ground: claims are full height, so only x and z decide
            if (!kind || b == null || b.maxX() < area.minX() || b.minX() > area.maxX() || b.maxZ() < area.minZ() || b.minZ() > area.maxZ()) {
                continue;
            }
            String owner = "";
            UUID first = r.owners().stream().findFirst().orElse(null);
            if (first != null) {
                owner = cache == null ? first.toString().substring(0, 8)
                        : cache.get(first).map(com.mojang.authlib.GameProfile::getName).orElse(first.toString().substring(0, 8));
            }
            out.add(new Nearby(r.id(), r.name(), r.type().key(), owner, first, b));
        }
        return out;
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
