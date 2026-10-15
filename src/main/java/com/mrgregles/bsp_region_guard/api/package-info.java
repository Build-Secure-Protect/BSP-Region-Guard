/**
 * The public API of BSP Region Guard.
 *
 * <p>Other mods compile against this package (and its {@code event} subpackage) and the flag types
 * {@code flag.Flag}, {@code flag.StateFlag}, {@code flag.State} and the constants in {@code flag.Flags}. Everything
 * else may change without notice. Entry point: {@link com.mrgregles.bsp_region_guard.api.RegionGuardApi#get()};
 * services {@link com.mrgregles.bsp_region_guard.api.RegionService}, {@link com.mrgregles.bsp_region_guard.api.ClaimService},
 * {@link com.mrgregles.bsp_region_guard.api.MineService}; value types {@link com.mrgregles.bsp_region_guard.api.Cuboid},
 * {@link com.mrgregles.bsp_region_guard.api.Border}, {@link com.mrgregles.bsp_region_guard.api.ClaimResult}; the
 * hook {@link com.mrgregles.bsp_region_guard.api.Exemption}. Documented in {@code docs/API.md}.
 */
package com.mrgregles.bsp_region_guard.api;
