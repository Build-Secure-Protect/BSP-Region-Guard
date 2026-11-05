# Flags

Every flag BSP Region Guard knows, what it does and how it is enforced. Set them with `/rg flag <region> <flag> <value>`; `clear` removes a value. State flags take `allow` or `deny`. An unset flag inherits from the region's parent, then from the world's `__global__` region, then falls back to the default given here.

How a flag is resolved at a position: take the regions containing it, highest priority first (a child stands in for its parent); at the first priority level where any region sets the flag, that level decides, and `deny` beats `allow` at the same level. **Build-family** flags (marked *build*) have no fixed default: when none sets them, owners and members of the top protecting region may act and nobody else may; they also fall back to the `build` flag's value. On plots, claims and open areas, owners and members are judged by their own lists instead (see "Plot owner flags" below). BSP Core admins bypass every player-action check unless they turn it off with `/rg bypass`.

Enforcement column: **event** = a Forge event, works now. **mixin** = a small patch into Minecraft's own code, grouped under a config switch (`mixins` in the server config); works now. **tick** = checked every tick as players move, works now.

## Protection

| Flag | Default | What it controls | Enforced by |
|---|---|---|---|
| `passthrough` | deny | `allow` means this region does not protect its ground; it still carries its other flags | resolver |
| `build` | *build* | the umbrella for everything below marked *build* | event |
| `block-break` | *build* | breaking blocks | event |
| `block-place` | *build* | placing blocks, including beds and doors | event |
| `interact` | *build* | using blocks with a state or screen, villagers, animals, armour stands, bone meal | event |
| `use` | *build* | doors, trapdoors, fence gates, buttons, levers, note blocks, repeaters, comparators | event |
| `chest-access` | *build* | opening any inventory, including chest boats and minecarts | event |
| `ride` | *build* | mounting boats, minecarts, horses and other animals | event |
| `pvp` | allow | player against player, checked where both stand; arrows and pets count | event |
| `sleep` | *build* | beds | event |
| `respawn-anchors` | *build* | charging or using respawn anchors | event |
| `tnt` | allow | TNT and TNT minecart explosions | event |
| `vehicle-place` | *build* | placing boats and minecarts | event |
| `vehicle-destroy` | *build* | breaking boats and minecarts | event |
| `lighter` | *build* | flint and steel, fire charges | event |
| `block-trampling` | *build* | farmland (event); turtle and sniffer eggs (mixin) | event, mixin |
| `item-frame-rotation` | *build* | rotating items in frames | event |
| `firework-damage` | allow | firework rockets hurting players | event |
| `use-anvil` | *build* | anvils | event |
| `use-dripleaf` | *build* | big dripleaf | event |
| `damage-animals` | *build* | players hurting animals, villagers, golems, allays | event |

## Mobs, fire and explosions

| Flag | Default | What it controls | Enforced by |
|---|---|---|---|
| `creeper-explosion` | allow | creeper blasts; a blast starting inside is cancelled, one reaching in spares the region | event |
| `enderdragon-block-damage` | allow | the dragon breaking blocks | event |
| `ghast-fireball` | allow | fireballs and wither skulls exploding or hurting | event |
| `other-explosion` | allow | every other explosion: beds, crystals, modded | event |
| `fire-spread` | allow | fire igniting neighbouring blocks | mixin |
| `fire-burn` | allow | fire destroying a block (BSP addition) | mixin |
| `enderman-grief` | allow | endermen picking up or placing blocks | event |
| `snowman-trails` | allow | snow golems leaving snow | event |
| `ravager-grief` | allow | ravagers breaking blocks | event |
| `mob-damage` | allow | mobs hurting players | event |
| `mob-spawning` | allow | natural, spawner and egg spawns | event |
| `deny-spawn` | none | comma list of mob ids that may not spawn, such as `minecraft:creeper` | event |
| `entity-painting-destroy` | allow | non-players (arrows, explosions) breaking paintings | event |
| `entity-item-frame-destroy` | allow | non-players breaking item frames | event |
| `wither-damage` | allow | wither explosions and block breaking | event |

## Natural events

