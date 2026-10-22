# BSP Region Guard 0.4.0: the region map

Everything added since 0.3.0. For Minecraft 1.20.1 (Forge 47.4.10 and Mohist). Requires **BSP-Core 1.1.0** or newer (tested with 1.3.0).

> **Update the server and every player together.** 0.4.0 talks to the game client in a new way (network protocol 7), so a player on an older version can't join a 0.4.0 server.

## New: the region map

The plot map has grown into one map for every region, plot and mine. Open it with the admin screen's **MAP** button (it opens on the selected region), `/rg map` or `/rg plotmap`.

- **Modes:** **REGION**, **PLOT** and **MINE** buttons along the top.
  - REGION shows every region.
  - PLOT shows plots for sale, claims and open areas; MINE shows mines and their shells.
  - The other kinds are dimmed and can't be picked, and the list follows the mode.
- **Tools:** **PICK**, **DRAW** and **RESHAPE** in every mode.
  - **DRAW:** drag a box, or click two corners. A card asks for an id, a priority and a height, plus a price in PLOT mode. CREATE makes a protected region, a plot for sale or a mine, depending on the mode. A new mine starts 16 blocks below your feet rather than the whole height of the world.
  - **RESHAPE:** drag the handles on the picked region's sides and corners, set its height, then DONE.
- **Panning:** hold **SPACE** and drag, or drag with the right mouse button. So you can click one corner, pan across the map and click the second.
- **The region's card:**
  - Its name and priority in every mode.
  - REGION mode adds the type; PLOT mode adds copy, lock and backups.
  - Buttons open its FLAGS, ACCESS, TELEPORT, INFO, SALE or MINE tab in the admin screen, and Esc there brings you back to the map.
  - VERTICAL FILL, RESHAPE and DELETE (click twice).
- **In MINE mode:** RESET and SHELL for the picked mine.
- **UNDO and REDO** now cover regions drawn and deleted on the map too. An undone delete comes back with its flags, owners and everything else.

## New: regions without the wand

- **+ NEW** with no wand selection, or `/rg define <id>` / `/rg create <id>` without one, makes a region with no corners yet. It protects nothing until it has some.
- The map then opens so you can click its two corners: no flying to the spot.
- Until then the list shows it as NO CORNERS. Its flags, owners and ranks can be set straight away.

## New: size editing and vertical fill

- **The INFO tab's SIZE block:**
  - The corners as six numbers: click one, type, Enter saves.
  - **VERTICAL FILL** stretches the region from the bottom to the top of the world.
  - **SET CORNERS ON MAP** and **USE WAND SELECTION**.
- **Commands:** `/rg vfill <id>` (or `/rg vfill` for your wand selection) and `/rg redefine <id> <x1 y1 z1> <x2 y2 z2>` for typed corners.

## Changed

- **The admin screen** fills the window and is drawn a little smaller, so the list and the tabs have more room.
- **The map's cards** are a little smaller and fold away with ▾ / ▸. UNDO and REDO sit in the top bar, where they no longer cover anything.
- **The admin screen's small MAP tab** now shows only while you look at another server's regions; the region map covers this server.

## Fixed

- Corners clicked on the map could land a block or two off. Corner snapping is now off at first and never snaps to the region's own old box. A white frame shows the exact block a click will use, and the corner bar shows its x and z.
- Protected regions and mines didn't show on the plot map.
- After a region changed size, quick checks still looked for it where it used to be. Nothing was protected wrongly, but those checks were slower.
