# BSP Region Guard API

Other mods compile against the package `com.mrgregles.bsp_region_guard.api` (with its `event` subpackage) and the flag types `flag.Flag`, `flag.StateFlag`, `flag.State` and the constants in `flag.Flags`. Everything else may change without notice. Every public type and method in `api` carries javadoc (`./gradlew javadoc`).

## Getting the API

```java
RegionGuardApi api = RegionGuardApi.get();
```

Available from `FMLCommonSetupEvent` on; call the services on the server thread. A mod that treats Region Guard as optional checks `ModList.get().isLoaded("bsp_region_guard")` and keeps every call in one class.

`RegionGuardApi.API_VERSION` is raised whenever a method is added or changed. **Version 2** (2026-10-14): claims are paid from the BSP Core bank in Shatter Pieces and `purchasePlot`, `purchaseOpen`, `enlarge` answer through a callback; `Cuboid` (from `region`), `Exemption` (from `region.RegionQuery.Exemption`) and the new `Border` are in `api`; `regions().bounds` and `regions().border` are new. **Version 3** (2026-10-15): `RegionTriggerEvent.Cause.EDGE`, fired when a player chooses to travel across a world edge linked to another server (region `__global__`, a cross-server destination 16 blocks inside the other world's edge). A listener that handles every cross-server `RegionTriggerEvent` (as BSP Teleport's does) needs no change. **Version 4** (2026-10-15): `claims().selection(player)`, `claims().claimsNear(level, area)` and `bounds()` on `ClaimPurchasedEvent` / `ClaimAbandonedEvent`, for BSP Core's Land Office. **Version 5** (2026-10-15): `serverId()`.

Value types in `api`: `Cuboid` (a box of whole blocks, both corners inclusive: `of`, `contains`, `intersects`, `sizeX/Y/Z`, `area`, `volume`), `Border` (a world border: a square `size` blocks across around `centerX, centerZ`: `contains`, `toEdge`, `minX ... maxZ`), `ClaimResult`.

## Services

| Call | What |
|---|---|
| `regions().regionIdsAt(level, pos)` | ids of the regions at a position, highest priority first, world default last |
| `regions().allows(level, pos, flag, player)` | whether a state flag allows the action there (exemptions included); flags are the constants in `flag.Flags`. For `teleport-in`, `teleport-out`, `set-home`, `totem-place` and `totem-drop` it also answers no inside a BSP-Core Anti Totem zone that blocks the same rule, and `teleport-in` and `set-home` answer no outside the world border, so callers need ask only Region Guard |
| `regions().value(level, pos, flag)` | a flag's value there, or null |
| `regions().bounds(level, regionId)` | a region's `Cuboid`, or null for an unknown id, the world default, or a region made without corners that has none yet (since 2026-10-22; such a region applies nowhere) |
| `regions().border(level)` | the world's `Border`, or null when it has none (it belongs to the world's global region; set with `/rg border`) |
| `regions().registerFlag(flag)` | add your own flag before the server starts; it appears in the admin screen |
| `regions().addExemption(exemption)` | lift region rules for a player in a spot (an `api.Exemption`, see below) |
| `serverId()` | this server's name in the shared storage (`storage.serverId`), which must equal its Velocity server name; given even when shared storage is off, so a mod can check it against its own name for this server |
| `claims()` | see Claims below |
| `mines().isMine / reset / secondsToReset` | mines |
| `publish(kind, dimension, regionId, payload)` | send a message to the other servers through the shared storage: through Redis within milliseconds when it is configured, and always through the MySQL message table as the safety net (each message is handled once); false when storage is off. See `docs/NETWORK.md` |

## Region triggers

Admins attach triggers to regions (`/rg trigger add ...`): on enter, exit, a timer while inside, or server shutdown, send the player to a world and position on this server or on another server. Each firing is a `RegionTriggerEvent` (cancellable). Region Guard carries out same-server moves itself; cross-server moves are BSP Teleport's: it listens, cancels and moves the player. When no listener cancels a cross-server trigger, Region Guard publishes kind `trigger` with payload `uuid;server;world;x;y;z` through the shared storage.

## Claims (`RegionGuardApi.get().claims()`)

