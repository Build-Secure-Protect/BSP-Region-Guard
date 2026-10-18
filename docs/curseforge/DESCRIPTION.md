![BSP Region Guard](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/banner.png)

# BSP Region Guard

**World protection, land claims, automated mines and linked servers for Minecraft 1.20.1, all run from in-game screens.**

BSP Region Guard looks after the world on a Build Secure Protect server:
- Admins draw regions and decide what may happen inside them.
- Players buy land and run their own plots.
- Mines refill themselves on a timer.
- Several servers share regions, and players can walk from one server's world into the next.

It takes WorldGuard's ideas to Forge and drives them from screens rather than long commands.

It is made for the [Build Secure Protect](https://www.curseforge.com/minecraft/modpacks/build-secure-protect) modpack and works closely with its sister mods: **BSP-Core** (required), and **BSP-Teleport** and **BSP-RankUp** (optional).

> The screens in this description are drawn from the mod's own layout and colours, as illustrations.

---

## At a glance

| | |
|---|---|
| **Minecraft** | 1.20.1, Forge 47.4.10 or newer; runs on Mohist |
| **Install on** | the server **and** every player (the screens and in-world drawing are client side) |
| **Requires** | [BSP-Core](https://github.com/Build-Secure-Protect/BSP-Core) 1.1.0 or newer |
| **Works with** | [BSP-Teleport](https://github.com/Build-Secure-Protect/BSP-Teleport), BSP-RankUp, [LuckPerms](https://luckperms.net/) (Forge mod or the Bukkit plugin on Mohist) |
| **Optional** | MySQL and Redis, only for several servers |

---

## 1. Draw your first region

Everything starts with the **Region Wand** (`/rg wand`):
1. Left-click one corner of the area you want and right-click the opposite corner.
2. The box appears in the world as a glowing wire cube.
3. Open the admin screen and press **+ NEW**, or type `/rg define <name>`, and the box becomes a region.

![Regions drawn in the world](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/wand_world.png)

Regions are drawn as see-through boxes in their type colour:
- **teal:** protected areas
- **violet:** players' claims
- **gold:** plots for sale and mines

Admins always see them. Players press **B** to see their own claims.

## 2. The admin screen

Open it with `/rg gui`, or sneak and right-click with the wand. Every region in the world is listed on the left, with a search box and filters (protected, claims, mines, for sale). The **MAP** tab shows them from above: click a region on the map to pick it, and use the wheel to zoom.

![The admin screen, MAP tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/admin_map.png)

The buttons under the pane:
- **TELEPORT:** go to the picked region.
- **REDEFINE:** move it to the wand's new outline.
- **SELECT:** put its corners back into the wand.

Every change saves as soon as you make it.

The screens are drawn **twice as big** as a normal Minecraft screen and shrink to fit smaller windows. Each player can change that with `ui.scale` in `config/bsp_region_guard-client.toml`.

## 3. Set the rules: flags

Each region carries **flags**, rules such as `pvp deny`, `block-break deny` or `greeting-title "Spawn"`. The **FLAGS** tab lists every flag with a one-line explanation:
- Click **allow** or **deny**, or type a value.
- Flags you leave unset show what they inherit, in violet.

![The admin screen, FLAGS tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/admin_flags.png)

When regions overlap, the one with the **higher priority** decides. A region left silent on a flag passes the question on to its parent and then to the world default. Building needs no flag at all: a region's owners and members may build, and everyone else may not.

![How a rule is decided](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/flags_diagram.png)

### The 110 flags

| Group | Flags |
|---|---|
| **Building** | `build`, `block-break`, `block-place`, `interact`, `use`, `chest-access`, `ride`, `sleep`, `respawn-anchors`, `vehicle-place`, `vehicle-destroy`, `lighter`, `block-trampling`, `item-frame-rotation`, `use-anvil`, `use-dripleaf`, `damage-animals`, `pistons`, `passthrough` |
| **Fighting** | `pvp`, `mob-damage`, `firework-damage`, `invincible`, `fall-damage`, `potion-splash` |
| **Explosions and mobs** | `tnt`, `creeper-explosion`, `ghast-fireball`, `wither-damage`, `enderdragon-block-damage`, `other-explosion`, `enderman-grief`, `ravager-grief`, `snowman-trails`, `mob-spawning`, `deny-spawn`, `entity-painting-destroy`, `entity-item-frame-destroy` |
| **Fire and nature** | `fire-spread`, `fire-burn`, `lava-fire`, `lightning`, `water-flow`, `lava-flow`, `snow-fall`, `snow-melt`, `ice-form`, `ice-melt`, `frosted-ice-form`, `frosted-ice-melt`, `crop-growth`, `grass-growth`, `mycelium-spread`, `vine-growth`, `mushroom-growth`, `leaf-decay`, `rock-growth`, `sculk-growth`, `soil-dry`, `coral-fade`, `copper-fade`, `dispenser-use`, `falling-blocks` |
| **Coming and going** | `entry`, `exit`, `exit-via-teleport`, `exit-override`, `entry-deny-message`, `exit-deny-message`, `greeting`, `farewell`, `greeting-title`, `farewell-title`, `notify-enter`, `notify-leave`, `enderpearl`, `chorus-fruit-teleport`, `teleport`, `spawn`, `teleport-message`, `allowed-ranks` |
| **Map making** | `item-pickup`, `item-drop`, `exp-drops`, `deny-message`, `game-mode`, `time-lock`, `weather-lock`, `natural-health-regen`, `natural-hunger-drain`, `heal-delay`, `heal-amount`, `heal-min-health`, `heal-max-health`, `feed-delay`, `feed-amount`, `feed-min-hunger`, `feed-max-hunger`, `blocked-cmds`, `allowed-cmds`, `send-chat`, `receive-chat` |
| **BSP** | `border-warning`, `border-message`, `teleport-in`, `teleport-out`, `set-home`, `totem-place`, `totem-drop`, `mine-only-break` |

Where Forge has no event to cancel (fire spreading, water flowing in, snow forming, grass spreading, dispensers, falling sand), Region Guard uses 28 small server-side patches. Each group can be switched off in the server config.

**BSP-Core admins** pass every rule so they can build anywhere. `/rg bypass` switches that off for testing.

## 4. The world itself: the world default and the border

Every world has a **world default** (`__global__`): flags set on it apply everywhere that no region decides, so it's where whole-world rules go.

It also holds the world's **border**, a square set with `/rg border set <width>` or from its **BORDER** tab:
- Players are warned 10 blocks before the edge and pushed back at it.
- Anyone found outside is put back on solid ground.
- In the Overworld, Minecraft's own wall is moved to match so everyone can see it.
- Teleports and homes outside the border are refused.

## 5. Who may come in: members and ranks

- **ACCESS tab:** a region's **owners** and **members**. They pass entry rules and may build.
- **RANKS tab:** with **LuckPerms**, pick the groups that may enter, for a VIP lounge, a staff room or a members-only market. Members always pass.

![Ranks and Region Guard](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/ranks.png)

**With BSP-RankUp**, players climb a LuckPerms rank ladder by playing: time online, fights, Shatter Totems stolen and defended, and seasons survived. Region Guard reads those same groups, so ranking up actually opens doors:
- **Rank-gated regions** let a player in once they reach the rank.
- **Rank-gated mines** unlock better ores at higher ranks.
- **Claim limits** rise with rank: 3 claims by default, more for higher groups (`claims.limitsByRank`).

Without RankUp, give groups by hand in LuckPerms and everything works the same.

## 6. Land for players: claims

Admins sell land in two ways:
- **Plots for sale:** an admin outlines a plot and sets its price. The admin also picks which rules the buyer may change.
- **Open areas:** an admin marks an area as open and sets a **price per block**. Players outline their own plot inside it (5 x 5 up to 64 x 64 blocks by default, full height). Plots may touch but never overlap.

![Buying land with BSP-Core](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/claim_flow.png)

**Buying, with BSP-Core:**
1. A player takes the plot tool (`/claim tool`) and clicks two corners.
2. BSP-Core's **Land Office** opens with that ground drawn on a map, priced, and with nearby claims shown.
3. The player buys it there, paid from their **BSP-Core bank** in Shatter Pieces.

Plots for sale are bought at the Land Office block too.

**Running your claim:** `/claim gui` (or sneak and right-click the plot tool) opens your claim card:
- **Who may build:** add members.
- **Rules:** written as plain sentences ("strangers may open chests"). Each switch is on, off, or grey (follow the area's rule).
- **Messages:** a greeting and a farewell for visitors.
- **Buttons:** **TELEPORT** to the claim, **ENLARGE** it later by paying for the extra ground, or **GIVE UP** for half the price back.

![The claim screen](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/claim_screen.png)

**Fair play for raiders:**
- Claims are not allowed in PvP worlds.
- A claim may not hold a Shatter Totem while any of its rules would stop a steal.
- Near another player's placed totem, regions never stop a raider.

**Starting a new season:** admins can wipe one world's claims from the admin screen or `/rg claims wipe`. You type back a one-time code, a backup is written first (`/rg claims restore` brings it back), and every admin online is told. Other worlds and servers are never touched.

## 7. Mines that refill themselves

Any region can become a **mine**:
- **Fill table:** list blocks with weights (stone 90, iron ore 8, diamond ore 1...). The heaviest block fills the mine and the others grow as fresh veins at every reset, so no two resets look alike.
- **Resets:** on a timer, with warnings at 60 and 10 seconds, or straight away with **RESET NOW**. Players inside are moved to the mine's spawn point.
- **Shell:** an optional ring around the mine where only the mine's blocks may be dug.
- **Ranks:** with LuckPerms or BSP-RankUp, keep the best mines for the higher ranks.

![The admin screen, MINE tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/admin_mine.png)

## 8. Region triggers: move players automatically

A region can move players when they **enter**, **leave**, **stay a while** (every so many seconds) or when the **server shuts down**. The destination can be a spot on the same server, or a world on another server; use the latter to send everyone to a lobby before a restart. Set triggers from the **TELEPORT** tab or `/rg trigger add`, with tab completion.

Same-server moves work on their own. **BSP-Teleport** carries out cross-server moves through the proxy.

## 9. Many servers, one network

On a network of servers, turn on the optional **shared storage**:
- **MySQL:** every server keeps its own regions and can see everyone else's.
- **Redis:** carries messages (mine resets, triggers, travellers) between servers in milliseconds, with MySQL as a safety net.

A single server needs neither. Region Guard bundles no database or Redis library, so nothing clashes with Mohist or other mods.

![One network, many servers](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/network.png)

**Server switcher.** The admin screen's **SERVER < name >** picker shows any other server's regions on the map, read-only, with this server laid underneath in grey.

![Looking at another server's regions](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/admin_servers.png)

**Edge links: walk from one server into the next.** On the world default's **BORDER** tab, link an edge of this world's border to an edge of another server's world. Links work both ways.

![Linking world edges between servers](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/admin_border.png)

When a player reaches a linked edge, a window asks whether to travel:
- **TRAVEL:** BSP-Teleport carries them to the other server. They arrive at the matching point along its edge, 16 blocks inside, always on the ground under open sky.
- **STAY:** they stay put and aren't asked again until they walk away and come back.

![The travel window](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Region-Guard/main/docs/curseforge/images/travel_popup.png)

---

## Working with the other BSP mods

### BSP-Core (required)
- **Money:** claims are paid from the **BSP-Core bank** in Shatter Pieces, and refunds go back to it.
- **The Land Office:** BSP-Core's land shop. Outline ground with the plot tool and it opens with the price; buy, enlarge or give up there. It also shows plots for sale and your neighbours' claims.
- **Admins:** BSP-Core's admin list decides who runs Region Guard.
- **Shatter Totems:** regions never stop a steal near another player's placed totem. BSP-Core's Anti Totem zones and Region Guard regions work together for totem placing, teleports and homes.

### BSP-Teleport (optional)
- **Teleport rules:** BSP-Teleport asks Region Guard's `teleport-in`, `teleport-out` and `set-home` flags, so admins can keep teleports out of an arena or homes out of spawn.
- **`/rtp`:** stays inside Region Guard's world border.
- **Cross-server moves:** BSP-Teleport carries out cross-server region triggers and **edge-link travel**, and Region Guard lands the player on solid ground.

### BSP-RankUp (optional)
- **Ranks open land:** RankUp's ranks are LuckPerms groups, the same ones Region Guard's rank gates read. Ranking up lets players into new regions and mines and raises how many claims they may own.
- **Server name:** on a network, RankUp picks up Region Guard's server name.

### LuckPerms (optional)
- **Groups:** decide rank-gated regions, mines and claim limits.
- **Editions:** both the Forge mod and the Bukkit plugin on Mohist are supported. Without LuckPerms, everything else still works.

---

## Commands

| Command | What it does |
|---|---|
| `/rg gui` | the admin screen |
| `/rg wand` | the Region Wand |
| `/rg define <id>`, `/rg redefine <id>`, `/rg remove <id>` | make, move or delete a region from the wand outline |
| `/rg flag <id> <flag> <value>` | set a flag (`clear` removes it) |
| `/rg addowner`, `/rg removeowner`, `/rg addmember`, `/rg removemember` | owners and members |
| `/rg addrank`, `/rg removerank` | the LuckPerms groups that may enter |
| `/rg priority`, `/rg parent`, `/rg type`, `/rg rename`, `/rg info`, `/rg list` | region details |
| `/rg teleport <id>` | go to a region |
| `/rg border set <width> [x z]`, `/rg border off`, `/rg border link ...` | the world border and edge links |
| `/rg mine ...` | fill table, interval, spawn, shell, reset |
| `/rg sale ...`, `/rg plotflags ...` | plots for sale and open areas |
| `/rg trigger add <id> <cause> <destination>` | region triggers |
| `/rg claims wipe`, `/rg claims restore` | the per-world claim wipe |
| `/rg bypass` | switch your admin bypass off or on |
| `/rg storage`, `/rg servers` | shared storage status |
| `/claim gui`, `/claim tool`, `/claim list`, `/claim quote` | your claims, the plot tool, prices |
| `/claim add`, `/claim remove`, `/claim flag`, `/claim abandon` | members, rules, giving a claim up |

## For developers

Other mods can use Region Guard through its public API (`com.mrgregles.bsp_region_guard.api`, version 5):
- **Questions:** ask whether a flag allows something at a spot; read flag values, region bounds and the world border.
- **Extending it:** register your own flags and exemptions.
- **Claims and mines:** work with claims (listings, quotes, purchases) and mines.
- **Messages:** send messages to the other servers.
- **Events:** players entering and leaving regions, triggers, claims bought and abandoned, and mine resets.

Full reference in [`docs/API.md`](https://github.com/Build-Secure-Protect/BSP-Region-Guard/blob/main/docs/API.md).

---

**Early release:** 0.1.0 is the first public version and has not yet run on a large live network. Please report problems on the [issue tracker](https://github.com/Build-Secure-Protect/BSP-Region-Guard/issues).

Source: [github.com/Build-Secure-Protect/BSP-Region-Guard](https://github.com/Build-Secure-Protect/BSP-Region-Guard) · MIT licence · Made for [Build Secure Protect](https://www.curseforge.com/minecraft/modpacks/build-secure-protect)
