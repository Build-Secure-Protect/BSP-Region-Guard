# BSP Region Guard 0.3.0: the plot map

Everything added since 0.2.0. For Minecraft 1.20.1 (Forge 47.4.10 and Mohist). Requires **BSP-Core 1.1.0** or newer (tested with 1.3.0).

> **Update the server and every player together.** 0.3.0 talks to the game client in a new way (network protocol 6), so a player on an older version can't join a 0.3.0 server.

## New: the plot map

A full-window map for setting up and managing plots, made for servers with many admin-made plots for sale. Open it with `/rg plotmap` or the new **PLOT MAP** button under the admin screen's region list.

- **The map:**
  - It shows the world itself, drawn the way a Minecraft map draws it.
  - Zoom from 1 pixel a block up to 16, with a block grid and the exact x/z under the cursor, so you can place to the block.
  - Ground you've never visited is read from the saved world; ground that was never generated stays dark and is never generated.
  - Plots for sale, claims and open areas are drawn in their colours.
- **Finding plots:** the folding **PLOTS** card searches and filters (all, for sale, claims, open areas). Click a plot to snap the map to it.
- **COPY a plot:**
  - Pick a plot and press COPY. The copy follows your mouse; click to drop it, then drag it into place.
  - The toolbar under it gives the copy a new name and sets its priority and height (▲/▼). **ROTATE** (or R) turns it.
  - **SNAP** lines its edges up with neighbouring plots; hold SHIFT to place freely.
  - CONFIRM makes a new **plot for sale** carrying the original's size, flags, banned blocks, the buyer's allowed flags, price, messages, triggers and priority. Owners and members are never copied.
- **Move a plot:**
  - Plots are **locked** until you press **UNLOCK**. Then drag it into place and CONFIRM; it locks again.
  - CANCEL puts it back.
  - A claim someone owns asks before it moves.
- **Plots inside plots:**
  - A plot may sit wholly inside another plot, claim or open area when its priority is higher; there, its own flags win.
  - Partial overlaps are refused.
  - The copy shows red, with the reason, until it fits.
- **UNDO and REDO:** the last 15 pastes and moves, with no time limit.
- **Other worlds:** shown read-only in a gold frame. Only the world you stand in can be edited.

## New: moving a plot's blocks

- **MOVE BLOCKS / COPY BLOCKS:** switch it on in the toolbar and the plot's build goes with it:
  - blocks, chest contents, signs and other block entities
  - item frames, armour stands, paintings and animals
  - everything turned with the plot
- **Big plots:** moved a little each tick with a progress line, so the server keeps running. Players standing inside go along.
- **The old spot** is rebuilt as natural ground from the world's generator: its shape, water and caves, with the biome's grass, sand, deepslate and bedrock. Trees, ores and structures don't regrow.
- **Backups first:** both spots are saved before anything is overwritten, and UNDO puts both back.
- **BACKUP and BACKUPS** on a plot's card save its blocks at any time and list its backups; click one twice to restore it. By command: `/rg plot backup <plot>`, `/rg plot backups <plot>`, `/rg plot restore <file>`.
- Copying a plot with its blocks copies chest contents too, which suits stamping out pre-built shops.

## Other changes

- The admin screen's buttons under the region list are now **+ NEW**, **WAND** and **PLOT MAP**.