| Flag | Default | What it controls | Enforced by |
|---|---|---|---|
| `lava-fire` | allow | lava starting fires | mixin |
| `lightning` | allow | lightning strikes, including tridents and `/summon` | event |
| `water-flow`, `lava-flow` | allow | fluids spreading inside, or into the region from outside | mixin |
| `snow-fall`, `snow-melt`, `ice-form`, `ice-melt`, `frosted-ice-form`, `frosted-ice-melt` | allow | weather changing blocks | mixin |
| `mushroom-growth`, `leaf-decay`, `grass-growth`, `mycelium-spread`, `vine-growth`, `rock-growth`, `sculk-growth`, `soil-dry`, `coral-fade`, `copper-fade` | allow | blocks spreading, growing and decaying on their own | mixin |
| `crop-growth` | allow | crops, saplings, cactus, sugar cane, bamboo, nether wart, kelp, cave vines | event |

## Movement

| Flag | Default | What it controls | Enforced by |
|---|---|---|---|
| `entry`, `exit` | allow | `deny` keeps non-members out (or in); members and admins always pass; a refused move puts the player back a step | tick |
| `exit-via-teleport` | allow | leaving by teleport when `exit` is denied | tick |
| `exit-override` | false | always allow leaving | tick |
| `entry-deny-message`, `exit-deny-message` | text | messages for refused entry or exit. Entry refusals show in the middle of the screen as a title (a newline starts the subtitle). `{rank}` in `entry-deny-message` is replaced by the rank needed (D154) | tick |
| `notify-enter`, `notify-leave` | false | tell admins when someone enters or leaves | tick |
| `greeting`, `farewell` | none | chat message on entering or leaving | tick |
| `greeting-title`, `farewell-title` | none | title on entering or leaving; a newline separates the subtitle | tick |
| `enderpearl`, `chorus-fruit-teleport` | allow | those teleports, from or into the region | event |
| `teleport` | none | the point `/rg teleport <region>` goes to; `here` uses your position | event |
| `spawn` | none | where players respawn after dying inside | tick |
| `sale-group` | none | a plot's sale group (lower case); a Land Office set to CHOSEN lists the groups ticked for it (D143) | event |
| `teleport-message` | none | shown after `/rg teleport` | event |
| `border-warning` | none ("The edge of the world is N blocks away.") | set on the world default: shown `border.warnDistance` (10) blocks before the world border | tick |
| `border-message` | none ("You have reached the edge of the world.") | set on the world default: shown when a player is pushed back at the world border | tick |
| `teleport-in`, `teleport-out`, `set-home` | allow | BSP Teleport asks these before a teleport or setting a home; also denied inside a BSP Core Anti Totem zone that blocks the same rule | API (`regions().allows`) |

## Map making

| Flag | Default | What it controls | Enforced by |
|---|---|---|---|
| `item-pickup`, `item-drop` | allow | picking up and dropping items | event |
| `exp-drops` | allow | experience orbs from mobs and blocks | event |
| `deny-message` | "You can't do that here." | the refusal message | event |
| `invincible` | deny | `allow` makes players inside immune to all damage | event |
| `fall-damage` | allow | fall damage | event |
| `game-mode` | none | game mode forced while inside | tick |
| `time-lock`, `weather-lock` | none | what the player sees while inside; the real world is unchanged | tick |
| `natural-health-regen` | allow | regeneration from food (potions and golden apples still heal) | mixin |
| `natural-hunger-drain` | allow | hunger loss | mixin |
| `heal-delay`, `heal-amount`, `heal-min-health`, `heal-max-health` | none | timed healing (or damage when negative) | tick |
| `feed-delay`, `feed-amount`, `feed-min-hunger`, `feed-max-hunger` | none | timed feeding (or starving) | tick |
| `blocked-cmds`, `allowed-cmds` | none | comma lists of command names; allowed makes everything else blocked | event |

## Miscellaneous and BSP additions

