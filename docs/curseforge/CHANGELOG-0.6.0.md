# BSP Region Guard 0.6.0: plot markers, the MINE FILL window and cleaner outlines

Everything added since 0.5.0. For Minecraft 1.20.1 (Forge 47.4.10 and Mohist).

> **Now requires BSP-Core 1.4.6 or newer** on the server and every player; with an older BSP-Core, this Region Guard won't load. Update the server and every player together: 0.6.0 talks to the game client in a new way (network protocol 9), so a player on 0.5.0 can't join a 0.6.0 server.

## New: plots for sale stand out

- **Every plot for sale and open area shows to every player** in its own colour, with a marker over its middle carrying its name. Once the plot is bought, the marker and outline disappear, so they're never in the new owner's way.
- **Four markers**, or just the outline:
  - **ARROW** (the default): a big arrow pointing down at the ground, 20% see-through, with the name above it. It bobs gently.
  - **SPHERE:** a glassy sphere with the name's letters circling it.
  - **BEACON:** a thin beam of light rising from the plot, pulsing slowly, with a turning crystal.
  - **SIGN:** a big floating board with FOR SALE, the name and the price.
- **The PLOT LOOK window** (LOOK on the SALE tab, or on the region map's card in PLOT mode):
  - the marker and its colour (eight colours or any hex code);
  - the marker's size and the name's size (0.5x to 3x);
  - how high it floats above the ground, never above the plot's top.

  A preview shows the plot and turns as you drag it.
- A copied plot takes the same look and shows its own name.
- `/rg look <id>` shows a plot's look, and `/rg look <id> <part> <value>` changes one part.
- Markers show within 96 blocks and only what's in view is drawn, so they stay light on frame rate.

## New: teleport after buying

- After buying a plot at a Land Office, or buying an outlined plot, a window asks whether to **TELEPORT** to the middle of the new plot (or to its teleport point) or **STAY**.
- Making a plot bigger doesn't ask.

## New: the MINE FILL window

- **EDIT FILL** on the MINE tab, or on the region map's card in MINE mode, opens it.
- **Left:** the mine's blocks, each with a box for its share in percent (down to 0.5% for a rare ore), and the mine's **total** at the bottom.
- **Right:** a creative-style block browser with the creative tabs, search, and `@mod` for one mod's blocks. Blocks already in the mine are framed in gold; click a block to add it.
- **REST:** mark one block to fill whatever share is left. It's the mine's base, and the others grow as veins through it. Without a REST block, the biggest share is the base, as before.
- **SAVE** works once the total is exactly 100%. Until then the window says how much is left, or how much too much.
- Existing mines open with their old weights turned into percentages. A mine takes up to 24 blocks.
- The MINE tab now shows each block's share in percent and marks the REST block.

## Changed

- **Region outlines show while you hold the Region Wand** (in either hand) and disappear when you put it away, so finished regions never get in the way. The gold outline of what you're selecting does the same.
  - The **B** key still shows outlines without the wand, and now starts off for everyone.
  - Players holding the plot tool see their own claims.
  - Plots for sale always show.
- **Renaming a region changes its id too.** The name "Market Hall" gives the id `market_hall`, so the old id is free again.
  - Everything the region holds comes along, including a mine's shell, child regions, plot backups and the map's undo history.
  - A name whose id is already taken is refused. The world default keeps its id.
- **The region list shows names** instead of ids. Search finds either.
- **Buttons answer you:** every button in the admin screen lights up when clicked, and the server's answer shows in the line along the bottom of the screen.
- **Lighter drawing:**
  - Regions out of view aren't drawn at all.
  - Outlines are drawn in a couple of batches instead of several draws per region.
  - The region map draws its terrain in large squares, a few dozen draws instead of about two thousand.
  - Region lists are sent once per server tick, however many regions changed.

## Fixed

- **RESHAPE on the region map couldn't be left**, and Esc didn't close the map. The reshape bar now has **CANCEL**, and Esc stops reshaping first.
- MOVE SPAWN, RESET NOW and the other MINE tab buttons seemed to do nothing. They worked, but their answer was hidden behind the screen. It now shows in the screen.
- **Texts that ran out of their boxes:**
  - a long BUYER MAY SET list on the SALE tab;
  - the server name on the BORDER tab;
  - VERTICAL FILL on the map's card;
  - three-digit priorities.

## For developers

- **API:** unchanged (version 6).
- **Region ids can now change.** Look a region up again by id rather than keeping an id for a long time, or listen for the region being removed and added.
