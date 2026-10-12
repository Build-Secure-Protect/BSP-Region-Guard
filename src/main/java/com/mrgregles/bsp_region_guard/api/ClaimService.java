package com.mrgregles.bsp_region_guard.api;

import com.mrgregles.bsp_region_guard.region.Cuboid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Land claims. Region Guard owns the rules (prices, sizes, overlap, limits per rank) and the claims themselves;
 * a shop screen (BSP Core's) asks for quotes and buys through here. Every purchase charges the player's BSP Core
 * bank and creates the claim in one server-side step, so a caller never handles money itself.
 *
 * <p>Prices are in Shatter Pieces, BSP Core's bank unit. Plots for sale are regions of type {@code plot_for_sale};
 * open areas are regions of type {@code open_for_buying} with a cost per block of ground, inside which a player
 * outlines their own plot. A bought plot becomes a region of type {@code claim} owned by the buyer.
 *
 * <p>The bank answers a charge through a callback (at once on a single server, after a database round trip on a
 * network), so purchases and enlargements answer through {@code then}, always on the server thread. While a charge
 * is in flight the plot or outline is held for the buyer, and the buyer cannot start another purchase. If the plot
 * was taken or changed by the time the money arrives, the money is paid back and the result says so.
 */
public interface ClaimService {
    /** A plot an admin put up for sale, or an open area, as a shop would list it. */
    record Listing(String id, String name, Cuboid bounds, long price, long costPerBlock, int minSize, int maxSize, boolean open) {
    }

    /** Plots for sale in a world. */
    List<Listing> plotsForSale(ServerLevel level);

    /** Open areas in a world where players may outline their own plot. */
    List<Listing> openAreas(ServerLevel level);

    /** The ids of the claims a player owns in a world. */
    List<String> claimsOf(ServerLevel level, UUID player);

    /** How many claims the player may own in a world, from the config and their LuckPerms rank. */
    int claimLimit(ServerPlayer player);

    /** What buying a plot would cost, and whether the player may (their balance as this server last knew it). Changes nothing. */
    ClaimResult quotePlot(ServerPlayer player, String plotId);

    /** What outlining {@code ground} (only x and z matter) inside an open area would cost, and whether the player may. */
    ClaimResult quoteOpen(ServerPlayer player, String openId, Cuboid ground);

    /** Buys a plot: charges the bank, makes it a claim owned by the player, then answers through {@code then}. */
    void purchasePlot(ServerPlayer player, String plotId, Consumer<ClaimResult> then);

    /** Buys a self-outlined plot inside an open area: charges the bank, creates the claim as a child of the area. */
    void purchaseOpen(ServerPlayer player, String openId, Cuboid ground, Consumer<ClaimResult> then);

    /** Grows a claim to {@code ground} (which must contain the old plot), charging for the added ground. */
    void enlarge(ServerPlayer player, String claimId, Cuboid ground, Consumer<ClaimResult> then);

    /**
     * Gives a claim up at once and pays part of what was paid back into the owner's bank (also when an admin removes
     * an offline player's claim); an admin plot goes back on sale, an outlined plot disappears. The result's price is
     * the refund.
     */
    ClaimResult abandon(ServerPlayer player, String claimId);

    /** Whether claims may exist in this world at all (not in PvP worlds unless configured). */
    boolean claimsAllowedIn(ServerLevel level);
}
