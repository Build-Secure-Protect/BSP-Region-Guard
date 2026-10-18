# BSP Region Guard 0.1.0: first release

The first public release of BSP Region Guard for Minecraft 1.20.1 (Forge 47.4.10 and Mohist), made for the Build Secure Protect modpack. Requires **BSP-Core 1.1.0** or newer. Works with **BSP-Teleport**, **BSP-RankUp** and **LuckPerms** when they are installed.

## Regions and protection
- **Regions:** boxes of the world with an id, a priority, an optional parent, owners and members. Overlapping regions resolve by priority, and children inherit unset flags from their parent.
- **World default:** every world has a `__global__` region whose flags apply everywhere no other region decides.
- **Region Wand:** left-click one corner and right-click the other to outline a region. `/rg wand` gives one.
- **Flags:** more than 100 WorldGuard-style flags in seven groups: protection, mobs and explosions, natural events, movement, map making, chat and commands, and BSP's own. They cover breaking and placing, chests, doors, PvP, explosions, fire, fluids, weather, growth, entry and exit, greetings and titles, game mode, healing and feeding, time and weather locks, blocked commands, and more.
- **Mixins:** 28 small, server-side patches cover what Forge has no event for, such as fire spread, fluids, snow and ice, growth, dispensers, falling blocks and hunger. Each group can be switched off in the server config, and all of them work on Mohist.
- **Admin bypass:** BSP-Core admins pass every rule; `/rg bypass` switches that off for testing.
- **Commands:** `/rg` covers everything the screen does, with tab completion.

## The admin screen
- `/rg gui`, or sneak and right-click with the wand.
- **Layout:** a region list with search and type filters on the left. On the right are tabs: MAP, FLAGS, ACCESS, RANKS, SALE, MINE, TELEPORT, INFO, and BORDER for the world default.
- **MAP tab:** a top-down map of every region in its type colour. Click one to pick it, and zoom with the wheel.
- **FLAGS tab:** every flag with a one-line hint and its inherited value. Changes save as soon as you make them.
- **New regions:** + NEW turns the wand outline into a region. REDEFINE moves an existing one, SELECT loads its corners back into the wand, and TELEPORT goes to it.
- **Size:** screens are drawn twice as big by default (client setting `ui.scale`, from 1 to 4) and fit themselves to the window.
- **Translations:** all screen text is in the language file, ready for translation.

## Seeing regions in the world
- Regions are drawn as wire boxes with see-through faces, coloured by type, with labels.
- Admins always see them. Players see their own claims by pressing **B**.

## World border
- **One square border per world**, kept on the world default (`/rg border set <width> [x z]`).
- **At the edge:** players are warned 10 blocks before it and pushed back at it. Anyone found outside is moved inside, onto ground where they can stand.
- **Overworld wall:** in the Overworld, Minecraft's own wall is set to match so it shows.
- **Teleports and homes** outside the border are refused.

## Land claims
- **Plots for sale:** admins outline plots and set a price on each, and can choose which flags the buyer may set.
- **Open areas:** admins set a price per block. Players outline their own plot inside (5 x 5 up to 64 x 64 by default, full height); plots may touch but never overlap.
- **Limits by rank:** claims per world follow the player's LuckPerms group (3 by default, `vip:5`, `mvp:8` out of the box).
- **Enlarging and giving up:** enlarge a claim later by paying for the added ground. Give it up for half the price back; an admin plot then goes back on sale.
- **The claim screen** (`/claim gui`, or sneak and right-click the plot tool):
  - a map of your claim and its surroundings, with arrows through your claims
  - owners and members
  - rules worded as plain sentences ("strangers may open chests"), each on, off, or following the area's rule
  - greeting and farewell messages
  - TELEPORT, ENLARGE, and GIVE UP
- **Totem rules:** claims are not allowed in PvP worlds. A claim may not hold a Shatter Totem while any of its rules would hinder a steal.
- **Claim wipe:** admins can wipe one world's claims, from `/rg claims wipe` or from the admin screen. You type back a one-time code within a minute, a backup file is written first, `/rg claims restore` puts it back, and every admin online is told. Claims on other worlds and servers are never touched.

## Mines
- **Fill table:** any region can become a mine with a weighted fill table. The heaviest block fills the mine, and the others grow as fresh veins at every reset, so no two resets look alike.
- **Resets:** on a timer per mine or at the press of RESET NOW. Warnings come at 60 s and 10 s, and players inside are moved to the mine's spawn point.
- **Shell:** a ring around the mine where only the mine's blocks may be dug.
- **Rank-gated entry** and a MINE tab in the admin screen.

## Ranks (LuckPerms)
- **RANKS tab:** pick the LuckPerms groups that may enter a region (flag `allowed-ranks`). Members always pass.
- **LuckPerms editions:** works with the Forge mod and with the Bukkit plugin on Mohist.

## Region triggers
- **What they do:** move players when they enter or leave a region, every so many seconds while they are inside, or when the server shuts down. The destination is a spot on this server or a world on another server.
- **Setting them up:** set them from the TELEPORT tab or `/rg trigger`, with tab completion for destinations. Other mods can listen to and cancel each firing.

## Several servers
- **Shared storage:** an optional MySQL mirror shares every server's regions. Each server owns its own; the others can see them.
- **Redis:** carries messages (mine resets, triggers, travellers) in milliseconds, with the MySQL table as a safety net. Region Guard bundles no database or Redis library, so nothing clashes with Mohist or other mods.
- **Server switcher:** the admin screen can look at any other server's regions, read-only, with this server laid underneath in grey.
- **Edge links:** link an edge of one server's world border to an edge on another server's world. A player who walks into a linked edge gets a TRAVEL / STAY window, then arrives at the matching point on the other server, 16 blocks inside and on the ground. Links work both ways.
- **Synced mine resets:** a mine with the same id on two servers can reset together.

## Works with BSP-Core (required)
- **Payments:** claims are paid from the BSP-Core bank in Shatter Pieces, and refunds go back to it.
- **Land Office:** finishing a plot outline opens BSP-Core's Land Office with the ground priced, and players buy and enlarge there.
- **Admins:** BSP-Core's admin list decides who is a Region Guard admin.
- **Shatter Totem rule:** regions never stop a steal near another player's placed totem. BSP-Core's Anti Totem zones and Region Guard regions both apply to totem placing, teleports and homes.

## Works with BSP-Teleport (optional)
- **Teleport rules:** BSP-Teleport asks Region Guard's `teleport-in`, `teleport-out` and `set-home` flags before teleports and homes.
- **`/rtp`:** picks spots inside Region Guard's world border.
- **Travel between servers:** BSP-Teleport carries out cross-server triggers and edge-link travel.

## Works with BSP-RankUp (optional)
- **Ranks unlock land:** RankUp's ranks are LuckPerms groups, so ranking up opens rank-gated regions and mines and raises the player's claim limit.
- **Server name:** on a network, RankUp takes Region Guard's server name.

## For developers
- **Public API** (`com.mrgregles.bsp_region_guard.api`, version 5):
  - ask whether a flag allows something at a spot, read flag values, region bounds and the world border
  - register your own flags and exemptions
  - claims, plus mine resets and countdowns
  - cross-server messages
- **Events:** enter and leave, triggers, claims bought and abandoned, mine resets, remote messages.
