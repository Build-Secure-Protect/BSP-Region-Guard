# BSP Region Guard 0.5.0: plots in PvP worlds, plot limits and Shatter Coins

Everything added since 0.4.0. For Minecraft 1.20.1 (Forge 47.4.10 and Mohist).

> **Now requires BSP-Core 1.4.3 or newer** on the server and every player; with an older BSP-Core, this Region Guard won't load. Update the server and every player together.

## New: plots in PvP worlds, per world

- A world where Shatter Totems can be placed counts as a PvP world, and plots are refused there unless you allow them.
- **Each world now has its own switch.** On the world default's **BORDER** tab, a new **PLOTS HERE** card shows whether plots are allowed and why:
  - **ALLOW:** plots may be made and bought in this world.
  - **REFUSE:** no plots in this world.
  - **CONFIG:** follow the server config (`claims.allowInPvpWorlds`), as before.
- **`pvp deny` on the world default now counts:** a world where PvP is switched off allows plots.
- `/rg claims pvp allow|deny|default` does the same, and `/rg claims pvp` on its own tells you the current rule and why.
- The totem rule hasn't changed. Near another player's placed totem, regions still never stop a raider, even where PvP is denied.

## New: plot limits per region

- **Any region can limit how many plots one player may own inside it.** It's on the region's SALE tab ("Plots per player inside", EDIT) or `/rg plotlimit <id> <number|none>`. Use it for a market where each player gets one shop, or a village with two plots each.
- A plot counts when its ground lies inside the region, whatever its height.
- **The world default's limit** is the **MAX** button on its PLOTS HERE card, or `/rg plotlimit __global__ <number>`. It replaces `claims.maxPerPlayer` for that world. Higher LuckPerms ranks (`claims.limitsByRank`) can still raise it.
- Every limit must pass: the world's, and that of every region the new plot lies inside. A refused player is told which limit they've reached.

## New: plots priced in Shatter Coins

- Set `currency = "coins"` in the `[claims]` section of the server config. Plots then cost **Shatter Coins from the player's inventory** instead of Shatter Pieces from the bank.
- **The price is a number of coins of one tier**, for example "3 gold coins". The same goes for an open area's price per block.
  - Choose the tier per plot or open area with the tier buttons after SET PRICE on the SALE tab, or `/rg cointier <id> <copper|gold|diamond|netherite|illyrium|default>`.
  - Otherwise `claims.coinTier` (copper) decides.
- **Paying:** any coins adding up to the price will do, and change comes back, the same way BSP-Core counts coins everywhere else.
- **Giving a plot up** pays half back in coins. A player who is offline gets them at their next login on that server.
- Each claim remembers what it cost in each currency, so a plot bought with pieces still refunds pieces after you switch to coins.
- **BSP-Core 1.4.3's Land Office** shows coin prices and the coins you carry when Region Guard is in coin mode.
- Switching the setting reads the same numbers in the new currency (a plot priced 5,000 pieces becomes 5,000 coins), so set new prices when you switch.

## Fixed

- After moving to another server on a network, the region map kept showing the previous server's terrain. It now loads the new server's terrain.

## Changed

- When a plot is refused in a PvP world, admins are told why the world counts as PvP and how to allow plots.
- `/rg claims list` shows what each claim cost in pieces, coins or both.

## For developers

- **API version 6:** `claims().paysInCoins()` and `claims().priceText(level, regionId, amount)`, for example "5 gold coins".
- In coin mode, a listing's `price` and `costPerBlock`, and a result's `price`, count coins. Purchases in coins answer at once.
- `claims().claimLimit(player)` starts from the world default's plot limit when it has one.
- `claims().claimsAllowedIn(level)` follows the new per-world rule.