For a shop screen or any mod that sells land. Region Guard keeps the rules and the claims; a call either answers a question or does the whole thing (checks, charges the player's BSP Core bank, creates or changes the claim) in one server-side step, so the caller never touches money.

**Money is in Shatter Pieces**, BSP Core's bank unit (owner decision D31; BSP Core's default is 1,000 pieces to a Copper coin). Prices are `long`. The bank answers a charge through a callback: at once on a single server, after a database round trip on a network. So `purchasePlot`, `purchaseOpen` and `enlarge` answer through a `Consumer<ClaimResult>`, always on the server thread. While a charge is in flight the plot (or the outlined ground) is held for the buyer, other players are told someone is buying it, and the buyer cannot start a second purchase. When the money arrives the purchase is checked again; if the plot was taken or its price changed meanwhile, the money goes straight back to the buyer's bank and the result says so. A hold lapses after 60 seconds if the bank never answers.

| Method | What it does |
|---|---|
| `plotsForSale(level)`, `openAreas(level)` | `Listing(id, name, bounds, price, costPerBlock, minSize, maxSize, open)` for each plot or area in the world; `minSize`/`maxSize` are 0 when the config decides. |
| `claimsOf(level, uuid)` | ids of the player's claims in that world. |
| `claimLimit(player)` | how many claims the player may own per world (config, raised by LuckPerms group). |
| `quotePlot(player, plotId)` | the price and whether the player may buy; changes nothing. Checks the balance this server last knew. `ClaimResult.price` is set even when `ok` is false for lack of money. |
| `quoteOpen(player, openId, ground)` | same for a self-outlined plot: `ground` is a `Cuboid` whose x and z matter (y is ignored; claims are full height). Reports size, overlap and limit problems. |
| `purchasePlot(player, plotId, then)`, `purchaseOpen(player, openId, ground, then)` | buy: charge the bank, then make the plot a claim owned by the player (plot) or create a child claim of the area (open); the answer goes to `then`. |
| `enlarge(player, claimId, ground, then)` | grow an outlined plot; the new outline must contain the old one; pays for the added ground. |
| `abandon(player, claimId)` | give up at once: `claims.abandonRefundPercent` of what was paid goes back into the owner's bank (also when an admin removes the claim of an offline player); an admin plot goes back on sale, an outlined plot is removed. The result's `price` is the refund. |
| `claimsAllowedIn(level)` | false in PvP worlds unless configured. |
| `selection(player)` | the player's wand outline (`Cuboid`) in the world they stand in, or null; what a shop prices when opened without an outline. |
| `claimsNear(level, area)` | `Nearby(id, name, type, ownerName, owner, bounds)` for every claim, plot for sale and open area whose ground touches `area` (x and z only); for drawing neighbours on a shop map. |

**BSP Core's Land Office** (owner decisions D22, D46, D47): when a player (not a BSP Core admin with bypass on) finishes a wand outline in a world where claims are allowed, Region Guard calls `BspLandOffice.open(player, x1, z1, x2, z2)`; the claim screen's ENLARGE does the same with the player's outline. Players buy and enlarge only there; `/claim buy` and `/claim enlarge` answer "Buy at a Land Office" for them and stay available to admins.

`Claims.INSTANCE.setPlayerFlag(player, claimId, flag, value)` is what the claim screen and `/claim flag` use: it checks the plot's allowed-flag list (the plot's own, its area's, or the config's) and the Shatter Totem rule (no rule that could stop a steal while a totem stands inside).

Every answer is a `ClaimResult(ok, message, claimId, price)`; `message` is a plain sentence for the player. Events on the Forge bus: `ClaimPurchasedEvent(player, claimId, dimension, price, enlarged, bounds)` and `ClaimAbandonedEvent(player, claimId, dimension, refund, bounds)`, both after the fact and not cancellable (`bounds()`: the claim's box, the new one after an enlargement). Plots for sale and open areas are already sent to every client and drawn in gold in the world, so a shop screen can highlight the one being bought without extra packets.

## Exemptions

`RegionGuardApi.get().regions().addExemption((level, pos, flag, player) -> ...)` registers an `api.Exemption`: a rule that lifts a region's decision for a player in a spot, the way the Shatter Totem rule does. Exemptions are asked only when a region would refuse a player, never for the world's own events (fire, fluids, mobs), on the server thread and possibly every tick for a moving player, so keep them quick. Register once, early.

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
