# BSP Region Guard 0.2.0: banned blocks

Everything added since 0.1.0. For Minecraft 1.20.1 (Forge 47.4.10 and Mohist). Requires **BSP-Core 1.1.0** or newer (built and tested against 1.3.0).

> **Update the server and every player together.** 0.2.0 talks to the game client in a new way (network protocol 5), so a player still on 0.1.0 can't join a 0.2.0 server.

## New: banned blocks per region

Admins can now ban chosen blocks and items in any region, from spawn down to a single shop, or across a whole world through the world default.

- **Four rules for each banned block or item:**
  - **PLACE:** it can't be placed. It stays in the player's hand, and they may still store it in a chest inside. Banned buckets can't be emptied.
  - **USE:** it can't be right-clicked, and a banned item does nothing in hand (flint and steel, end crystals, a machine's screen).
  - **BREAK:** it can't be broken.
  - **CARRY:** it isn't allowed in the region at all, which also covers the other three.
- **The BANNED tab:** a new tab in the admin screen opens the banned blocks editor.
  - A grid of the region's banned blocks sits on the left, and Minecraft's own creative tabs on the right, vanilla and modded.
  - Pick a rule on the bar, then drag blocks across, or shift-click them.
  - Right-click a banned block to step through its rules, or hover it for its four switches. Drag it back, or press x, to allow it again.
  - **Search** works as in JEI: `@create` lists only Create's blocks, `@create drill` narrows that down, and plain words search every name and id.
  - **BAN WHOLE MOD** bans every block of the mod you last pointed at, as one entry.
  - Every change saves at once.
- **Carrying banned things in:**
  - Players carrying a CARRY item are turned back at the region's edge.
  - A window shows a picture of each banned thing they carry, with its name and where it is ("x 12", "in Shulker Box"), and tells them to leave it outside.
  - Everything the player carries is checked: inventory, armour, off-hand, Curios slots, and the contents of carried shulker boxes, bundles and backpacks.
  - Teleporting in while carrying one is refused, including BSP-Teleport's teleports and homes.
- **Inside a region with CARRY bans:**
  - banned items lying on the ground can't be picked up
  - no container hands one out, **the ender chest included**
  - anyone found holding one (they logged in there, or someone gave it to them) is moved just outside
- **Who it applies to:** everyone, the region's owners and members included. Only BSP-Core admins with bypass on pass, and Region Guard's Shatter Totem rule still keeps a steal possible.
- **How bans combine:** they add up. A block banned by a region, its parent or the world default is banned everywhere beneath, and a smaller region can't lift it.
- **Commands work too:** `/rg flag spawn deny-carry minecraft:tnt,@create`. The four flags are `deny-place`, `deny-use`, `deny-break` and `deny-carry`. Entries are ids, whole mods (`@create` or `create:*`) or tags (`#minecraft:beds`).
- **New config switch:** the container rule is a small patch into Minecraft's container screens, with its own switch, `mixins.containers` (on by default).

## Other changes

- Works with BSP-Core 1.3.0.
- The admin screen's tab row fits nine tabs.
