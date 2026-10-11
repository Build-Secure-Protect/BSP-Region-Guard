# Testing BSP Region Guard in game

What to try after each build, in the order the features were added. Tick what works, note what does not. Claude cannot run the client, so this list is the hand-off between a build and the owner.

Headless checks already run by Claude: `./gradlew runGameTestServer` (43 tests: regions, every enforcement rule a server can exercise, movement, mixins, the totem rule, the screens' server side, claims, mines, and LuckPerms with the real LuckPerms Forge mod loaded from `libs/`).

**LuckPerms on the live network.** Verified on a local Mohist 1.20.1 server with the Bukkit edition of LuckPerms (5.5.87): the startup log says `LuckPerms reached through the Bukkit edition` and `rg ranks` lists the groups. On the live backends, check for the same two lines. The local test server lives in `server/` (not committed): `tools/setup_test_server.sh` copies the current jars, `server/start.sh` starts it, `tools/rcon.py "<command>"` runs console commands. **BSP Core must be the `-slim` jar on Mohist** (request RG-C4 to BSP Core): the full jar's bundled MySQL driver clashes with Mohist's own.

## Phase 1: regions, flags and commands (build 0.1.0, 2026-10-10)

Setup: a world with BSP Core installed; you are an operator or a BSP Core admin.

- [ ] Server log on start shows `BSP Region Guard ready. LuckPerms: ... BSP-Teleport: ...`.
- [ ] `/rg wand` gives a Region Wand (handheld stick with a turquoise crystal). It also appears in the Tools creative tab.
- [ ] Left-click a block with the wand: action bar says "First corner set to x, y, z" and the block is **not** broken. A gold particle marks the corner while the wand is held.
- [ ] Right-click another block: "Second corner set to ... (w x h x d, n blocks)". The box is outlined with turquoise particles while the wand is in either hand; put the wand away and the outline stops.
- [ ] `/rg pos1` and `/rg pos2` set the corners at your feet.
- [ ] `/rg define test` creates the region; `/rg list` shows it with its bounds and priority 0; `/rg info test` shows type, bounds, no owners, no flags.
- [ ] Stand inside it: `/rg here` lists `test`. Step outside: "No region here."
- [ ] `/rg flag test pvp deny`, then `/rg flag test pvp` shows `deny`, `/rg info test` lists `pvp=deny`. `/rg flag test pvp clear` removes it.
- [ ] `/rg flag test greeting Welcome to the test area` stores the whole sentence.
- [ ] `/rg flag test teleport here` then move away and `/rg teleport test` brings you back to that spot and facing. Without a teleport flag, `/rg teleport test` lands you on top of the region's centre column.
- [ ] `/rg addmember test <your name>` then `/rg info test` lists you under Members; `/rg addowner test <name>` moves you to Owners.
- [ ] `/rg flag test deny-spawn minecraft:creeper,minecraft:zombie` is accepted; `/rg flag test heal-delay abc` is refused with a plain message.
- [ ] `/rg define other 5` with a second selection overlapping the first; `/rg here` lists `other` before `test`.
- [ ] `/rg parent other test` then `/rg info other` shows "Inherits: n flags from test". `/rg parent test other` is refused ("loop").
- [ ] `/rg flag __global__ pvp deny` creates the world default region; `/rg list` shows it as "world default"; `/rg here` anywhere lists `__global__` last.
- [ ] Leave the world and come back (or restart the server): `/rg list` still shows every region with its flags.
- [ ] `/rg remove test`: gone from `/rg list`; `other` now has no parent.
- [ ] Tab completion offers region ids, flag names and, for state flags, `allow`, `deny`, `clear`.

## Phase 2a: event-driven protections (build 0.1.0, 2026-10-10)

Setup: `/rg define test` around a small area with a chest, a door, a bed, a lever, a crop and some grass in it. Test as a **non-admin** second account where it says "stranger" (admins bypass; `/rg bypass` turns your bypass off so you can test alone).

- [ ] Stranger: breaking a block in `test` is refused and the action bar says "You can't do that here." once, not every swing. Outside the region breaking works.
- [ ] `/rg flag test deny-message Keep out` changes the message.
- [ ] Stranger: placing a block, opening the chest, opening the door, flipping the lever, sleeping in the bed, using flint and steel, placing a boat on water, filling or emptying a bucket, hoeing dirt, bone-mealing a crop: all refused. `/rg addmember test <stranger>` and all of them work.
- [ ] `/rg flag test build allow`: strangers may do everything; `/rg flag test build deny`: even members are stopped; `/rg flag test block-break allow` then members and strangers may break but still not place.
- [ ] `/rg flag test pvp deny`: two players inside cannot hit each other; one inside and one outside cannot either; both outside can. Arrows count.
- [ ] `/rg flag test mob-damage deny`: a zombie inside cannot hurt you. `/rg flag test invincible allow`: nothing hurts you inside, including lava and falling.
- [ ] `/rg flag test fall-damage deny`: jump from the region's top edge inside it without damage.
- [ ] `/rg flag test item-drop deny`: pressing Q keeps the item in your inventory. `item-pickup deny`: items on the floor inside cannot be picked up.
- [ ] `/rg flag test tnt deny`: TNT lit inside does nothing; TNT lit just outside breaks blocks outside but none inside the region. Same with `creeper-explosion` and a creeper.
- [ ] `/rg flag test mob-spawning deny` at night: no mobs spawn inside while they spawn outside. `/rg flag test deny-spawn minecraft:creeper`: zombies spawn inside, creepers do not. Spawn eggs follow the same rules.
- [ ] `/rg flag test lightning deny`: a trident with Channeling in a thunderstorm does not strike inside; `/summon lightning_bolt` inside does nothing.
- [ ] `/rg flag test crop-growth deny`: crops and saplings inside do not grow (speed up with `/gamerule randomTickSpeed 200`).
- [ ] `/rg flag test pistons deny`: a piston inside does not move; a piston outside pointing in does not push a block into the region.
- [ ] `/rg flag test enderpearl deny`: throwing a pearl from inside or into the region is refused with a message; `chorus-fruit-teleport deny` likewise.
- [ ] `/rg flag test enderman-grief deny`: endermen do not pick up blocks inside. `/rg flag test ravager-grief deny` likewise if you can find a ravager.
- [ ] `/rg flag test blocked-cmds home,spawn`: `/home` inside says "You can't use /home here." but works outside. `/rg flag test allowed-cmds rg,msg`: only those commands work inside.
- [ ] `/rg flag test send-chat deny`: chatting inside is refused. `/rg flag test receive-chat deny`: a player inside sees no chat from others; players outside still see each other.
- [ ] `/rg flag test exp-drops deny`: killing a mob inside drops no XP orbs.
- [ ] Item frames inside: a stranger cannot rotate the item or break the frame; paintings cannot be hit down; arrows from outside do not break them.
- [ ] `/rg bypass` as an admin: off means regions stop you like anyone else; on means they do not.

## Phase 2b: movement rules (build 0.1.0, 2026-10-10)

Same `test` region. "Stranger" means a non-member who is not an admin (or an admin after `/rg bypass`).

- [ ] `/rg flag test entry deny`: a stranger walking in is pushed back a step with "You are not allowed in there." (once, not every tick). A member walks in. `/rg flag test entry-deny-message Members only` changes the text. Sprinting, jumping and riding a horse into the region are all pushed back.
- [ ] Ender pearl or `/tp` into an `entry deny` region as a stranger: you land, then are moved just outside the nearest face.
- [ ] `/rg flag test exit deny`: a stranger inside cannot walk out; a member can. `/tp` out works (exit-via-teleport default allow); `/rg flag test exit-via-teleport deny` and `/tp` out puts you back. `/rg flag test exit-override true` lets everyone out.
- [ ] `/rg flag test greeting Welcome!` and `farewell Bye!`: chat lines on entering and leaving. `/rg flag test greeting-title Test Area\nHave fun` shows a title with a subtitle.
- [ ] `/rg flag test notify-enter true`: another admin online sees "[Region Guard] <name> entered test in minecraft:overworld".
- [ ] `/rg flag test game-mode adventure`: a survival player becomes adventure inside and survival again outside. Log out inside, log in and walk out: still restored to survival.
- [ ] `/rg flag test time-lock midnight`: inside it looks like night and the sun does not move; outside the real time returns. `weather-lock downfall`: rain inside only; `weather-lock clear` during a real storm: dry inside, storm returns outside.
- [ ] `/rg flag test heal-delay 1` and `heal-amount 2`: lose health, stand inside, gain a heart every second up to full. `heal-max-health 10`: healing stops at five hearts. `heal-amount -1`: lose half a heart a second; `heal-min-health 2` stops it at one heart.
- [ ] `/rg flag test feed-delay 1` and `feed-amount 1`: hunger refills inside; `feed-amount -1` with `feed-min-hunger 6` drains to three shanks and stops.
- [ ] `/rg flag test spawn here` at a chosen spot, then `/kill` while standing in the region: you respawn at that spot. Die outside the region: normal respawn.
- [ ] `/rg flag test allowed-ranks vip`: on a server **with** LuckPerms, a player without the `vip` group is refused, a member or a `vip` player gets in. Without LuckPerms everyone gets in and the log warns once.

## Phase 2c: world rules (mixins) (build 0.1.0, 2026-10-11)

Same `test` region. Speed things up with `/gamerule randomTickSpeed 100` where a rule depends on random ticks, and set it back to 3 afterwards.

- [ ] `/rg flag test fire-spread deny`: light a wooden floor with a border inside the region: fire spreads across the outside part and stops at the border; nothing inside catches.
- [ ] `/rg flag test fire-burn deny`: fire placed on planks inside burns out without destroying them. `/rg flag test lava-fire deny`: a lava pool next to planks inside never sets them alight.
- [ ] `/rg flag test water-flow deny`: water poured just outside flows everywhere except into the region; water poured inside does not spread at all. `lava-flow deny` likewise with lava.
- [ ] `/rg flag test snow-fall deny` and `ice-form deny` in a snowy biome during snowfall: snow settles and water freezes outside, not inside. `snow-melt deny` and `ice-melt deny` with torches: snow and ice inside stay.
- [ ] `/rg flag test frosted-ice-form deny`: Frost Walker boots make no ice inside. `frosted-ice-melt deny`: frosted ice inside never melts.
- [ ] `/rg flag test grass-growth deny`: dirt next to grass inside stays dirt while the same setup outside turns green. `mycelium-spread deny` likewise with mycelium. `vine-growth deny`: vines inside do not creep. `mushroom-growth deny`: mushrooms inside do not spread.
- [ ] `/rg flag test leaf-decay deny`: cut a tree's trunk inside: leaves stay. `sculk-growth deny`: a sculk catalyst inside does not spread when a mob dies next to it.
- [ ] `/rg flag test rock-growth deny`: dripstone and budding amethyst inside do not grow. `soil-dry deny`: dry farmland inside does not revert to dirt. `coral-fade deny`: coral inside survives out of water. `copper-fade deny`: copper inside does not oxidise.
- [ ] `/rg flag test dispenser-use deny`: a dispenser outside pointing into the region fires nothing; one pointing away works. A dispenser inside pointing inside also does nothing.
- [ ] `/rg flag test falling-blocks deny`: sand dropped from above into the region lands as an item, not a block; sand outside lands normally.
- [ ] `/rg flag test block-trampling deny` with turtle eggs inside: a stranger jumping on them does not break them; a member does.
- [ ] `/rg flag test natural-hunger-drain deny`: sprint and jump inside: the hunger bar does not drop. `natural-health-regen deny`: with a full hunger bar, lost health does not come back inside; a potion of healing still works.
- [ ] Set `mixins.fire = false` in `world/serverconfig/bsp_region_guard-server.toml` and restart: the fire flags do nothing; the others still work.

## Phase 2d: the Shatter Totem rule (build 0.1.0, 2026-10-11)

Needs two accounts in a world where totems can be placed (the overworld by default) and a placed Shatter Totem belonging to player A.

- [ ] Admin: `/rg define base` around A's totem with `/rg flag base build deny`, `entry deny`, `pvp deny`. Player B (not a member, not an admin) can still walk up to the totem, break and place blocks within 5 blocks of it, open the totem's panel and start a steal, and hit A there. Further than 5 blocks from the totem, inside the same region, B is stopped as usual.
- [ ] Player A (the owner) is stopped by the same region everywhere, including next to their own totem.
- [ ] Set `totem.exemptBuild = false` in `world/serverconfig/bsp_region_guard-server.toml` and restart: B can still enter, interact and fight near the totem but no longer break or place blocks.
- [ ] `/rg type base claim` in the overworld is refused with "Claims are not allowed in a PvP world". In a world not in BSP Core's `restrictions.allowedDimensions` it is accepted.

## Phase 3a: the admin screen (build 0.1.0, 2026-10-11)

As an operator or BSP Core admin, with a few regions defined (use the Phase 1 commands or the screen itself). GUI scale 2 or 3 fits the screen; at "auto" on a small window it may be clipped, say so if it is.

- [ ] `/rg gui` opens the screen on the region you stand in (or the first one). Sneak + right-click with the wand (air or block) opens it too. A non-admin gets a refusal message.
- [ ] Left list: every region with a type letter (P, C, M, S, W) and priority; the search box narrows by id or name; the filter chips narrow by type; the wheel scrolls; a scrollbar appears past 12 rows.
- [ ] MAP tab: loaded terrain shows with regions as tinted boxes in turquoise (protected), violet (claims), gold (mines, sale); the selected region has a thicker outline; the gold dot is you; unloaded chunks are dark. Clicking a box selects it (the list follows); double-click jumps to FLAGS; the wheel zooms (the scale line updates); hovering shows id, type and priority.
- [ ] FLAGS tab: categories in turquoise; state flags switch between inh / allow / deny with one click and `/rg flag <id> <flag>` agrees; text and number flags open an edit line at the bottom (Enter saves, CLEAR clears, Esc cancels); a bad value (letters in `heal-delay`) shows a message on the action bar and changes nothing; SET ONLY shows the set flags; hints appear under names on a wide window and disappear on a narrow one; inherited values show in violet.
- [ ] ACCESS tab: + ADD A PLAYER then a name and +OWN or +MEM; the x removes; an unknown name is reported.
- [ ] RANKS tab: on a server with LuckPerms the groups appear as tick boxes; without, a typed rank is added; `/rg info` lists the ranks.
- [ ] TELEPORT tab: SET HERE stores your position; GO teleports there; CLEAR removes it; the respawn point likewise (die inside to check); the message edit saves.
- [ ] INFO tab: rename, type chips (claim types refused in a PvP world), priority -10/-1/+1/+10, parent set and cleared (a loop is refused), bounds and size.
- [ ] + NEW: with a wand selection, type an id and Enter creates the region and selects it; without a selection the action bar says so. WAND gives a wand. REDEFINE moves the region to the current selection; SELECT copies the region's corners to the wand (its particles move). DELETE asks SURE? then removes.
- [ ] Two admins with the screen open: a change by one shows for the other after they click anything (there is no push yet; note it if it bothers you).
- [ ] Open the screen in the nether: the title says NETHER and lists that world's regions only.

## Phase 3b: regions drawn in the world (build 0.1.0, 2026-10-11)

- [ ] As an admin, every region within 256 blocks shows as a box with a thin outline, faint faces and its id floating above the centre; colours by type (turquoise protected, violet claim, gold mine or sale). Walk inside: the faces fade a little and the box stays readable.
- [ ] Press B: "Regions hidden." and the boxes vanish; B again brings them back. The key can be changed under Controls, category BSP Region Guard.
- [ ] Set wand corners: each corner gets a small gold frame; with both set, the selection box is gold. `/rg select <id>` and the screen's SELECT button move the gold box to that region.
- [ ] Define a region in the screen or with `/rg define`: the box appears at once without reopening anything. Delete it: it disappears. Redefine: it moves. A second admin online sees the same without doing anything.
- [ ] Change world (nether portal): only that world's regions show. Die and respawn: regions still show.
- [ ] As a non-admin player: no admin regions are drawn. Make the player an owner of a region of type claim (`/rg type <id> claim` in a non-PvP world, `/rg addowner <id> <name>`): that one box shows for them in violet, and B hides it. A `plot_for_sale` region shows for them in gold.
- [ ] Log out and in: boxes are there straight away.
- [ ] Look at a region from 100 blocks away: box visible, label gone (labels show within 96 blocks).

## Phase 4a: claims (build 0.1.0, 2026-10-11)

Claims need a world that is not a PvP world: either a second dimension not in BSP Core's `restrictions.allowedDimensions`, or `claims.allowInPvpWorlds = true` in `world/serverconfig/bsp_region_guard-server.toml` for testing. Give the test player coins with BSP Core (`/bsp` admin tools or the creative tab).

- [ ] Admin: define a region and `/rg sale shop1 100`: `/rg info shop1` says plot for sale; every player sees its gold box in the world and `/claim list` lists it with the price. The SALE tab shows the price; SET PRICE 0 takes it off sale.
- [ ] Player without coins: `/claim quote shop1` names the price and says you cannot afford it. With 150 coins: `/claim buy shop1` takes 100, the box turns violet (a claim), `/claim here` says it is yours, you can build inside while another player cannot, and the admin screen lists it as a claim with you as owner.
- [ ] `/claim add shop1 <friend>`: the friend can build; `/claim remove` stops that. `/claim flag shop1 pvp deny` works; `/claim flag shop1 tnt deny` is refused with the list of allowed flags.
- [ ] `/claim abandon shop1`: 50 coins back, the region is for sale again at 100, the box is gold again.
- [ ] Admin: a big region and `/rg open field 2` (2 coins per block). Player: `/claim tool`, outline 3 x 3: `/claim buy field` refuses (too small); outline partly outside: refused; outline 6 x 6: `/claim quote field` says 72 coins; `/claim buy field` creates `field-<name>-1` as a violet box from bedrock to sky, 72 coins taken. A second outline overlapping it: refused. With `claims.maxPerPlayer = 1`, a second plot elsewhere: refused with the limit.
- [ ] `/claim enlarge field-<name>-1` with a bigger outline containing the plot: pays for the added blocks only; a smaller or shifted outline is refused.
- [ ] `/claim abandon field-<name>-1`: refund, the box disappears.
- [ ] Wipe: as a BSP Core admin (in the config list, or op when the list is empty), `/rg claims wipe` prints the count, the world and a four-digit code; `/rg claims wipe 0000` (wrong) wipes nothing; the right code within a minute removes every claim of that world, keeps plots for sale and protected regions, names the backup file, and other admins online see a notice; `/rg claims backups` lists it; `/rg claims restore <file>` brings the claims back with their owners. A non-admin op is refused when the admin list is set.
- [ ] In a PvP world: `/rg sale` and `/claim tool` are refused with a message about PvP worlds.

## Phase 4b: the player claim screen (build 0.1.0, 2026-10-11)

As a non-admin player in a world where claims are allowed, owning at least one claim (buy one with `/claim buy` first).

- [ ] `/claim gui` opens on the claim you stand in; sneak + right-click with the plot tool does the same (an admin gets the admin screen instead). Without a claim the screen lists what is for sale and offers the PLOT TOOL.
- [ ] The map on the left shows your plot outlined in violet with the area around it; the wheel zooms. With two claims, the arrows in the header step between them.
- [ ] "+ add", a name, Enter: the member appears as a chip; its x removes them. Adding yourself says you are the owner already; an unknown name is reported.
- [ ] The RULES switches cycle grey (follow the area) to on to off with each click; `/rg flag <claim> <flag>` as an admin agrees (grey = not set, on = allow, off = deny). Only the flags from the config list (or the plot's own list) appear.
- [ ] MESSAGES: edit "on entering", type a greeting, Enter: walking into the claim shows it; empty text clears it.
- [ ] ENLARGE with no outline says to outline first; with a bigger outline containing the plot it pays and the box grows (plots outlined in an open area only).
- [ ] TELEPORT puts you on top of the claim's centre.
- [ ] GIVE UP asks "REALLY GIVE UP?" then refunds and closes the claim; the screen moves to your next claim or the empty state.
- [ ] Admin: `/rg plotflags shop4 greeting,chest-access` then a buyer of shop4 sees only those two rules on the screen and `/claim flag shop4 entry deny` is refused with the allowed list; `/rg plotflags shop4 default` restores the config list. The SALE tab shows the list and lets you edit it.
- [ ] Totem rule (needs `claims.allowInPvpWorlds = true` or totems allowed in the claim world): in a claim with "strangers may enter" switched off, placing a Shatter Totem is refused with a message; switch it back to grey and the totem can be placed; with the totem standing inside, switching PvP off is refused ("A Shatter Totem stands in this claim"), while a greeting still works.

## Phase 5: mines (build 0.1.0, 2026-10-11)

- [ ] Define a region (say 20 x 8 x 20) and `/rg mine create quarry`: `/rg info quarry` shows type mine with `block-break=allow`, `block-place=deny`. The box turns gold in the world.
- [ ] `/rg mine add quarry minecraft:stone 80`, `minecraft:iron_ore 15`, `minecraft:gold_ore 5`, `minecraft:diamond_ore 1`: `/rg mine info quarry` lists them with percentages. `/rg mine add quarry minecraft:nothing 5` is refused.
- [ ] `/rg mine reset quarry`: the box fills with stone and ore veins (clusters, not speckles); a second reset gives a different layout. A large mine (60 x 30 x 60) fills over a few ticks without freezing; lower `mines.blocksPerTick` in the server config to watch it fill in strips.
- [ ] Stand inside and reset: you are moved just above the mine. `/rg mine spawn quarry` elsewhere, then reset from inside: you land at that spot.
- [ ] `/rg mine interval quarry 90`: `/rg mine info` shows the countdown; players within 48 blocks get "resets in 60 seconds" and "in 10 seconds" in chat and on the action bar, then "has been reset" when it fires. `/rg mine interval quarry 0` stops it.
- [ ] Any player can dig in the mine but cannot place blocks there. `/rg mine shell quarry 5`: a region `quarry_shell` appears 5 blocks around; inside the shell but outside the mine, digging says "Only the mine can be dug here." and placing is refused; beyond the shell the world behaves as before. An admin with bypass can still dig the shell.
- [ ] `/rg flag quarry allowed-ranks vip` (with LuckPerms): a player without the group is pushed back at the mine's edge; a `vip` player digs. `/rg ranks <player>` shows an online player's groups.
- [ ] Admin screen, MINE tab: on a non-mine region, MAKE THIS A MINE; on a mine, the fill table with x to remove, + ADD BLOCK ("minecraft:coal_ore 10"), INTERVAL, RESET NOW, SET SPAWN / MOVE SPAWN, MAKE SHELL / RESIZE SHELL, and the live countdown.

## Phase 7: shared storage (build 0.1.0, 2026-10-12)

Needs a MySQL database reachable from two test servers (two copies of `server/` on different ports work), each with its own `storage.serverId` and the same `tablePrefix`. On Forge backends BSP Core's full jar must be present for the driver; on Mohist the slim jar.

- [ ] With `storage.mode = mysql` and the details filled in, the log says `Region storage connected: mysql ...` and `Region storage reconcile for minecraft:overworld: ...` for each world; `/rg storage status` shows the server id, the poll interval and zero errors. A wrong password shows the error in `/rg storage status` and the server keeps running.
- [ ] Define a region: the `bsp_rg_regions` table gains a row for this server id with the region's id and version; change a flag: the version rises; delete it: the row has `deleted = 1`.
- [ ] Stop the server, delete `world/data/bsp_region_guard_regions.dat`, start again: the regions come back from the database (log line counts "added").
- [ ] Second server with another `serverId`: `/rg storage servers` on either lists both servers with their region counts; neither sees the other's regions in `/rg list`.
- [ ] A mine with the same id and world on both servers: `/rg mine reset quarry` on one resets it on the other within `pollSeconds` (the other's log shows the reset, players there see the warning line). With `syncMineResets = false` it does not.
- [ ] `/rg storage sync` re-runs the reconcile for the current world.

## Phase 8a: region triggers (build 0.1.0, 2026-10-12)

- [ ] `/rg trigger add test enter here Welcome!` while standing at a spot outside the region: walking into `test` shows "Welcome!" and puts you at that spot. `/rg trigger list test` shows it; `/rg trigger remove test 1` removes it.
- [ ] `/rg trigger add test exit minecraft:the_nether 0 70 0`: leaving the region sends you to the nether at that position.
- [ ] `/rg trigger add test timer 10 here`: standing inside, every 10 seconds you are moved to the spot.
- [ ] `/rg trigger add test shutdown here`: stop the server while standing inside: the log shows the trigger firing (the move itself needs BSP Teleport for another server; locally you are already leaving).
- [ ] `/rg trigger add test enter lobby/minecraft:overworld 0 64 0 See you there`: with no BSP Teleport installed, nothing moves and the log warns that no teleport mod handled it (or, with storage on, a `trigger` message appears in the messages table).

- [ ] Admin screen, TELEPORT tab: the triggers list shows what `/rg trigger list` shows; x removes one; pick a cause chip, + ADD, type `here` (or `30 here` for a timer), Enter: the trigger appears and works.
- [ ] Admin screen, MAP tab with shared storage on and a second server's regions in the database: OTHER SERVERS draws them in grey with "server/id" labels; clicking one does nothing; HIDE OTHERS removes them.

Not yet in this build: BSP Core's shop screen (RG-C3), BSP Teleport's side of cross-server triggers (see the BSP-Teleport handoff in the vault), Redis.
