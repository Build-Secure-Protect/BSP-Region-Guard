package com.mrgregles.bsp_region_guard.api;

import org.jetbrains.annotations.Nullable;

/**
 * The answer to a claim question or action.
 *
 * @param ok       whether the action went ahead (or, for a quote, whether it could)
 * @param message  a plain sentence for the player
 * @param claimId  the claim concerned, when there is one
 * @param price    the price in coin value (copper-coin units), 0 when not relevant
 */
public record ClaimResult(boolean ok, String message, @Nullable String claimId, int price) {
    public static ClaimResult fail(String message) {
        return new ClaimResult(false, message, null, 0);
    }

    public static ClaimResult ok(String message, @Nullable String claimId, int price) {
        return new ClaimResult(true, message, claimId, price);
    }
}
