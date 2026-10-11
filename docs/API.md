# BSP Region Guard API

Other mods compile against the package `com.mrgregles.bsp_region_guard.api` only. Everything outside it may change without notice.

## Getting the API

```java
RegionGuardApi api = RegionGuardApi.get();
```

Available from `FMLCommonSetupEvent` on. `RegionGuardApi.API_VERSION` is raised whenever a method is added or changed.

## Services

| Call | What |
|---|---|
| `regions().regionIdsAt(level, pos)` | ids of the regions at a position, highest priority first, world default last |
| `regions().allows(level, pos, flag, player)` | whether a state flag allows the action there (exemptions included); flags are the constants in `flag.Flags` |
| `regions().value(level, pos, flag)` | a flag's value there, or null |
| `regions().registerFlag(flag)` | add your own flag before the server starts; it appears in the admin screen |
| `regions().addExemption(...)` | lift region rules for a player in a spot |
| `claims()` | see Claims below |
| `mines().isMine / reset / secondsToReset` | mines |
| `publish(kind, dimension, regionId, payload)` | send a message to the other servers through the shared storage; false when storage is off |

## Region triggers

Admins attach triggers to regions (`/rg trigger add ...`): on enter, exit, a timer while inside, or server shutdown, send the player to a world and position on this server or on another server. Each firing is a `RegionTriggerEvent` (cancellable). Region Guard carries out same-server moves itself; cross-server moves are BSP Teleport's: it listens, cancels and moves the player. When no listener cancels a cross-server trigger, Region Guard publishes kind `trigger` with payload `uuid;server;world;x;y;z` through the shared storage.

## Claims (`RegionGuardApi.get().claims()`)

For a shop screen or any mod that sells land. Region Guard keeps the rules and the claims; a call either answers a question or does the whole thing (checks, charges the BSP Core wallet, creates or changes the claim) in one server-side step, so the caller never touches money.

| Method | What it does |
|---|---|
| `plotsForSale(level)`, `openAreas(level)` | `Listing(id, name, bounds, price, costPerBlock, minSize, maxSize, open)` for each plot or area in the world; `minSize`/`maxSize` are 0 when the config decides. |
| `claimsOf(level, uuid)` | ids of the player's claims in that world. |
| `claimLimit(player)` | how many claims the player may own per world (config, raised by LuckPerms group). |
| `quotePlot(player, plotId)` | the price and whether the player may buy; changes nothing. `ClaimResult.price` is set even when `ok` is false for lack of coins. |
| `quoteOpen(player, openId, ground)` | same for a self-outlined plot: `ground` is a `Cuboid` whose x and z matter (y is ignored; claims are full height). Reports size, overlap and limit problems. |
| `purchasePlot(player, plotId)`, `purchaseOpen(player, openId, ground)` | buy: charge, then make the plot a claim owned by the player (plot) or create a child claim of the area (open). |
| `enlarge(player, claimId, ground)` | grow an outlined plot; the new outline must contain the old one; pays for the added ground. |
| `abandon(player, claimId)` | give up: refund `claims.abandonRefundPercent` of what was paid; an admin plot goes back on sale, an outlined plot is removed. Admins may abandon anyone's claim (the owner is refunded if online). |
| `claimsAllowedIn(level)` | false in PvP worlds unless configured. |

`Claims.INSTANCE.setPlayerFlag(player, claimId, flag, value)` is what the claim screen and `/claim flag` use: it checks the plot's allowed-flag list (the plot's own, its area's, or the config's) and the Shatter Totem rule (no rule that could stop a steal while a totem stands inside).

Every call returns `ClaimResult(ok, message, claimId, price)`; `message` is a plain sentence for the player. Events on the Forge bus: `ClaimPurchasedEvent(player, claimId, dimension, price, enlarged)` and `ClaimAbandonedEvent(player, claimId, dimension, refund)`, both after the fact and not cancellable. Plots for sale and open areas are already sent to every client and drawn in gold in the world, so a shop screen can highlight the one being bought without extra packets.

## Exemptions

`com.mrgregles.bsp_region_guard.region.RegionQuery.addExemption((level, pos, flag, player) -> ...)` registers a rule that lifts a region's decision for a player in a spot, the way the Shatter Totem rule does. Exemptions are asked only when a region would refuse a player; never for the world's own events. This class will move into the `api` package in Phase 9; until then treat the method as stable.

## Events (Forge bus, `com.mrgregles.bsp_region_guard.api.event`)

| Event | When | Cancel means |
|---|---|---|
| `RegionEnterEvent` | a player is about to enter a region; one event per region, with `regionId()`, `dimension()` and `byTeleport()` | the player is put back where they came from |
| `RegionLeaveEvent` | a player is about to leave a region; same fields | the player is put back inside |

Both extend `PlayerEvent`; `getEntity()` is the `ServerPlayer`. They fire from the server tick, so a listener may teleport or message the player freely.

| `MineResetEvent` | a mine starts refilling (players inside already moved out); `level()`, `mineId()`, `blocks()` | not cancellable |
| `ClaimPurchasedEvent`, `ClaimAbandonedEvent` | see the claims section | not cancellable |
| `RegionTriggerEvent` | a region trigger fired for a player: `player()`, `regionId()`, `cause()` (ENTER, EXIT, TIMER, SHUTDOWN), `destinationServer()` (empty = this server), `destinationWorld()`, `x() y() z()`, `message()`; cancel to take the move over | cancellable |
| `RemoteMessageEvent` | another server published a message through the shared storage: `source()`, `kind()`, `dimension()`, `regionId()`, `payload()`; publish with `storage.RegionMirror.active().publish(kind, dimension, regionId, payload)` (null when storage is off) | not cancellable |

Mines are driven by `com.mrgregles.bsp_region_guard.mine.Mines` (`reset`, `schedule`, `secondsToReset`, `makeShell`, `spawnOf`) and `MineData` (fill table, interval, spawn); a `MineService` in the `api` package wraps them in Phase 9.
