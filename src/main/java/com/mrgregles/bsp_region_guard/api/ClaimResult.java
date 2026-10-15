package com.mrgregles.bsp_region_guard.api;

import org.jetbrains.annotations.Nullable;

/**
 * The answer to a claim question or action.
 *
 * @param ok       whether the action went ahead (or, for a quote, whether it could)
 * @param message  a plain sentence for the player
 * @param claimId  the claim concerned, when there is one
 * @param price    the amount in Shatter Pieces (BSP-Core's bank unit): the price, or for an abandon the refund; 0 when not relevant
 */
public record ClaimResult(boolean ok, String message, @Nullable String claimId, long price) {
    /** A refusal with a sentence for the player; nothing changed. */
    public static ClaimResult fail(String message) {
        return new ClaimResult(false, message, null, 0);
    }

    /** A success (or, for a quote, a yes) with a sentence for the player. */
    public static ClaimResult ok(String message, @Nullable String claimId, long price) {
        return new ClaimResult(true, message, claimId, price);
    }
}