| Flag | Default | What it controls | Enforced by |
|---|---|---|---|
| `pistons` | allow | pistons moving, including blocks pushed in from outside | event |
| `send-chat`, `receive-chat` | allow | chatting from inside, hearing chat while inside | event |
| `potion-splash` | allow | splash and lingering potions landing | event |
| `dispenser-use` | allow | dispensers and droppers firing into the region | mixin |
| `falling-blocks` | allow | sand, gravel and anvils landing in the region from outside | mixin |
| `allowed-ranks` | none | LuckPerms groups that may enter, on top of `entry`. A player above a listed rank on a BSP-RankUp ladder also gets in. A refused player is told the rank needed: the lowest listed rung on a ladder with where they stand, or the ranks on no ladder (D149 to D157) | tick |
| `totem-place`, `totem-drop` | allow | For BSP Core to ask about Shatter Totems; also denied inside a BSP Core Anti Totem zone that blocks the same rule (until the two zone systems merge) | API (`regions().allows`) |
| `deny-place`, `deny-use`, `deny-break`, `deny-carry` | none | banned blocks and items, comma separated: ids (`minecraft:tnt`), whole mods (`@create` or `create:*`), tags (`#minecraft:beds`). See "Banned blocks" below | event, tick, mixin |
| `mine-only-break` | deny | inside a mine's shell only the mine's own blocks may be broken | later (mines) |

## Mixin groups

