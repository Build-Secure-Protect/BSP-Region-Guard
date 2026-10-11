# Flags

Every flag BSP Region Guard knows, what it does and how it is enforced. Set them with `/rg flag <region> <flag> <value>`; `clear` removes a value. State flags take `allow` or `deny`. An unset flag inherits from the region's parent, then from the world's `__global__` region, then falls back to the default given here.

How a flag is resolved at a position: take the regions containing it, highest priority first (a child stands in for its parent); at the first priority level where any region sets the flag, that level decides, and `deny` beats `allow` at the same level. **Build-family** flags (marked *build*) have no fixed default: when none sets them, owners and members of the top protecting region may act and nobody else may; they also fall back to the `build` flag's value. BSP Core admins bypass every player-action check unless they turn it off with `/rg bypass`.

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
| `entry-deny-message`, `exit-deny-message` | text | messages for refused entry or exit | tick |
| `notify-enter`, `notify-leave` | false | tell admins when someone enters or leaves | tick |
| `greeting`, `farewell` | none | chat message on entering or leaving | tick |
| `greeting-title`, `farewell-title` | none | title on entering or leaving; a newline separates the subtitle | tick |
| `enderpearl`, `chorus-fruit-teleport` | allow | those teleports, from or into the region | event |
| `teleport` | none | the point `/rg teleport <region>` goes to; `here` uses your position | event |
| `spawn` | none | where players respawn after dying inside | tick |
| `teleport-message` | none | shown after `/rg teleport` | event |
| `teleport-in`, `teleport-out`, `set-home` | allow | BSP Teleport asks these before a teleport or setting a home | API (later) |

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
| `allowed-ranks` | none | LuckPerms groups that may enter, on top of `entry` | tick |
| `totem-place`, `totem-drop` | allow | BSP Core asks these for Shatter Totems (replaces Anti Totem zones) | API (later) |
| `mine-only-break` | deny | inside a mine's shell only the mine's own blocks may be broken | later (mines) |

## Mixin groups

Each group is one config switch under `mixins` in the server config and patches these vanilla methods: **fire** (`FireBlock#tick` spread, `FireBlock#tryCatchFire` burning, `LavaFluid#randomTick`), **fluids** (`FlowingFluid#canSpreadTo`), **weather** (`ServerLevel#tickChunk`, `SnowLayerBlock#randomTick`, `IceBlock#randomTick`, `FrostedIceBlock#tick`, `FrostWalkerEnchantment#onEntityMoved`), **growth** (the random ticks of grass and mycelium, vines, mushrooms, sculk, leaves, dripstone and amethyst, farmland drying, coral, the three copper block shapes), **dispensers** (`DispenserBlock#dispenseFrom`), **falling blocks** (`FallingBlockEntity#tick`), **eggs** (`TurtleEggBlock#destroyEgg`), **hunger** (`Player#causeFoodExhaustion`, `FoodData#tick` healing). 28 patches in all, all server side.

## The Shatter Totem rule

Build Secure Protect's standing rule is that a totem must never be made impossible to steal. So in a PvP world (where totems can be placed), within BSP Core's steal radius of another player's placed totem, a region does not stop a raider: `entry`, `exit`, `interact`, `use`, `chest-access`, `enderpearl`, `chorus-fruit-teleport`, `teleport-in`, `teleport-out` always pass, `pvp` cannot be denied and `invincible` does not apply, and unless `totem.exemptBuild` is off in the config, `build`, `block-break`, `block-place`, `lighter`, `tnt`, the explosion flags and the vehicle flags pass too. The totem's owner gets none of this. World flags (fire, fluids, growth) are unaffected. Claims cannot be made in PvP worlds.

## Known gaps

- Mods that change blocks without a fake player (most quarries and contraptions do use one) are not stopped.
- Hoppers and pipes moving items across a border are not stopped; protect containers with `chest-access`.
- A tree outside a region may grow leaves into it.
