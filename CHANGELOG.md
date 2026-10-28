# Changelog

All notable changes to BSP Region Guard. Format follows Keep a Changelog; versions follow SemVer.

## [Unreleased]

### Changed (2026-10-28, T14: network passwords no longer reach players; D126 to D129)
- **The shared storage's connection moved to its own file, `config/bsp_region_guard-network.toml`.** Forge sends every server config file to each player at login, so the database and Redis passwords in `bsp_region_guard-server.toml` reached every player who joined. The new file is a common config and is never sent. It holds `[database]` host, port, database, user, password, tablePrefix and `[redis]` host, port, username, password. `storage.mode`, `storage.serverId`, `pollSeconds`, `syncMineResets`, `storage.redis.enabled`, `channel` and `safetyPollSeconds` stay in the server config.
- **The move happens by itself** at the first start, before shared storage connects: each old value moves (a value already in the new file is kept), the old key stays in the server config empty with a note, and the log names the keys that moved, never a value. `RegionGuardApi.serverId()` is unchanged.
- **After updating, change the database and Redis passwords**, remove them from any `defaultconfigs/` copy, and never ship `config/*-network.toml` in a client pack.

### Fixed (2026-10-27, B17: missing ranks on the RANKS tab)
- The RANKS tab showed at most 18 LuckPerms groups (six rows of three), so on a server with more groups the rest (in the owner's case everything after `titan_slayer`: vanguard, wanderer, warden, warlord...) never appeared. It now fills as many rows and columns as the pane has room for, the mouse wheel scrolls through the rest, and a line says how many more there are.

## [0.6.0] - 2026-10-25

Plot markers and the PLOT LOOK window, the teleport offer after buying, the MINE FILL window, region outlines only with the wand, renaming that changes the id, and lighter drawing. **Network protocol 9:** servers and players need this version together. **Needs BSP-Core 1.4.6 or newer** (was 1.4.3); Region Guard now builds against 1.4.6. The API is unchanged (version 6).

### Added (2026-10-25, mine fill window: D115 to D119)
- **MINE FILL window** (EDIT FILL on the MINE tab, or on the region map's card in MINE mode): the mine's blocks on the left, each with a percentage box (one decimal, so 0.5% diamond works) and the mine's **total** under them; a creative-style block browser on the right (the creative tabs with blocks, search, `@mod`), blocks already in the mine marked in gold. Click a block to add it.
- **REST:** one block can be marked to fill whatever share is left; it is the mine's base, and the others grow as veins. The first block added to an empty mine is REST. Without a REST block the biggest share is the base, as before.
- **SAVE** only when the total is 100%; the window says how much is left or too much. Existing mines open with their weights turned into percentages. Up to 24 blocks.
- The MINE tab shows each block's share with one decimal and marks the REST block. Blocks are no longer typed in by name there (`/rg mine add` still works).

### Changed (2026-10-25, region outlines: D120 to D124)
- **Region outlines show while you hold the Region Wand** (either hand) and go away when you put it down; the gold outline of a selection too. The region key (B) still shows them without the wand and now **starts off for everyone**. Plots for sale keep showing to everyone with their markers. Players holding the plot tool see their own claims.

### Fixed (2026-10-25, B16: renaming changes the id too)
- Renaming a region (INFO tab, the region map's card, or `/rg rename`) only changed its display name, so the old id stayed taken ("a region named 'mine-i' already exists"). A rename now changes the id as well: the name "Mine-II" gives the id `mine-ii`, "Tadana Plot 1" gives `tadana_plot_1` (lower case, spaces as `_`, only letters, digits, `_` and `-`). Everything the region holds comes along; regions whose parent it was follow it; a mine's `_shell` is renamed with it; the region map's undo steps and a plot's backup files follow the new id; shared storage drops the old id and writes the new one. A name whose id is already taken is refused. The world default keeps its id. The screen and the map stay on the renamed region.

### Fixed (2026-10-25, B15: names in the region list)
- The admin screen's region list showed each region's id, so a name set on the INFO tab (for example "Tadana Plot 1" for `tadana`) never showed there. The list now shows the name (the id until one is set); the id stays in the header above the tabs, and search finds either.

### Fixed (2026-10-25, B14: buttons that seemed to do nothing)
- The admin screen's buttons (RESET NOW, MOVE SPAWN, INTERVAL, RESIZE SHELL on the MINE tab, and every other one) worked, but the server's answer went to the action bar, which is hidden behind an open screen. Every button now lights up for a moment when clicked, and the answer shows in the screen's bottom line for six seconds (teal when it worked, red when it was refused). With no screen open it still goes to the action bar.

### Added (2026-10-23, plot markers: D105 to D114)
- **How plots for sale look in the world.** Every plot for sale and open area shows to every player in its own colour, with a marker over its middle carrying its name, until it is bought; then it disappears (the owner can still show their claim with the region key).
  - Markers: **arrow** (a big downward arrow at 20% with the name above, bobbing), **orbit sphere** (the name's letters circling a glassy sphere), **spire beacon** (a pulsing beam with a turning crystal), **for sale sign** (FOR SALE, the name and the price), or **outline only**. New plots start with the gold arrow.
  - Settings: colour (eight colours or any hex), marker size and name size (0.5x to 3x), height above the ground in the plot's middle (kept below the plot's top). Markers show within 96 blocks.
  - Set in the new **PLOT LOOK** window (LOOK on the SALE tab, or on the region map's plot card in PLOT mode) with a preview that turns, or `/rg look <id> [marker|colour|size|namesize|height] <value>`.
  - A copied plot takes the look and shows its own name.
- **Teleport after buying** (D113): after buying a plot at a Land Office (or an outlined plot), a pop-up offers TELEPORT (to the ground in the plot's middle, or its teleport point) or STAY.

### Changed (2026-10-23)
- While an admin looks at another server's regions, the world drawing keeps showing this server's regions.
- **Less work per frame** (review 2026-10-23): regions out of view are skipped; boxes are drawn in one batch for all faces and one for all edges (was two or three draws per region); a region's box is built once per list, not every frame; the sphere's shape is worked out once.
- **The region map's terrain** is drawn as one picture per 8 by 8 chunks (was one draw per chunk, about two thousand a frame at the widest zoom). A picture is repainted only where new chunk colours arrive, and pictures far out of sight are let go after 300.
- **Fewer packets:** a change to regions now sends each player in that world one fresh list at the end of the tick, however many regions changed in it.

### Fixed (2026-10-24, B13: stuck in RESHAPE)
- On the region map, RESHAPE could not be left: Esc dropped the pending shape, but the bar started the same reshape again on the next frame, so Esc never reached closing the map. The reshape bar now has **CANCEL** (also while folded), and Esc while reshaping goes back to PICK; a second Esc closes the map as before.

### Fixed (2026-10-23, review before release)
- A plot's faint box hid its own marker from outside the plot (the faces were drawn first and blocked it). Markers are drawn first now.
- The for sale sign's lines ran into each other at name size 2 and above; the board grows with the name size. Sphere letters ran together when the name size was larger than the marker size.
- The PLOT LOOK preview lost its lines with Fabulous graphics; it now draws them on the screen itself.
- Text that ran out of its box: the SALE tab's BUYER MAY SET line (now cut with "..."), the PLOT LOOK title and hint, the bought pop-up's question (now wrapped), the BORDER tab's server name and centre, long names on the sign, and VERTICAL FILL on the region map's card. A priority of three digits no longer runs into its "-" button. "1 blocks" reads "1 block".

## [0.5.0] - 2026-10-23

Plots in PvP worlds, plot limits per region, and plots priced in Shatter Coins. **Needs BSP-Core 1.4.3 or newer** (was 1.1.0): coins go through BSP-Core's new `api.BspCoins` (request RG-C9); Region Guard now builds against BSP-Core 1.4.3. API version 6. Network protocol unchanged (7).

### Fixed (2026-10-23, B12: map terrain after a server switch)
- After moving to another server through the proxy, the region map kept drawing the previous server's terrain for worlds with the same name (every server has an overworld). The map's tiles now belong to one server: they are dropped when the client's world changes, on login and on logout (`client/MapTileCache`, `ClientSetup`).

### Fixed (2026-10-23, B11: plots in PvP worlds)
- A world counted as a PvP world for plots whenever BSP Core allows Shatter Totems there, even with `pvp` denied on its world default. Now a world default with **`pvp` deny counts as not PvP for plots** (D93). The totem rule is unchanged: raiders are still not stopped near another player's totem (D94).
- The refusal now says why the world counts as a PvP world and how to allow plots.

### Added (2026-10-23)
- **Plot limits per region** (D100 to D102): any region can limit how many plots one player may own inside it (by ground, x and z), with **Plots per player inside** on its SALE tab or `/rg plotlimit <id> <n|none>`. The world default's limit (MAX on its BORDER tab's PLOTS HERE card, or `/rg plotlimit __global__ <n>`) takes the place of `claims.maxPerPlayer`; rank limits can still raise it. Every limit must pass.
- **Shatter Coin prices** (D95 to D98): `claims.currency = coins` (server config) makes plots cost coins of one tier from the player's inventory, any tiers adding up to the value, with change. The tier is set per plot or open area (tier chips on the SALE tab, `/rg cointier <id> <tier|default>`), else `claims.coinTier` (copper). Giving a plot up pays half back in coins (at the next login if offline). Claims keep what they cost in each currency. **API 6:** `claims().paysInCoins()`, `claims().priceText(...)`. BSP-Core 1.4.3's Land Office shows coin prices (RG-C9).
- **Plots in a PvP world, per world** (D92): ALLOW, REFUSE or CONFIG on a new PLOTS HERE card on the world default's BORDER tab, or `/rg claims pvp allow|deny|default` (no argument: shows the current rule and why). CONFIG follows `claims.allowInPvpWorlds`. The setting is stored on the world default, so it is mirrored with it.

## [0.4.0] - 2026-10-23

The region map: one map that makes, reshapes and manages every region, plot and mine; regions made without the wand; size editing and vertical fill. Network protocol 7: servers and players need this version together. The API is unchanged (version 5).

### Added (2026-10-22, region size and corners: B6, B7)
- **Regions without corners** (owner decisions D71 to D79): **+ NEW** with no wand selection, `/rg define <id>` or the new alias `/rg create <id>` without one, makes a region that protects nothing and applies nowhere until it has corners.
  - It shows NO CORNERS in the list and "no corners yet" in `/rg list` and `/rg info`.
  - Flags, owners, members, ranks and the name can be set beforehand. Teleport, sale, open for buying, mine, other types and SELECT refuse until it has corners.
  - It is saved with a one-block box far below the world, so an older Region Guard on another server reads it as a tiny region, never as the world default.
- **Corners on the plot map:** + NEW without a selection, or SET CORNERS ON MAP on the INFO tab, opens the plot map in corner mode.
  - Click the first and second corner; a drag still pans. SNAP lands a corner just outside a neighbour's side.
  - The height is the whole world unless the corner bar's two height fields say otherwise.
  - Protected regions and mines show as grey dotted outlines.
  - CONFIRM sets the corners and goes back to the admin screen; CANCEL or Esc changes nothing. UNDO and REDO cover it.
  - Plots, claims and open areas keep to the overlap rule (D59).
- **Size editing on the INFO tab:** the corners are six fields (Enter saves, Esc puts them back), with VERTICAL FILL, SET CORNERS ON MAP and USE WAND SELECTION (which replaces REDEFINE under the tabs).
- **Vertical fill:** from the world's lowest block to its highest buildable block (-64 to 319 in the Overworld). VERTICAL FILL on the INFO tab; `/rg vfill <id>` for a region, `/rg vfill` for your wand selection.
- **Typed corners:** `/rg redefine <id> <x1 y1 z1> <x2 y2 z2>` (`~` works). Heights outside the world are refused.
- Seven GameTests (98 at the time).

### Changed (2026-10-22, screens; D80, mock-up option A)
- **The admin screen fills the window** and is drawn at 80% of `ui.scale`. The region list shows as many rows as fit; the MAP tab's map and the FLAGS list grow with the pane.
- **The plot map's cards are drawn at 80%:**
  - UNDO and REDO sit in the world card at the top right, so they no longer overlap the other cards.
  - The picked plot's card and the toolbar under a copied or moved plot fold to one line with ▾ / ▸; the PLOTS card uses the same button.
  - The toolbar has a title line (COPY OF ..., MOVING ...).

### Added (2026-10-23, the region map: one map that edits everything; D82 to D91, mock-up option A)
- **One map for everything.** The plot map is now the region map. The admin screen's **MAP** button (formerly PLOT MAP) opens it centred on the selected region, and so do `/rg map` and `/rg plotmap`. On this server the admin screen's small MAP tab is gone; it still shows while you look at another server's regions.
- **One top bar:**
  - **REGION, PLOT, MINE modes.** Each mode shows its own kind of region and dims the rest, which can't be picked. REGION shows everything.
  - **PICK, DRAW, RESHAPE tools.**
  - In MINE mode, **RESET** and **SHELL** (5 blocks) for the picked mine.
  - The world switcher, UNDO and REDO.
- **DRAW:** drag a box, or click two corners. A card then makes a protected region, a plot for sale (with its price) or a mine, depending on the mode, with an id, a priority and a height. A new mine starts 16 blocks below your feet rather than the whole height.
- **RESHAPE:** drag the eight handles on the picked region's sides and corners, set the height in the bar, then DONE.
- **Panning:** hold **SPACE** and drag, or drag with the right button, in every mode, so you can click one corner, pan, and click the second.
- **The card:**
  - Every mode: the name (Enter saves) and priority.
  - REGION mode: the type.
  - PLOT mode: copy, lock and backups.
  - Buttons open the region's FLAGS, ACCESS, TELEPORT, INFO, SALE or MINE tab in the admin screen; Esc there brings the map back.
  - VERTICAL FILL, RESHAPE and **DELETE** (click twice).
- **UNDO and REDO** now also cover regions drawn and deleted on the map. Undoing a delete brings the region back with its flags, owners and everything else.
- Three GameTests (103 in all). Network protocol 7 (unchanged since 2026-10-22).

### Changed (2026-10-22, every region on the plot map; B10, D81)
- **The plot map lists and draws every region**, not only plots. Protected areas show in teal and mines in gold, as outlines. The list card is now called REGIONS and has PROT and MINE filters.
- **SET CORNERS ON MAP** is on every card. It starts corner mode for that region and stays on the map afterwards. A region that already has corners keeps its height until it's changed.
- Copying, moving, locking and backups stay for plots. One GameTest (100 in all).

### Fixed (2026-10-22)
- Corners clicked on the plot map could land a block or two off (B9). SNAP was on and pulled a corner to just outside any side within two blocks, including the region's own old box, which is hard to see when blocks are small. In corner mode SNAP is now off at first and never uses the region itself. A white frame marks the block a click will use at every zoom, and the corner bar shows the corners' x and z. One GameTest (99 in all).
- After a region changed size, it stayed listed under the ground it used to cover. Lookups still checked the box, so nothing was protected wrongly, but quick checks were slower there. It is now listed only where it is.
- The BORDER tab could miss other servers' worlds on a network with many worlds (B5): it read at most 50 entries, and each server listed the others in a different order, so a world one server offered could be missing on another. Now only worlds with a border (the only ones that can be linked) are sent, all of them, in the same order everywhere. One GameTest (91 in all).

## [0.3.0] - 2026-10-20

The plot map: copy, move, back up and find plots on a full-window map. Network protocol 6: servers and players need this version together.

### Fixed (2026-10-20)
- The plot map's PLOTS card drew its filter buttons over the search box; they now sit below it.

### Added (2026-10-20, moving a plot's blocks)
- **MOVE BLOCKS / COPY BLOCKS** on the plot map's toolbar (owner decisions D67 to D70). A plot's blocks travel with it when it is moved or pasted:
  - **What goes:** chest contents, signs and other block entities, plus item frames, armour stands, paintings and animals. Blocks turn with a turned plot.
  - **Batches:** large plots move a little each tick against a time budget, with a progress line. Players standing inside go along.
  - **The old spot** is rebuilt as natural ground from the world's generator: its shape, water and caves, with the biome's grass, sand, deepslate and bedrock. Trees, ores and structures don't regrow.
  - **Backups first:** both spots are saved to `world/bsp_region_guard/backups/plots/` before anything is overwritten, and UNDO puts both back from them.
  - Copying a plot with its blocks copies chest contents too.
- **BACKUP and BACKUPS on a plot's card:** save a plot's blocks at any time, list its backups, and click one twice to restore it. By command: `/rg plot backup <plot>`, `/rg plot backups <plot>`, `/rg plot restore <file>`.
- Two GameTests (89 in all).

### Added (2026-10-20, the plot map screen)
- **The plot map** (owner decisions D57 to D66, mock-up option B): `/rg plotmap` or PLOT MAP under the admin screen's region list.
- **The map:** the whole window is a map of the world, from one pixel a block up to 16 with a block grid and the x/z under the cursor. It shows plots for sale, claims and open areas.
- **Finding plots:** a folding PLOTS card searches and filters them; click one to snap to it.
- **Copying:** the picked plot's card has COPY. The copy follows the mouse until a click drops it, then drags.
- **Moving:** the card's UNLOCK lets the plot move the same way; cancelling locks it again.
- **The toolbar under a copy or moved plot:** the copy's name, priority, height ▲/▼, ROTATE (or R), SNAP (lines edges up with neighbours; SHIFT flips it), CONFIRM and CANCEL. It shows red with the reason where it would partly overlap a plot or needs a higher priority.
- **Claims someone owns** ask before they move.
- **UNDO and REDO** take back or redo the last 15 actions.
- **Other worlds** can be viewed with the world switch (gold frame, read-only); only the world you stand in can be edited.
- One GameTest (87 in all). Network protocol 6.

### Added (2026-10-20, plot map terrain)
- The plot map's terrain (D61): the server colours chunks as a Minecraft map would (top block, shaded by slope, water by depth), answering loaded chunks at once and loading finished chunks from disk a few per tick. Ground that was never generated comes back empty and is never generated. Colours are cached per world and refreshed on request. Admins only. Network protocol 6. Two GameTests (86 in all).

### Added (2026-10-20, plot map, server side)
- The groundwork for the plot map (owner decisions D57 to D65): copying a plot to a new plot for sale (size, height span, flags and banned blocks, the buyer's allowed flags, price, messages, triggers and priority; never owners or members; turned 90 degrees if wanted; teleport and respawn points move with it), moving a plot once unlocked (plots are locked until an admin unlocks them, and lock again after a move; a claim someone owns moves only after the admin confirms), the nesting rule (a plot may sit wholly inside a plot, claim or open area of lower priority; never partly overlap one or cover one), and undo/redo of each admin's last 15 pastes and moves. Admins only, in the world they stand in. The map screen follows. Four GameTests (84 in all).

## [0.2.0] - 2026-10-19

Banned blocks per region. Built against BSP Core 1.3.0 (requires 1.1.0 or newer). Network protocol 5: servers and players need this version together.

### Fixed (2026-10-19)
- The banned blocks editor's two hints beside BAN WHOLE MOD ran off the right edge of the screen; they now wrap in the space beside the button.

### Added (2026-10-19, banned items pop-up)
- A player turned away for carrying banned things now gets a **window** ("YOU CAN'T BRING THESE INTO SPAWN") showing a picture and the name of each banned thing they carry and where it is ("x 12", "in Shulker Box"), with "Remove them from your inventory to enter." and an OK button. Up to six are shown, the rest counted. It opens at most every 5 seconds; the action-bar line still shows each time. The same window opens when someone holding a banned item is moved out from inside. Network protocol 5 (players need the new jar). One GameTest (80 in all).

### Added (2026-10-19, banned blocks screen)
- **BANNED** tab in the admin screen opens the **banned blocks editor** (owner decision D56, mock-up option C). On the left is a grid of the region's banned blocks; on the right, Minecraft's creative tabs with a search box (`@mod` shows one mod's blocks, as in JEI; `@mod words` narrows it).
- **Using it:**
  - Pick a rule on the bar (PLACE, USE, BREAK, CARRY), then drag blocks into the grid, or shift-click them.
  - Right-click a banned block to step through its rules, or hover it for its four switches.
  - Drag it back, or press x, to allow it again.
  - BAN WHOLE MOD bans every block of the last block you pointed at.
- **Saving:** every change is saved as the region's `deny-*` lists at once.
- The tab row narrows its spacing so nine tabs fit. Two GameTests (79 in all).

### Added (2026-10-18, banned blocks, server side)
- **Banned blocks per region** (owner decisions D51 to D56). Four list flags: `deny-place`, `deny-use`, `deny-break` and `deny-carry`. Entries are ids, whole mods (`@create` or `create:*`) or tags (`#minecraft:beds`). They apply to owners and members too; only admins with bypass pass. The lists add up across overlapping regions and parents.
- `deny-carry` keeps a block or item out of the region entirely: players carrying one (anywhere in their inventory, Curios slots, or inside carried shulker boxes, bundles and backpacks) are put back at the edge, refused by `teleport-in`, can't pick one up inside, can't take one out of any container inside (the ender chest included; new mixin group `containers`), and are moved out if found inside holding one.
- Built against BSP Core 1.3.0 (still requires 1.1.0 or newer). Six GameTests (77 in all). The BANNED BLOCKS screen and the player's pop-up follow.

## [0.1.0] - 2026-10-18

First release on CurseForge.

### Changed (2026-10-17, from the network test)
- **The screens are drawn twice as big** (the admin screen, the claim screen and the travel window): a new client setting, `ui.scale` in `config/bsp_region_guard-client.toml` (default 2, from 1 to 4). A screen that would not fit the window at that size is drawn as large as fits, never smaller than before. One GameTest for the sizing rule.

### Fixed (2026-10-17, landing after edge travel)
- **Travellers across a linked edge sometimes landed underground** (B4): when the top of the column was not plain standing ground (a tree trunk capped by leaves, water, lava), the landing search fell back to the height the player left the other server at and could pick a cave. Arrivals now always land on the ground open to the sky: the top of the column (through grass, flowers and thin snow) when it is solid and safe, otherwise the nearest such column within 12 blocks (kept clear of the edge), and with no dry ground near (open sea) the water's surface. Worlds with a ceiling keep the old search. One GameTest (71 in all).

### Fixed (2026-10-17, from the first network test)
- **A linked edge never asked whether to travel in the Overworld** (B1): the question came only once a player was past the edge, but Minecraft's own wall (which Region Guard sets in the Overworld) stops everyone just inside it. The question now comes within a block of a linked edge, and admins with bypass on are asked too. Two GameTests (69 in all).
- The BORDER tab showed the centre as "around 0.0d, 0.0d" and other numbers from region data with NBT suffixes (B2); the screen now gets plain numbers.

### Added (2026-10-15, server name on the API)
- API version 5: `RegionGuardApi.get().serverId()` gives this server's `storage.serverId` (also when shared storage is off), so BSP Teleport can check it against its own server name without reading Region Guard's config by reflection (BSP Teleport's TR4). One GameTest (67 in all).

### Changed (2026-10-15, BSP Core 1.1.0)
- Built against **BSP Core 1.1.0** and now requires it (`[1.1.0,)`): earlier 1.0.1 builds may lack the totem calls added for Region Guard (RG-C5). Nothing else changes; `tools/setup_test_server.sh` picks up the new version from `gradle.properties`.

### Changed (2026-10-15, BSP Core 1.0.1 Land Office)
- Finishing a wand outline opens **BSP Core's Land Office** with that ground drawn and priced (for players in worlds where claims are allowed; not for BSP Core admins with bypass on, who draw regions with the same wand). The claim screen's ENLARGE opens it too. Players now buy and enlarge only there: `/claim buy` and `/claim enlarge` tell them so; admins keep both (owner decisions D46, D47).
- API version 4: `claims().selection(player)`, `claims().claimsNear(level, area)` (claims, plots for sale and open areas touching some ground, with owner name and bounds), and `bounds()` on `ClaimPurchasedEvent` and `ClaimAbandonedEvent` (BSP Core's CR-1 to CR-4).
- The remaining totem lookups go through BSP Core's api (`BspTotems.allPlaced`, `stealRadius`, `isTotem`, `thiefAt`, RG-C5). BSP Core's full jar now starts on Mohist (RG-C4, verified); `CORE_JAR=full tools/setup_test_server.sh` uses it.
- One GameTest (66 in all).

### Added (2026-10-15, claim wipe in the screen)
- The admin screen can wipe a world's claims (owner decision D45, mock-up option C): with the CLAIM filter on and the search empty, a red WIPE ALL N CLAIMS sits under the region list (N counts every claim in the world). It turns the right pane into the confirmation: what goes (every claim, bought plots included), the one-time code in large figures, a box to type it back, the seconds left, the server's answer, and the backups with RESTORE. The same rules as `/rg claims wipe` apply (BSP Core admins only, code within a minute, backup first, admins told). Network protocol 4. One GameTest (65 in all); both wipe tests now run in their own batches, since a wipe removes every claim in the test world.

### Changed (2026-10-15, server switcher)
- The admin screen's header has a **server picker** ("SERVER < survival-2 > 2 of 5") when shared storage is on (owner decision D44, mock-up option C). Picking another server swaps the region list and the map to that server's regions in this world, in their own colours, read from the shared storage. While looking the screen is **read-only**: gold frame, READ-ONLY in the header, only MAP, FLAGS and INFO, no editing controls, a BACK TO button; the server also refuses every edit from that screen, so a region id both servers use cannot be changed here by mistake. "+ THIS SERVER" on the map lays this server's regions underneath in grey. Region changes on this server do not pull the admin out of the view; changing world, BACK, Esc or reopening the screen does. This replaces the all-at-once OTHER SERVERS overlay. Network protocol 3. One GameTest (64 in all).

### Added (2026-10-15, tab completion)
- `/rg trigger add <region> <cause> <destination>` completes the destination word by word: for a timer the seconds first (10, 30, 60, 300), then `here`, this server's worlds (with and without `minecraft:`) and the other servers' worlds as `server/world` (from the shared storage), then your own x y z, then nothing (the rest is the message). One GameTest (63 in all).

### Changed (2026-10-15, screen text)
- All text on the admin screen, the claim screen and the edge travel pop-up (and the show-regions key message) now comes from the language file, `assets/bsp_region_guard/lang/en_us.json`, under `gui.bsp_region_guard.*`: 252 keys, with `%s` for filled-in values and `.one` / `.many` forms for counts ("1 owner", "2 owners"). The file is written by `tools/gen_assets.py` (table `GUI`); a translation is another `xx_xx.json` with the same keys. `tools/check_lang.py` checks that every key the screens use exists, that texts given values have `%s`, and that no stray `%` would break a text. Flag names, flag hints and messages sent by the server are still English.

### Added (2026-10-15, edge links)
- **Edge links between servers** (owner decisions D40 to D42): an edge of a world's border leads to an edge of a world on another server. A player who walks into a linked edge gets a window: TRAVEL hands them to BSP Teleport (a `RegionTriggerEvent` with the new cause `EDGE`, API version 3) to arrive at the same fraction along the other edge, 16 blocks inside, and the arriving server moves them onto standing ground; STAY pushes them back and they are not asked again until they leave the warning band. Links are stored on the world default of the server where they were made and work both ways because every server reads the others' world defaults from the shared storage (at start, every 5 minutes, on any border or link change, and when the BORDER tab opens). Unlinking from either side works.
- Admin screen: the world default has a **BORDER** tab (the compass layout chosen from the mock-ups): the border with CHANGE, and a slot per edge showing where it leads or + LINK with a list of the other servers' worlds (matching edge first). SALE and MINE are hidden for the world default, which can be neither.
- Commands `/rg border links`, `/rg border link <side> <server> <world> <their side>`, `/rg border unlink <side>`. A warning at start when `storage.serverId` and BSP Teleport's server name differ (they must match). Network protocol 2.
- Five GameTests (62 in all): arrival geometry; a link made on another server works both ways and unlinks; the question is asked once per approach and TRAVEL goes to the teleport mod with the right destination; arrivals are put on standing ground; the BORDER tab's actions.

### Changed (2026-10-14, API)
- **API version 2.** `Cuboid` moved from `region` to `api`, `RegionQuery.Exemption` became `api.Exemption`, and the world border's shape is `api.Border`. New `regions().bounds(level, id)` and `regions().border(level)`. Every public type and method in `api` now has javadoc; `docs/API.md` lists what other mods may compile against (the `api` package and its events, plus the flag types in `flag`). Mods that only use `regions().allows`, `publish` and the events (BSP Teleport) need no change.

### Added (2026-10-14, world border)
- **World border per world** (owner decisions D36 to D39): `/rg border`, `/rg border set <width> [<centerX> <centerZ>]`, `/rg border off`. The border belongs to the world's global region, which every world now has from the start, and travels with it through the shared storage. Players are warned `border.warnDistance` (10) blocks before the edge (flag `border-warning`), put back where they stood when they step over it (flag `border-message`), and moved just inside onto standing room when found outside after a login, teleport, respawn or a smaller border; riders move with their mount. `teleport-in` and `set-home` answer no outside the border, so BSP Teleport refuses teleports and homes there. Admins with bypass on pass.
- In the Overworld, Minecraft's own border is set to match so the wall shows (`border.vanillaWall`); the other worlds' Minecraft border is then lifted, because Minecraft would copy the Overworld's size into them. Without a Region Guard border in the Overworld, Minecraft's border is left alone.
- Two GameTests (57 in all), in their own batch: warning, push-back, moving in from outside, refused teleports and homes, admin bypass; the Overworld wall follows and the Nether's is lifted.

### Changed (2026-10-14, later)
- The `/rg` commands and the admin screen now make every region edit through one shared class (`command/RegionEdits`), so each rule lives in one place. Rules that differed between the two now match:
  - `/rg sale <id> 0` takes a plot off sale (it used to leave it marked for sale with no price), and `/rg open <id> 0` closes an open area.
  - A price or cost of 0 from the SALE tab only reverts a plot for sale or an open area; it no longer turns a mine or other region into a plain protected one.
  - `/rg open` refuses an owned claim, like `/rg sale` did.
  - The screen can no longer change the world default's priority, and its mine settings (blocks, interval, spawn, reset) need a mine, as the commands did.
  - Messages are the commands' fuller sentences on both; the screen still stays quiet when a field edit works.
- One GameTest (55 in all) checks these shared rules.

### Added (2026-10-14)
- **Redis for cross-server messages** (owner decision D34; `storage.redis` section: `enabled`, `host`, `port`, `username`, `password`, `channel`, `safetyPollSeconds`). Mine resets, region triggers, BSP Teleport's landings and other `publish` calls now arrive within milliseconds. Region Guard has its own small Redis client (AUTH, PUBLISH, SUBSCRIBE, PING over a plain socket, for a local network); no library is bundled, so nothing can clash with Mohist or the other BSP mods. It reconnects by itself, waiting longer after each failure, and pings a quiet connection to notice a dead one.
- Every message also goes into the MySQL message table with a unique id (new column `uid`, added to existing tables on start); a server handles each id once, whichever copy arrives first. While Redis is connected the table is read only every `safetyPollSeconds` (30); while it is down, every `pollSeconds` as before. On stop, Redis's queue is sent before the database worker's.
- `/rg storage status` gains a Redis line (connected, receiving only, sending only or down; sent, received, not sent; last error). `docs/NETWORK.md` describes the four-machine setup.
- Three GameTests (54 in all) against an in-process fake Redis on a real socket: messages arrive before the table is written and the table copy is dropped; an outage falls back to the table and the link comes back by itself; a wrong password is reported.

### Changed (2026-10-12)
- **Claims are paid from the BSP Core bank** in Shatter Pieces, no longer with coins from the inventory (owner decision D31). Prices, costs per block and refunds are in pieces and are now `long`s. `ClaimService.purchasePlot`, `purchaseOpen` and `enlarge` answer through a callback, because on a network the bank answers after a database round trip: meanwhile the plot or outline is held for the buyer and the buyer cannot start a second purchase; if the plot changed before the money arrived, the money is paid straight back. `abandon` pays the refund into the owner's bank, also when an admin removes the claim of an offline player. The screens and commands say "pieces".
- Built against **BSP Core 1.0.1**; the BSP Core adapter now goes through BSP Core's `api` package (admins, coins, dimensions, bank, Anti Totem zones). A few calls still use BSP Core internals where the api has no equivalent yet (every placed totem, steal radius, the totem block, test helpers). `tools/setup_test_server.sh` copies the slim jar of the version in `gradle.properties`.
- BSP Core's Anti Totem zones count as well (owner decision D32): `teleport-in`, `teleport-out`, `set-home`, `totem-place` and `totem-drop` answer no where either a region or a BSP Core zone blocks them, so BSP Teleport and BSP Core need only ask Region Guard.

### Fixed (2026-10-12)
- A stopping server now sends everything the storage worker still has queued (up to 5 seconds) before closing the database, instead of interrupting it. Landing messages published during shutdown (BSP Teleport's SHUTDOWN trigger moves, its request TR2) used to be lost, so players reached the holding server at its default spot.

### Added (2026-10-12)
- Three GameTests (51 in all): queued messages are sent on stop; a BSP Core zone denies the matching flags; a charge in flight holds the plot, refuses a second purchase and pays back when the price changed.

### Added
- The TELEPORT tab now lists a region's triggers with a remove button and adds new ones (pick ENTER, EXIT, TIMER or SHUTDOWN, type the destination). The MAP tab's OTHER SERVERS button overlays other servers' regions from the shared storage in grey (read-only, prefixed with the server name).
- Region triggers: `/rg trigger add <region> <enter|exit|timer|shutdown> [<seconds>] <here | world x y z | server/world x y z> [message]`, `/rg trigger list|remove`. A trigger fires `RegionTriggerEvent` for the player; same-server destinations are carried out by Region Guard, cross-server ones are left to BSP Teleport (which cancels the event to take over) and are also published through the shared storage as kind `trigger`. Shutdown triggers fire for everyone inside when the server stops.
- The public API gained `regions()` (regions at a position, flag answers, flag registration, exemptions), `mines()` (is mine, reset, countdown) and `publish(...)`; see `docs/API.md`.
- One more GameTest (47 in all): an enter trigger moves the player, a cancelling listener takes it over, the API facade answers.
- Shared storage for several servers (`storage` section of the server config, `mode = mysql`): every region of this server is mirrored to MySQL under its `serverId` as it changes (removals leave tombstones); on start each world is reconciled with the database (newer rows replace, tombstones remove, unknown local regions are pushed); messages between servers travel through a table polled every `pollSeconds` and arrive as `RemoteMessageEvent`; a mine reset on one server resets the mine with the same id in the same world on the others (`syncMineResets`). No database driver is bundled: BSP Core's full jar provides one on Forge servers and Mohist ships its own. `/rg storage status|sync|servers`.
- Three more GameTests (46 in all) on an in-memory backend: push and tombstones, reconcile, messages between two servers with a synced mine reset.
- LuckPerms on Mohist: the bridge now also reaches the Bukkit edition of LuckPerms, found through Bukkit's plugin manager and used by reflection, so a Forge mod on a Mohist backend sees the same groups the plugins do. Verified on a local Mohist 1.20.1 server with LuckPerms Bukkit 5.5.87: `rg ranks` lists the groups. The startup log says which edition was reached.
- `/rg ranks [players]`: a console-friendly check of the LuckPerms bridge and a player's groups.

### Changed
- The world-rule patches no longer redirect block-setting calls (Mohist rewrites those for its Bukkit events, which made the server fail to start). Fire spread is cut at the ignite-odds check, fire burning at the burn method, lava fire at lava's random tick, ice and snow at the biome's freeze and snow checks, grass and mycelium at the propagate check, farmland drying at its random tick, Frost Walker at its entry, and natural regeneration through Forge's heal event marked by the food tick. Only `falling-blocks` still redirects a call and is skipped on Mohist.
- LuckPerms verified against the real LuckPerms Forge mod in the development server: groups are listed, a player's inherited groups resolve, rank-gated entry (`allowed-ranks`) and claim limits by rank (`claims.limitsByRank`) work. The jar is a dev-run-only dependency (`libs/`, not shipped); `tools/make_luckperms_dev_jar.py` explains how it is prepared. One GameTest (43 in all) covers it and skips itself when LuckPerms is absent.
- Automated mines. `/rg mine create <id>` turns a region into a mine (anyone may dig inside, nobody may place); `/rg mine add <id> <block> <weight>` builds the fill table, `/rg mine interval <id> <seconds>` sets the timer, `/rg mine spawn <id>` where players inside are moved on a reset, `/rg mine shell <id> <radius>` wraps the mine in a region where only the mine itself can be dug and nothing placed, `/rg mine reset <id>` and `/rg mine info <id>`. A reset moves the players inside out, replaces every block in the mine, lays the heaviest block everywhere and grows the others as veins from a new seed each time, and is written in batches (`mines.blocksPerTick`) so big mines do not freeze the server. Players within 48 blocks are warned at 60 and 10 seconds (`mines.warnSeconds`) and told when the reset happens. The MINE tab in the admin screen edits all of this and shows the countdown; `MineResetEvent` fires on the Forge bus.
- Four more GameTests (42 in all): fill proportions and vein clustering with a fresh layout per seed, reset with player relocation and scheduling, the shell rule, the timer.
- The player claim screen (`/claim gui`, or sneak and right-click with the plot tool): a map of the claim and its surroundings, members as chips with add and remove, the flags the owner may set as plain on/off switches worded as sentences ("strangers may open chests"; grey means follow the area's rule), message editing, PLOT TOOL, ENLARGE, TELEPORT and GIVE UP with a second click to confirm. Arrows step through a player's claims. With no claim it lists what is for sale.
- Admin plots carry their own list of flags the buyer may set (`/rg plotflags <id> <flags|default>`, or the SALE tab); open areas may too, and their plots inherit it. The config list is the default.
- A Shatter Totem cannot be placed in a claim whose rules could stop a steal (entry, exit, building, interaction, PvP off, invincibility, pearls), and such a rule cannot be set on a claim that already holds a totem.
- Two more GameTests (38 in all): the totem restriction and per-plot flag lists with the claim screen's server side.
- Land claims. Admins put a region up for sale at a fixed price (`/rg sale <id> <price>`, or the SALE tab) or open an area where players outline their own plot and pay per block of ground (`/rg open <id> <costPerBlock> [minSize] [maxSize]`). Players use `/claim list`, `/claim tool` (the wand), `/claim quote`, `/claim buy <plot or area>`, `/claim enlarge`, `/claim abandon`, `/claim add|remove <player>`, `/claim flag` (a config list of flags an owner may set) and `/claim here`. Rules: plots 5 to 64 blocks a side by default, full world height, never overlapping another claim; a limit of claims per world per player, raised by LuckPerms group (`claims.limitsByRank`); abandoning refunds half (`claims.abandonRefundPercent`), an admin plot goes back on sale at its old price, an outlined plot disappears; no claims in PvP worlds unless `claims.allowInPvpWorlds`. Money moves through BSP Core's wallet in the same server step as the claim.
- The claim API for BSP Core's shop screen: `RegionGuardApi.get().claims()` with listings, quotes, purchase, enlarge and abandon, each answering with a `ClaimResult`; `ClaimPurchasedEvent` and `ClaimAbandonedEvent` on the Forge bus. See `docs/API.md`.
- Wiping claims per world, with safeguards: `/rg claims wipe` reports what would go and gives a one-time code; `/rg claims wipe <code>` within a minute writes a backup under `<world>/bsp_region_guard/` and removes that world's claims only; `/rg claims backups` and `/rg claims restore <file>` put them back; BSP Core admins only; every admin online is told. `/rg claims list` shows the claims of the world.
- Three more GameTests (36 in all): buying and abandoning a plot, the open-area rules (size, overlap, limit, enlarge, refund), the wipe flow with backup and restore.
- Regions drawn in the world: wire-edged boxes with faint faces in the type's colour (turquoise protected, violet claims, gold mines and plots for sale) and the id floating above, within 256 blocks. Admins see every region; other players see their own claims and the plots for sale. The B key (configurable, "Show regions") toggles them. The wand selection is drawn the same way in gold, with both corners marked; the old particle outline is gone.
- Clients get the region list on login, after a world change or respawn, and whenever a region changes, so the drawing and an open screen stay current.
- One more GameTest (33 in all): players receive only their own claims and the plots for sale.
- The admin screen: `/rg gui`, or sneak and right-click with the Region Wand. The world's regions in a searchable, filterable list on the left; the selected region on the right with tabs MAP (a top-down map of the loaded terrain with every region drawn in its type's colour, click to select, double-click for FLAGS, wheel to zoom), FLAGS (every flag grouped by category with an inherit / allow / deny switch or an edit box, hints under the names, a "set only" filter), ACCESS (owners and members, add by name, remove), RANKS (LuckPerms groups as tick boxes, or typed), TELEPORT (teleport and respawn points set to where you stand, the message after teleport), INFO (name, type, priority steps, parent, bounds, version); SALE and MINE are placeholders for Phases 4 and 5. Buttons: new region from the wand selection, give wand, teleport, redefine from the selection, copy bounds to the selection, delete with a second click to confirm. Every change goes to the server at once; only operators and BSP Core admins may open it.
- Network channel `bsp_region_guard:main` with the list, detail and action packets.
- Flags carry a category and a one-line hint, used by the screen and the docs.
- Two more GameTests (32 in all): packet round trips and every screen action against the region store.
- The Shatter Totem rule: near another player's placed totem (within BSP Core's steal radius, or `totem.exemptRadius`) in a world where totems live, regions do not stop a raider: entry and exit, interaction, PvP and, unless `totem.exemptBuild` is off, breaking and placing all go ahead. The totem's own owner gets no exemption. Claims cannot be created in PvP worlds (`claims.pvpWorlds`, defaulting to BSP Core's totem dimensions) unless `claims.allowInPvpWorlds` is set.
- API: `RegionQuery.addExemption` lets another mod lift region rules for a player in a spot, the way the totem rule does.
- Three more GameTests (30 in all): raider exemptions by flag, owner and distance; a raider walking into an entry-deny region near a totem; claims refused in PvP worlds.
- The flags that need patches into Minecraft's own code (mixins), in eight groups each with a switch in the server config under `mixins`: **fire** (`fire-spread`, `fire-burn`, `lava-fire`), **fluids** (`water-flow`, `lava-flow`, checked where the fluid would arrive, so nothing flows into a region from outside), **weather** (`snow-fall`, `snow-melt`, `ice-form`, `ice-melt`, `frosted-ice-form`, `frosted-ice-melt`), **growth** (`grass-growth`, `mycelium-spread`, `vine-growth`, `mushroom-growth`, `sculk-growth`, `leaf-decay`, `rock-growth`, `soil-dry`, `coral-fade`, `copper-fade`), **dispensers** (`dispenser-use`), **fallingBlocks** (`falling-blocks`: sand and gravel landing inside drop as items), **eggs** (`block-trampling` for turtle eggs), **hunger** (`natural-hunger-drain`, `natural-health-regen`). 28 small patches, all server side; a world with no region in the column answers from one lookup.
- Seven more GameTests (27 in all): fire spread and burn, water flow across a border, falling sand, dispensers, grass spread and leaf decay, coral fade, hunger drain.
- Movement rules, checked every tick as players cross region borders: `entry` and `exit` (members may always pass unless the flag targets them; a refused move puts the player back), `exit-via-teleport`, `exit-override`, `entry-deny-message`, `exit-deny-message`, `allowed-ranks` (LuckPerms groups; skipped with a warning when LuckPerms is absent), `greeting`, `farewell`, `greeting-title`, `farewell-title`, `notify-enter`, `notify-leave` (to BSP Core admins), `game-mode` (restored on leaving, even across a relog), `time-lock` and `weather-lock` (what the player sees), `heal-delay`/`heal-amount`/`heal-min-health`/`heal-max-health`, `feed-delay`/`feed-amount`/`feed-min-hunger`/`feed-max-hunger`, and `spawn` (where a player who dies inside respawns).
- API: `RegionEnterEvent` and `RegionLeaveEvent` on the Forge bus, one per region crossed, cancellable to refuse the move. The first hooks for BSP Teleport.
- Four more GameTests (20 in all): entry refusal and member entry, exit refusal with teleport and override, forced game mode, heal and feed timers.
- Enforcement of every flag that Forge fires an event for: breaking, placing, using and interacting with blocks (doors, buttons and levers under `use`, containers under `chest-access`, anvils, beds, respawn anchors, dripleaf, lighters, boats and minecarts, tools that change blocks, buckets, bone meal, trampling), damage (`pvp` checked where both players stand, `mob-damage`, `invincible`, `fall-damage`, `firework-damage`, `damage-animals`), hanging entities and armour stands, riding, sleeping, ender pearls and chorus fruit, item drop and pickup, experience drops, splash potions and fireballs, explosions (an explosion starting inside a forbidding region is cancelled; one reaching in from outside spares that region's blocks and entities; `creeper-explosion`, `tnt`, `wither-damage`, `ghast-fireball`, `other-explosion`), mob griefing (`enderman-grief`, `snowman-trails`, `ravager-grief`, `enderdragon-block-damage`), `mob-spawning` and `deny-spawn`, `lightning`, `crop-growth`, `pistons` (the piston, every moved block and every landing spot), `blocked-cmds`, `allowed-cmds`, `send-chat`, `receive-chat`.
- Refusals show the region's `deny-message` on the action bar, at most once every 1.5 seconds per player.
- BSP Core admins bypass every player-action check; `/rg bypass` switches that off and on for yourself.
- Six more GameTests (16 in all): breaking by stranger and member, explosion pruning and cancelling, spawning and `deny-spawn`, PvP at either end, item drop and pickup, pistons.
- Regions: named cuboids per world with priority, parent, owners, members, allowed ranks and typed flags, saved with the world and indexed in 64-block cells for fast lookup. A per-world global region holds defaults.
- The full flag set (every WorldGuard flag plus the BSP additions), resolved by priority with deny beating allow, parent inheritance, the `build` fallback family and member bypass. Not enforced yet: this release only stores and resolves them.
- `/rg` commands: `wand`, `pos1`, `pos2`, `select`, `define`, `redefine`, `remove`, `list`, `info`, `here`, `flags`, `flag`, `addowner`, `removeowner`, `addmember`, `removemember`, `addrank`, `removerank`, `priority`, `parent`, `type`, `rename`, `teleport`. `/regionguard` is an alias. Operators and BSP Core admins may use them.
- Region Wand: left-click a block for the first corner, right-click for the second; the selection is outlined with particles while the wand is held.
- Forge GameTests for containment, the index, every resolution rule and save/load (`./gradlew runGameTestServer`, 10 passing).
- Project skeleton: Forge 47.4.10 build with BSP Core as a mandatory dependency, BSP Teleport optional, LuckPerms API compile-only, mixin wiring (no mixins yet), server config, GameTest run configuration, and the `api` package entry point.