Each group is one config switch under `mixins` in the server config and patches these vanilla methods, all at their entry so they match on Forge and on Mohist (which rewrites block-setting calls for its Bukkit events): **fire** (`FireBlock#getIgniteOdds` returns 0 inside, `FireBlock#tryCatchFire` cancelled inside, `LavaFluid#randomTick`), **fluids** (`FlowingFluid#canSpreadTo`), **weather** (`Biome#shouldFreeze` and `#shouldSnow` answer no inside, `SnowLayerBlock#randomTick`, `IceBlock#randomTick`, `FrostedIceBlock#tick`, `FrostWalkerEnchantment#onEntityMoved`), **growth** (`SpreadingSnowyDirtBlock#canPropagate`, the random ticks of vines, mushrooms, leaves, dripstone and amethyst, farmland, coral, the three copper block shapes, `SculkSpreader#updateCursors`), **dispensers** (`DispenserBlock#dispenseFrom`), **falling blocks** (`FallingBlockEntity#tick`, a redirect that is skipped on Mohist), **eggs** (`TurtleEggBlock#destroyEgg`), **hunger** (`Player#causeFoodExhaustion`; natural regeneration is Forge's `LivingHealEvent` during `FoodData#tick`). 28 patches in all, all server side.

## The Shatter Totem rule

Build Secure Protect's standing rule is that a totem must never be made impossible to steal. So in a PvP world (where totems can be placed), within BSP Core's steal radius of another player's placed totem, a region does not stop a raider: `entry`, `exit`, `interact`, `use`, `chest-access`, `enderpearl`, `chorus-fruit-teleport`, `teleport-in`, `teleport-out` always pass, `pvp` cannot be denied and `invincible` does not apply, and unless `totem.exemptBuild` is off in the config, `build`, `block-break`, `block-place`, `lighter`, `tnt`, the explosion flags and the vehicle flags pass too. The totem's owner gets none of this. World flags (fire, fluids, growth) are unaffected. Claims cannot be made in PvP worlds.

## Known gaps

- Mods that change blocks without a fake player (most quarries and contraptions do use one) are not stopped.
- Hoppers and pipes moving items across a border are not stopped; protect containers with `chest-access`.
- A tree outside a region may grow leaves into it.

## The world border

Each world's global region (the world default, `__global__`) stands for the whole world: flags set on it apply everywhere no other region decides. It also carries the world's **border**: a square set with `/rg border set <width> [<centerX> <centerZ>]` (centre 0, 0 when left out), shown with `/rg border`, removed with `/rg border off`. Players are warned `border.warnDistance` blocks before the edge and put back where they stood when they step over it; anyone found outside (after logging in, a teleport, a respawn or a smaller border) is moved just inside, onto a spot where they can stand. Riders are moved with their mount. Teleports (`teleport-in`, which BSP Teleport asks) and homes (`set-home`) outside the border are refused. Admins with bypass on are not stopped.

In the Overworld, Minecraft's own border is set to match so the wall shows (`border.vanillaWall`, on by default). Minecraft copies the Overworld's border into the other worlds, so while the Overworld has a Region Guard border the other worlds' Minecraft border is lifted and Region Guard's own border (if any) applies there. Use `/rg border` rather than `/worldborder`: Region Guard sets Minecraft's border again on every change and start.

An edge of the border can lead to an edge of a world on another server (edge links; setup in `docs/NETWORK.md`). Within a block of a linked edge the player is asked whether to travel (admins with bypass on too), and a player past it is pushed back without `border-message`.

## Plot owner flags (visitors, owners, members)

Plots for sale, claims and open areas have **three lists** of the player flags. Protected regions and mines keep one list.

| List | Who it is for | What inherit means | Who sets it |
|---|---|---|---|
| VISITORS | everyone who is not an owner or member | the parent region, then the world default, as for any region | the admin; the buyer for the flags marked BUYER |
| OWNERS | the plot's owners | **owners may**, for the build flags, so a world default that denies `build` never reaches owners; for the other player flags, "same as visitors" | the admin |
| MEMBERS | the plot's members | same as owners | the admin sets starting values; the owner changes them on the claim card or with `/claim flag <claim> <flag> <value> members` |

A flag the OWNERS list denies is denied for members too, and the owner can't change it. Members never get more than owners. The owner's own choices for members come before the admin's starting values, so switching "members may build" off also covers block-break. Another region at the plot's priority that denies a flag still stops owners and members, as before. A claim outlined in an open area takes the area's OWNERS and MEMBERS values where it has none of its own.

Player flags with three values: every *build* flag (build, block-break, block-place, interact, use, chest-access, ride, sleep, respawn-anchors, vehicle-place, vehicle-destroy, lighter, block-trampling, item-frame-rotation, use-anvil, use-dripleaf, damage-animals) plus `item-drop`, `item-pickup`, `enderpearl` and `chorus-fruit-teleport`. Everything else (PvP, fire, mobs, explosions, weather, messages) has one value, in VISITORS. PvP is checked for the spot rather than for one player, so it stays single.

Set them in the admin screen's FLAGS tab (VISITORS / OWNERS / MEMBERS buttons above the list) or with `/rg flag <plot> <flag> <allow|deny|clear> owners` (or `members`, or `visitors`, the same as leaving it out). The gold **BUYER** chip on each VISITORS row marks the flags the buyer may set; it replaces the typed list on the SALE tab (`/rg plotflags` still works).

Copying a plot copies all three lists and the BUYER chips; the owner's own member choices are not copied. Giving a bought plot back puts members back on the admin's starting values. Plots made before this version keep their behaviour: a build-flag deny on them, or inherited from a parent region without lists, is also put in OWNERS, except a claim's own deny on a flag its buyer could set (the buyer meant it for strangers). This happens once per region, whether it comes from the save file, the shared database or a wipe backup. Setting a claim back to a plot for sale with TYPE also resets the owner's member choices.

## Banned blocks

Four list flags keep chosen blocks and items out of a region's hands. They apply to **everyone**, owners and members included; only BSP Core admins with bypass on, and the Shatter Totem rule near a placed totem, pass. Unlike other flags the lists **add up**: a block is banned at a spot when any region covering it, any of those regions' parents, or the world default bans it, so a child region cannot lift its parent's ban.

| Flag | What it stops |
|---|---|
| `deny-place` | placing the block (it stays in the player's inventory; storing it in a chest inside is fine), and emptying a banned bucket |
| `deny-use` | right-clicking the block, or using the item in hand (flint and steel, end crystals) |
| `deny-break` | breaking the block |
| `deny-carry` | having it in the region at all, and counts as the other three. A player carrying one (inventory, armour, off-hand, Curios slots, or packed inside a carried shulker box, bundle or backpack) is put back at the edge, with a window showing what they carry; `teleport-in` refuses them; inside, it cannot be picked up, and no container hands one out, **the ender chest included** (mixin group `containers`). A player found inside holding one (logged in there, given one, or a ban added later) is moved just outside within a second. |

Set them in the admin screen's **BANNED** tab (drag blocks in from the creative tabs; `@mod` in its search shows one mod's blocks) or with `/rg flag spawn deny-carry minecraft:tnt,@create`. Known gaps: hoppers and pipes moving banned items into containers inside; items already in chests inside stay there (they just can't be taken out).

**What players see (D133 to D139).** While a player stands where anything is banned, a BANNED button shows in the top left of their inventory, beside BSP Core's TEAM and KITS. It opens BANNED HERE, a read-only grid of every banned item grouped by mod, with search and the rules for each. The bought-plot pop-up shows the count and a BANNED HERE button, and `/claim banned [plot]` opens the list. Tags and whole mods are shown as their items. The lists are not secret: any player may ask for any region's.

