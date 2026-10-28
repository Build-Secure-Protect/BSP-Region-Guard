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

Claims need a world that is not a PvP world: either a second dimension not in BSP Core's `restrictions.allowedDimensions`, or `claims.allowInPvpWorlds = true` in `world/serverconfig/bsp_region_guard-server.toml` for testing (from 2026-10-23 also `/rg claims pvp allow` in that world, or `pvp` deny on its world default). Claims are paid from the BSP Core bank in Shatter Pieces (D31, from 2026-10-12). Set the test player's balance with `/bsp bank set <player> 0` and `/bsp bank give <player> <pieces>`; `/bank` shows it (new accounts start with BSP Core's starting pieces, 500 by default).

- [ ] Admin: define a region and `/rg sale shop1 100`: `/rg info shop1` says plot for sale; every player sees its gold box in the world and `/claim list` lists it with the price. The SALE tab shows the price; SET PRICE 0 takes it off sale.
- [ ] Player with 0 pieces: `/claim quote shop1` names the price and says what the bank holds. With 150 pieces: `/claim buy shop1` takes 100 (the bank log shows it, paid to Region Guard), the box turns violet (a claim), `/claim here` says it is yours, you can build inside while another player cannot, and the admin screen lists it as a claim with you as owner.
- [ ] `/claim add shop1 <friend>`: the friend can build; `/claim remove` stops that. `/claim flag shop1 pvp deny` works; `/claim flag shop1 tnt deny` is refused with the list of allowed flags.
- [ ] `/claim abandon shop1`: 50 pieces back into the bank, the region is for sale again at 100, the box is gold again.
- [ ] Admin: a big region and `/rg open field 2` (2 pieces per block). Player: `/claim tool`, outline 3 x 3: `/claim buy field` refuses (too small); outline partly outside: refused; outline 6 x 6: `/claim quote field` says 72 pieces; `/claim buy field` creates `field-<name>-1` as a violet box from bedrock to sky, 72 pieces taken from the bank. A second outline overlapping it: refused. With `claims.maxPerPlayer = 1`, a second plot elsewhere: refused with the limit.
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
- [ ] (Replaced on 2026-10-15 by the server switcher; see that section.)

Not yet in this build: BSP Core's shop screen (RG-C3). (BSP Teleport's side of cross-server triggers exists since 2026-10-12; Redis since 2026-10-14.)

## 2026-10-12: bank payments, BSP-Core 1.0.1, Anti Totem zones, shutdown flush (build 0.1.0)

Needs BSP-Core 1.0.1 (its `api` package and bank). On the Mohist test server, `tools/setup_test_server.sh` now copies the slim jar of the version in `gradle.properties`.

- [ ] Bank payments: repeat Phase 4a with the bank. Also: with 100 pieces, `/claim buy` a plot priced 150 is refused with what the bank holds, and nothing changes. The bank log (`/bank`) names Region Guard as the other side of each purchase and refund.
- [ ] An admin gives up someone else's claim while that player is offline: the owner's bank gets the refund (check `/bank` when they log in).
- [ ] On a network (BSP Core with shared storage): two players try to buy the same plot at the same moment; one gets it, the other is told someone else is buying it (or, if their charge went through first, gets the money back).
- [ ] Anti Totem zones (D32): put a BSP Core Anti Totem zone with "teleport in" blocked somewhere without any region; BSP Teleport refuses a teleport into it (Region Guard answers no for `teleport-in` there). Same for "totem place" if BSP Core asks Region Guard.
- [ ] Shutdown landings (TR2): with storage on and a shutdown trigger to another server, stop the server; the player lands at the trigger's position on the holding server, not its spawn. The log shows no "queued writes not sent" warning.

## 2026-10-14: Redis messaging (build 0.1.0)

Needs the network set up as in `docs/NETWORK.md`: MySQL and Redis on the main machine, at least two backends with `storage.mode = mysql`, `storage.redis.enabled = true`, the same `tablePrefix` and `channel`, different `serverId`s. Built and tested headless only (54 GameTests against a fake Redis); this is the first real run.

- [ ] Start each backend: the log says `Region storage connected: mysql ...` and `Redis connected (redis <host>:6379, channel bsp_rg:messages)`. `/rg storage status` says the table is read every 30 s and Redis is `connected` with no error.
- [ ] Wrong Redis password on one backend: its log warns "Redis refused the login", `/rg storage status` shows it, the server keeps running and still gets messages (within 5 s, from the table).
- [ ] A mine with the same id and world on two backends: `/rg mine reset quarry` on one resets it on the other **at once** (not after a few seconds). `/rg storage status` on the receiver counts one received message.
- [ ] Stop Redis on the main machine: within a few seconds each backend's log warns once and `/rg storage status` says `down` and reading every 5 s. A mine reset still reaches the other server, within about 5 s. Start Redis again: the logs say "Redis connected again" within about 30 s, status is `connected`, resets are instant again. Nothing is reset twice.
- [ ] With BSP Teleport: a cross-server shutdown trigger (`/rg trigger add <region> shutdown <holding server>/minecraft:overworld x y z`), stop the backend while standing inside: you land at that spot on the holding server (the landing message goes out through Redis before the server finishes stopping).
- [ ] Watch for a while with players moving between servers: no "queued writes not sent" warning on stop, and the "not sent" count in the status stays at 0 while Redis is up.

## 2026-10-14: commands and screen share their edit rules (build 0.1.0)

Every edit the admin screen makes now goes through the same code as the `/rg` command. Re-check a few edits from both sides:

- [ ] `/rg sale shop1 100`, then `/rg sale shop1 0`: the plot is no longer for sale (gold box gone, `/claim list` does not list it). The same from the SALE tab (SET PRICE 0).
- [ ] On a mine, SET PRICE 0 in the SALE tab: the region stays a mine.
- [ ] `/rg open <an owned claim> 5`: refused, the claim is untouched.
- [ ] Screen: world default selected, change its priority: refused with a message.
- [ ] Screen and commands: flags, owners and members, ranks, parent, type, rename, redefine, select, teleport, triggers and the MINE tab behave as before; a failed edit shows the same sentence the command gives.

## 2026-10-14: world border (build 0.1.0)

- [ ] `/rg border set 200` in the Overworld: the chat line shows the square (x -100 to 100, z -100 to 100) and Minecraft's wall appears at it. `/rg border` repeats it.
- [ ] Walk towards the edge: about 10 blocks before it the action bar says how far the edge is (once per approach). Walk into the wall: you cannot pass (Minecraft's wall). With F3 off and an elytra or a boat along the edge: same.
- [ ] Set `/rg border set 100` while standing 80 blocks out: within a tick you are moved just inside onto solid ground with "You have reached the edge of the world."
- [ ] In the Nether: `/rg border set 300`: there is no visible wall, but stepping over the edge puts you back with the message; on a horse or in a boat you move back with it. The Nether is not limited by the Overworld's 200 (the Overworld wall does not show up in the Nether).
- [ ] Log out, `/rg border set 50` from the console for that world, log back in outside: you are moved inside.
- [ ] With BSP Teleport: a home or a teleport to a spot outside the border is refused.
- [ ] `/rg flag __global__ border-message "Turn back, traveller"` and `/rg flag __global__ border-warning "Here be dragons"`: the new texts show.
- [ ] As a BSP Core admin with bypass on, walk out past the Nether border: nothing stops you; `/rg bypass` off: you are pushed back like anyone.
- [ ] `/rg border off` in the Overworld: the wall disappears.

## 2026-10-15: edge links between servers (build 0.1.0)

Needs two backends on the proxy with shared storage (and Redis if available), BSP Teleport on both, `storage.serverId` equal to each server's proxy name (the log warns if not), and a border on each (`/rg border set 400` on A, `/rg border set 600` on B, both in the Overworld). Built and tested headless only (62 GameTests); the screens have not been seen in game.

- [ ] On A: `/rg gui`, select the world default: the tabs are MAP, FLAGS, ACCESS, RANKS, TELEPORT, INFO, BORDER (no SALE or MINE). BORDER shows A's world in the middle ("BORDER 400 wide, around 0, 0") and four + LINK slots.
- [ ] Click + LINK on the east slot: a list shows B's Overworld with its west edge first. Pick it: the east slot names B, "its west edge - 600". `/rg border links` on A says the same; on B (after a moment, or reopen the BORDER tab) the west slot names A with "made on A".
- [ ] On A, walk east: about 10 blocks before the edge the warning shows; at the wall a window asks "Beyond this edge is B" with the arrival point. STAY: the window closes, you stand at the edge, nothing asks again while you stay near it. Walk 20 blocks back west and return: it asks again.
- [ ] TRAVEL: you leave A and arrive on B 16 blocks inside its west edge, at the matching place along it (a quarter of the way along A's edge arrives a quarter of the way along B's), standing on the ground (not inside a hill or in the air).
- [ ] On B, walk back west to the edge: the window offers A. TRAVEL brings you back near A's east edge.
- [ ] Unlink from B's side (x on the west slot): A's east slot becomes + LINK again within a moment; walking into A's east edge just pushes you back with the edge message.
- [ ] With BSP Teleport removed from A: TRAVEL says travel is not available, and you stay.
- [ ] Find out with the owner whether inventory and XP follow players between your servers (that depends on how player data is shared, not on Region Guard; BSP Core carries Shatter Totems).

## 2026-10-15: screen text from the language file (build 0.1.0)

Before testing: `python3 tools/gen_assets.py && python3 tools/check_lang.py` (0 missing).

- [ ] Open the admin screen (`/rg gui`), the claim screen (`/claim gui`) and walk into a linked edge: every label, hint, button and line reads as English text. A raw key such as `gui.bsp_region_guard.set_price` anywhere means a key is missing (tell Claude which screen and where).
- [ ] Counts read naturally: "1 region" / "3 regions" in the admin header, "1 owner" / "2 owners" under a region's name, "1 px = 1 block" / "1 px = 4 blocks" under the map.
- [ ] Tabs, filter chips (ALL, PROT, ...), the FLAGS tab's inh / allow / deny switches, the type chips on INFO, the trigger causes on TELEPORT, and the BORDER tab's NORTH/EAST/SOUTH/WEST all show their labels.
- [ ] Press B (show regions): "Regions shown." / "Regions hidden."

## 2026-10-15: trigger destination completion

- [ ] Type `/rg trigger add test enter ` and press Tab: here, minecraft:overworld, overworld, the other worlds, and (with shared storage) other servers' worlds as server/world. Pick a world, space, Tab: your x y z (all three, or just x). After `here` and after z nothing is offered (the message is free text).
- [ ] `/rg trigger add test timer ` then Tab: 10, 30, 60, 300; after the seconds the destinations as above.

## 2026-10-15: server switcher in the admin screen

Needs two backends with shared storage, regions on both in the Overworld.

- [ ] On A, `/rg gui`: the header shows SERVER < A > 1 of 2 (give it ten seconds after the server starts). Click >: the frame turns gold, the header says READ-ONLY, the list and map show B's regions in their colours, only MAP, FLAGS and INFO remain, and no button changes anything. FLAGS shows B's values; INFO shows B's priority, parent, bounds.
- [ ] + THIS SERVER on the map: A's regions appear underneath in grey; clicking one does nothing.
- [ ] While looking, have someone create or change a region on A: the screen stays on B.
- [ ] BACK TO A (or Esc, or < again): back to A's regions, editing works. Reopening the screen always starts on A.

## 2026-10-15: claim wipe from the admin screen

As a BSP Core admin, in a world with a few claims (and a copy of the world, in case):

- [ ] Pick the CLAIM filter: the list shows the claims and a red WIPE ALL N CLAIMS under it. Type something in the search box: the button disappears (it always wipes every claim, so it only shows when nothing is hidden).
- [ ] Press it: the right pane shows "WIPE EVERY CLAIM IN OVERWORLD", how many claims and players, a four-digit code, a typing box with WIPE, and the seconds counting down. Type a wrong code: nothing is wiped and the answer says so. Wait a minute: the code runs out and ASK FOR A CODE appears.
- [ ] Ask again, type the right code, WIPE: the claims are gone from the list and the map, the answer names the backup file, other admins online get a chat line, and the file appears under BACKUPS.
- [ ] RESTORE on that file: the claims come back with their owners.
- [ ] A non-admin operator gets "Only BSP Core admins can wipe claims." (when BSP Core's admin list is set).
- [ ] CLOSE, or picking another filter, returns the pane to the region view.

## 2026-10-15: BSP Core's Land Office

Needs BSP Core 1.0.1 from 2026-10-15 (the Land Office) and a world where claims are allowed, with an open area.

- [ ] As a player, outline ground inside the open area with the plot tool: on the second corner the Land Office opens with the outline drawn and priced. Buying there creates the claim (Region Guard's `/claim list` shows it).
- [ ] Outline next to another player's claim: the office's map shows that claim nearby with its owner.
- [ ] Open the office from its block after outlining: it shows and prices the same outline.
- [ ] `/claim buy <area>` as a player: "Buy at a Land Office...". As a BSP Core admin: it still buys.
- [ ] As an admin with bypass on, draw a region with the wand: no Land Office opens. `/rg bypass` off: it opens.
- [ ] In the claim screen, outline a bigger plot around yours and press ENLARGE: the Land Office opens with that outline for the enlargement.

## 2026-10-17: retest after B1 and B2 (edge links)

Same setup as the edge links section: two backends with shared storage, an Overworld border on both, an edge of A linked to B.

- [ ] As a player (or `/rg bypass` off), walk to the linked edge on A: the edge-of-world warning 10 blocks before, then the travel window as soon as you stand against the wall. TRAVEL: you arrive on B 16 blocks inside its matching edge. STAY: the window closes and you are not asked again until you walk more than 13 blocks back from the edge and return.
- [ ] As an admin with bypass on: the same window appears at the wall.
- [ ] The BORDER tab shows the centre as "around 0, 0" (no "d").

## 2026-10-17: larger screens and landing on the ground (B3, B4)

The new jar on every server **and every client**.

- [ ] `/rg gui`: the admin screen is about twice as big as before (smaller only if the window has no room). Every button, the search box, list scrolling, the map (click a region) and the flag list scrolling still answer where you click. Resize the window while it is open: it re-fits.
- [ ] The claim screen (`/claim gui`) and the travel window are larger in the same way.
- [ ] `config/bsp_region_guard-client.toml`, `scale = 1.0`, reopen: the old size. `scale = 1.5`: in between.
- [ ] Travel across a linked edge several times, ending up in forest, near water and on hills: always on the ground under open sky, never in a cave or inside blocks. In the Nether (if linked) the old search still applies.

## 2026-10-18: banned blocks, server side (T10.1a)

A region (`/rg define bantest` with the wand), yourself with `/rg bypass` off, and a second account that is a member (`/rg addmember bantest <name>`).

- [ ] `/rg flag bantest deny-place minecraft:tnt`: the member can't place TNT inside ("TNT can't be placed in bantest."), it stays in their hand; they can put it in a chest inside. Outside it places as usual.
- [ ] `/rg flag bantest deny-break minecraft:bookshelf`: nobody (owner, member, stranger) can break a bookshelf inside.
- [ ] `/rg flag bantest deny-use minecraft:flint_and_steel,minecraft:lever`: flint and steel does nothing inside, levers can't be flipped.
- [ ] `/rg flag bantest deny-carry minecraft:tnt`: walk up with TNT in your inventory: you're put back at the edge with "Remove TNT from your inventory to enter bantest." Try with TNT inside a shulker box, a bundle, a backpack (Sophisticated Backpacks), and worn in a Curios slot. Drop it outside and walk in.
- [ ] With TNT in your ender chest, stand inside and open it: the TNT won't come out (click, shift-click, number keys); other items do. Outside it comes out.
- [ ] Drop TNT inside (from outside, with a dispenser or by throwing): you can't pick it up inside.
- [ ] Log out inside holding TNT (give it with `/give` first, as an admin with bypass, then `/rg bypass` off): within a second you're moved just outside.
- [ ] `@minecraft` and `#minecraft:beds` entries ban a whole mod and every bed. `/rg bypass` on: none of the bans apply to you.
- [ ] With BSP Teleport: `/tp`-style moves or homes into the region are refused while carrying TNT.

## 2026-10-19: the banned blocks editor (T10.1b)

As an admin, `/rg gui`, pick a region.

- [ ] The tab row shows BANNED at the end, and every tab still fits. BANNED opens the editor; DONE or Esc returns to the region screen on the same region.
- [ ] The right side shows the creative tabs (vanilla and modded) across the top, with arrows when there are many; clicking one lists its blocks, scrolling works, hovering shows the item's tooltip.
- [ ] Type `@minecraft`: only vanilla things. `@create` (or any installed mod): only that mod's. `@minecraft stone`: vanilla things with "stone" in the name. A plain word searches every name.
- [ ] Pick PLACE, drag TNT to the left grid: it appears with a gold frame and a P; the region's `/rg info` shows `deny-place: minecraft:tnt`. Shift-click a block on the right: it is added at once.
- [ ] Right-click the TNT on the left: P, U, B, C in turn, its frame colour changing (gold, teal, violet, red); `/rg info` follows.
- [ ] Hover a banned block: the card at the bottom shows its name, id and four switches; switching them changes the lists; x REMOVE frees it. Dragging it back to the right frees it too.
- [ ] Point at a Create block, press BAN WHOLE MOD: an entry with a star appears (`@create`).
- [ ] Close and reopen: everything is as you left it. A second admin opening the same region sees the same list.
- [ ] Check the rules from the T10.1a section still apply with lists made here.

## 2026-10-19: the banned items pop-up (T10.1c)

The new jar on the server **and** the client (network protocol 5). A region with `deny-carry minecraft:tnt,minecraft:lava_bucket`.

- [ ] Walk in carrying 12 TNT and a shulker box holding a lava bucket: you're put back and a red-framed window opens, "YOU CAN'T BRING THESE INTO <region>", showing TNT (x 12) and the lava bucket (in Shulker Box), the two lines of advice, and OK. Hovering a picture shows the item's tooltip. OK or Esc closes it.
- [ ] Keep walking into the edge: the action-bar line repeats, but the window reopens no more than every 5 seconds.
- [ ] Carry seven different banned things: six pictures and "and 1 more".
- [ ] Stand inside, get TNT (as an admin with bypass, `/give`, then `/rg bypass` off): you're moved out and the window opens.

## 2026-10-20: the plot map (T10.2)

The new jar on the server **and** the client (network protocol 6). As a BSP Core admin, in a world with a few plots for sale, a claim someone owns, and an open area.

- [ ] `/rg plotmap` (or PLOT MAP under the admin screen's list) opens a full-window map centred on you. Terrain fills in around you, then further out as you pan (drag) and zoom (wheel, 1 to 16 pixels a block). From 8 pixels a block a block grid shows; the x and z under the cursor show bottom right.
- [ ] Pan far into ground you have never visited: it fills in from the saved world. Ground never generated stays black (and is not generated: check the world folder's size or fly there).
- [ ] The PLOTS card: search, the four filters, clicking a row snaps the map to that plot. FOLD hides it.
- [ ] Click a plot for sale on the map: its card shows size, height, priority. COPY: a teal box follows the mouse; click to drop it; drag it; ROTATE or R turns it; ▲/▼ shift its height; P -/+ its priority. Over another plot it turns red with the reason; wholly inside the open area with a higher priority it turns teal.
- [ ] SNAP on: edges line up with a neighbour's within two blocks; SHIFT held, or SNAP off, places freely.
- [ ] CONFIRM with a new name: the plot appears, `/rg info <name>` shows the original's flags, banned blocks, price and buyer flags, no owner; UNDO (1) takes it away, REDO (1) brings it back.
- [ ] UNLOCK a plot for sale, drag it, CONFIRM: it moves and shows locked again. CANCEL instead: it stays and locks. Try a claim someone owns: a red question asks first.
- [ ] Do 16 actions: UNDO goes back 15.
- [ ] The world switch (< >) shows another world in a gold frame, READ-ONLY, with no COPY, UNLOCK or UNDO.

## 2026-10-20: moving a plot's blocks (T10.3)

On a copy of the world, in the plot map, with a plot for sale that has a small build inside (a chest with items, a sign, an item frame, stairs, a sheep).

- [ ] UNLOCK it, turn it once (ROTATE), switch MOVE BLOCKS on, drop it somewhere clear and CONFIRM: a progress line counts up; the build appears at the new spot turned a quarter (stairs and the chest face the new way), the chest keeps its items, the sign its text, the frame and the sheep come along; nothing is dropped on the ground.
- [ ] The old spot is natural ground again: the right height, grass or sand on top as the biome has it, no build. (No trees or ores: expected.)
- [ ] Stand inside the plot while it moves: you arrive at the same spot in the new place.
- [ ] UNDO: both spots go back as they were (the build at the old spot, the new spot as it was before).
- [ ] COPY a plot with COPY BLOCKS on: the copy has the build, the original keeps its own.
- [ ] Move a full-height 64 x 64 plot: the server keeps ticking (watch TPS); it finishes within a minute or two.
- [ ] BACKUP on a plot's card, change something inside, BACKUPS, click the newest twice (SURE? RESTORE): the change is undone. `/rg plot backups <plot>` lists the files; they are in `world/bsp_region_guard/backups/plots/`.


## 2026-10-22: region size, vertical fill and corners on the map (B6, B7)

The new jar on the server **and** the client (network protocol 7). As a BSP Core admin. Built and tested headless (98 GameTests); nothing below has been seen in a client yet.

- [ ] `/rg gui`: the admin screen fills the window with a small margin, and text is a little smaller than before. Make the window smaller and larger: it follows, never smaller than before. The region list shows more rows, the MAP tab's map is larger, FLAGS shows more flags.
- [ ] Pick a region, INFO tab: SIZE shows FROM and TO as six numbers, the size line, VERTICAL FILL (-64 to 319 in the Overworld), SET CORNERS ON MAP and USE WAND SELECTION. Click a number, change it (it turns gold), Enter: the region moves (watch in the world or on MAP). Esc while typing puts the number back.
- [ ] Type a height above the world (e.g. 400) and Enter: a red sentence, nothing changes.
- [ ] VERTICAL FILL: the region now runs from the bottom to the top of the world; `/rg info` shows it. `/rg vfill <id>` does the same; `/rg vfill` with a wand selection stretches the selection (its box in the world grows).
- [ ] `/rg redefine <id> ~ ~ ~ ~10 ~5 ~10` moves the region to typed corners.
- [ ] With no wand selection, + NEW, type `harbour`, Enter. The plot map opens with "harbour has no corners yet: click the first corner". Protected regions show as grey dotted outlines.
- [ ] Click once: a marker. Move the mouse: a teal box follows. Drag the map: it pans, no corner is placed. Click the second corner: the bar shows the size and y -64 to 319.
- [ ] Change the bar's heights (e.g. 60 to 90). CONFIRM: you are back in the admin screen on harbour with its new box; INFO shows the corners. A third click before CONFIRM starts over.
- [ ] Before giving harbour corners: the list shows NO CORNERS in gold, and the header says "no corners yet". TELEPORT, SELECT, SALE and MINE give a "has no corners yet" sentence; flags can be set. Stand anywhere with `/rg bypass` off and a `build deny` flag on harbour: you can still build everywhere.
- [ ] SET CORNERS ON MAP on a region that has corners: click new ones, CONFIRM; then PLOT MAP, UNDO (1): its old corners come back.
- [ ] CANCEL or Esc in corner mode: back to the admin screen, nothing changed.
- [ ] Plot map, normal use: UNDO and REDO are in the world card at the top right and never sit over the PLOTS card. ▾ on the picked plot's card folds it to its name; ▸ opens it again. The same on the toolbar under a copy (COPY OF ...) and on the PLOTS card. The cards are a little smaller than before.
- [ ] Another server still on the older Region Guard, sharing storage: harbour (no corners) shows there as a tiny region far below the world and protects nothing.
- [ ] B9 retest, at several zoom levels including one step out from the block grid: a white frame marks the block under the cursor; click a build's corner and compare the bar's "first corner x .., z .." with the bottom-right x and z; CONFIRM and check `/rg info` gives the same numbers. SNAP is off at first; with it on, a corner within two blocks of another region's side lands just outside it, never against the region's own old box.
- [ ] B10: the plot map's REGIONS card lists protected areas (teal) and mines (gold) as well as plots; the PROT and MINE filters show only those. Click a protected region on the map: its card shows SET CORNERS ON MAP and no COPY or UNLOCK. SET CORNERS, click two corners, CONFIRM: you stay on the map, the region has its new box and keeps its height; UNDO puts it back.

## 2026-10-23: the region map (one map with REGION, PLOT and MINE modes)

The new jar on the server and the client. As a BSP Core admin. Built and tested headless (103 GameTests); nothing below has been seen in a client yet.

- [ ] Admin screen: there is no MAP tab (unless you look at another server); the **MAP** button under the list opens the region map centred on the selected region, picked. `/rg map` and `/rg plotmap` open it too.
- [ ] The top bar: REGION / PLOT / MINE on the left, PICK / DRAW / RESHAPE next to them, the world, UNDO, REDO and refresh on the right; nothing overlaps at your usual window size and a smaller one.
- [ ] REGION mode: every region is bright; PLOT mode: only plots, claims and open areas (the rest dimmed, can't be picked); MINE mode: mines and their shells. The REGIONS list follows the mode.
- [ ] PICK a protected region: its card has the name field (change it, Enter), P - / +, the type chips, FLAGS / ACCESS / TELEPORT / INFO (each opens the admin screen on that tab; Esc returns to the map), VERTICAL FILL, RESHAPE, DELETE (twice). UNDO after DELETE brings it back with its flags and owners.
- [ ] DRAW in REGION mode: drag a box; a NEW REGION card asks for an id, priority and height (whole world at first); CREATE: it appears, picked. Also click one corner, hold SPACE and drag to pan (or right-drag), click the second corner.
- [ ] DRAW in PLOT mode: the card also asks for a PRICE (CREATE stays off without one); the new plot is for sale. Over another plot it is refused with the reason.
- [ ] DRAW in MINE mode: the height starts 16 blocks below your feet; CREATE makes a mine; RESET and SHELL on the bar work on the picked mine.
- [ ] RESHAPE: the picked region shows eight white handles; drag a side or a corner a few blocks; change the bar's heights; DONE: the region has the new box (`/rg info`), UNDO puts it back. RESET in the bar drops the changes.
- [ ] Esc steps back: a half-drawn box or a pending reshape first, then the map closes.

## 2026-10-23: plots in PvP worlds (B11)

The new jar on the server and the client. As a BSP Core admin, in a world where BSP Core allows Shatter Totems, with `claims.allowInPvpWorlds = false` (the default). Built and tested headless (104 GameTests); the BORDER tab card has not been seen in a client yet.

- [ ] `/rg claims pvp`: "Plots are refused in ... a PvP world (the config)".
- [ ] Region map, PLOT mode, DRAW a plot with a price: refused, and the message names the world and says "Deny pvp on the world default, or allow plots on its BORDER tab (/rg claims pvp allow)".
- [ ] Admin screen, the world default, BORDER tab: a red PLOTS HERE card at the bottom right says "refused: PvP world (config)", CONFIG lit.
- [ ] Click ALLOW: the card turns teal, "allowed in this PvP world". DRAW the plot again: it is made. `/rg claims pvp` agrees.
- [ ] Click REFUSE: refused again. Click CONFIG: back to the config's answer.
- [ ] With CONFIG lit, set `pvp` to deny on the world default (FLAGS tab): the card says "allowed: pvp is denied" and plots can be made. Place your own Shatter Totem nearby and have a second player try to steal it: they are not stopped (the totem rule still applies).
- [ ] A world where totems are not allowed: the card says "allowed: not a PvP world" whatever the switch says.
- [ ] With shared storage: the setting stays after a restart (it lives on the world default).

## 2026-10-23: plot limits per region

As a BSP Core admin with a second test player, in a world where plots are allowed. Built and tested headless (106 GameTests).

- [ ] Make a protected region `market` around two plots for sale. SALE tab of `market`: "Plots per player inside: no limit". EDIT, type 1, Enter: "Plots per player inside: 1". `/rg plotlimit market` agrees.
- [ ] The second player buys one plot inside `market` (Land Office or `/claim buy` as admin): it works. The second plot inside: "You already own 1 plot inside market; its limit is 1."
- [ ] A plot outside `market` can still be bought.
- [ ] `/rg plotlimit market none`: the second plot inside can be bought.
- [ ] World default, BORDER tab, PLOTS HERE card: MAX: CONFIG. Click it, type 2: MAX 2. A player with 2 claims in this world is refused the third ("your limit is 2"); a LuckPerms group with a higher `limitsByRank` entry still raises it.

## 2026-10-23: Shatter Coin prices

Needs BSP-Core 1.4.3 or newer on the server and the client (older ones refuse to load with this Region Guard). Set `currency = "coins"` in the `[claims]` section of `world/serverconfig/bsp_region_guard-server.toml` and restart. Built and tested headless (106 GameTests); the screens have not been seen in a client.

- [ ] SALE tab of a plot for sale: the price reads "for sale at 3 copper coins" and five tier chips follow SET PRICE. Click GOLD: it lights and the price reads "3 gold coins". Click GOLD again: back to the config's tier. `/rg cointier <plot> gold` does the same.
- [ ] A player with no coins: `/claim quote` (or the Land Office's buy) is refused: "That plot costs 3 gold coins; you carry coins worth 0 copper."
- [ ] Give the player a netherite coin (worth 8 copper by default). Buy: the coin is taken and coins worth 2 copper come back as change. Their bank balance does not change.
- [ ] `/claim abandon <plot>`: "you get coins worth 3 copper back", coins arrive in the inventory; the plot is for sale again at 3 gold coins.
- [ ] As admin, abandon an offline player's coin-bought claim; when they log in they get the coins and a chat line.
- [ ] An open area: cost per block in coins; outline and buy; the quote says "blocks at 1 gold coin each".
- [ ] Switch `currency` back to `pieces` and restart: prices read as pieces again; a claim bought with coins still refunds coins.
- [ ] Land Office (BSP-Core 1.4.3, RG-C9): in coin mode it shows "3 gold coins" for the price, the cost per block and the outline quote, and the coins you carry instead of the bank balance. Buying there takes coins and gives change.

## 2026-10-23: map terrain after a server switch (B12)

On the network, as an admin on two servers whose overworlds look different. Built and tested headless (106 GameTests); the fix is client side and untested in a client.

- [ ] On server A, open the region map (`/rg map`) and look around so its terrain loads. Close it.
- [ ] Move to server B through the proxy (`/server B` or an edge link). Open the region map: only server B's terrain is drawn, filling in as it arrives; nothing of server A's islands or plots remains.
- [ ] Go back to server A: its terrain loads again (fresh, not left over from before).

## 2026-10-23: plot markers and teleport after buying (D105 to D114)

The new jar on the server and every client (network protocol 8). Built and tested headless (109 GameTests); none of the drawing below has been seen in a client.

- [ ] As a player (not an admin): the region key (B) is off at first; plots for sale still show, in gold with an arrow over their middle and the name above it. Press B: your own claims show too.
- [ ] As an admin, SALE tab of a plot for sale: LOOK opens the PLOT LOOK window. The preview turns with the TURN buttons and by dragging it.
- [ ] Try each marker: ARROW (20% see-through, bobbing), SPHERE (letters circling it), BEACON (a pulsing beam from the ground, a turning crystal), SIGN (FOR SALE, the name, the price), OUTLINE ONLY (just the name). The preview follows each change.
- [ ] Pick a colour from the eight, then type one in the hex box (e.g. #FF9F40): the preview and the swatch next to the box change. MARKER SIZE and NAME SIZE go from 0.50x to 3.00x; HEIGHT goes from 0 up to the plot's height (and never past 64).
- [ ] SAVE: back on the SALE tab; in the world the plot shows the new look at once, for you and for a player nearby. CANCEL or Esc: nothing changes.
- [ ] Walk around the plot: the marker and its name turn to face you. From more than 96 blocks away the marker goes (the outline stays).
- [ ] A plot whose middle is on a hill: the marker stands the chosen height above the ground there, and never above the plot's top.
- [ ] Region map, PLOT mode, pick a plot for sale: LOOK on the card opens the same window; Esc comes back to the map.
- [ ] Copy that plot on the map: the copy has the same look and shows its own name.
- [ ] An open area: the same look over the area's middle; the sign's price says "... a block". Plots outlined inside it have no marker once bought.
- [ ] `/rg look <plot>` describes the look; `/rg look <plot> marker sign`, `colour #5AA9FF`, `size 2`, `namesize 1.5`, `height 6` each change one part.
- [ ] As a player, buy the plot at a Land Office: the Land Office closes and "You bought ..." asks TELEPORT or STAY. TELEPORT puts you on the ground in the plot's middle (or on its teleport point). STAY or Esc leaves you where you are. The plot's marker and outline are gone for everyone.
- [ ] Outline and buy a plot in an open area: the same pop-up. Enlarging a plot does not ask.
- [ ] With frame rate shown (F3), stand among several plots with markers: no noticeable drop.
- [ ] Stand outside a plot for sale and look at it: the marker shows through the plot's faint walls (it used to be hidden below the plot's top).
- [ ] Video settings, Graphics: Fabulous. Open PLOT LOOK: the preview still shows the outline and the arrow's and sign's edges.
- [ ] Sign with NAME SIZE 3: the three lines do not touch. Sphere with MARKER SIZE 0.5 and NAME SIZE 3: the letters stay apart.
- [ ] A plot whose buyer may set a long list of flags: the SALE tab's BUYER MAY SET line ends in "..." before its edit button.
- [ ] Region map, zoomed all the way out, frame rate shown (F3): it stays smooth while panning. Terrain fills in as it arrives with no gaps or stripes between 8-by-8-chunk squares; REFRESH (↻) repaints it; switching world with < > shows that world's terrain only.

## 2026-10-24: leaving RESHAPE (B13)

- [ ] Region map, pick a region, RESHAPE, change nothing: the bar shows DONE (grey), RESET and a red CANCEL. CANCEL: the bar goes, the tool is PICK again, the region is still picked and unchanged.
- [ ] RESHAPE again, drag a side, then CANCEL: the region keeps its old box (nothing was sent; UNDO count unchanged).
- [ ] RESHAPE, press Esc: back to PICK. Esc again: the map closes.
- [ ] Fold the reshape bar (▾): CANCEL is still on its title line and works.
- [ ] RESHAPE, change the box, DONE, then CANCEL: the new box stays (UNDO brings the old one back).

## 2026-10-25: button answers in the admin screen (B14, protocol 9)

- [ ] MINE tab of a mine: click RESET NOW. The button flashes, and the bottom line says "iron_mine is resetting (... blocks)." in teal for a few seconds, then goes back to "Changes save at once. Esc closes."
- [ ] MOVE SPAWN (or SET SPAWN): flashes; the bottom line says players are moved here when it resets.
- [ ] Something refused (for example SALE on a region without corners): the answer shows in red.
- [ ] A long answer is cut with "..." inside the pane.
- [ ] With the screen closed, a command answer still shows on the action bar as before.

## 2026-10-25: the MINE FILL window (D115 to D119)

- [ ] MINE tab of a mine: the fill rows show shares like "80.0%"; EDIT FILL opens the MINE FILL window with the same blocks and percentages (old weights turned into percentages that add up to 100).
- [ ] The browser on the right: the creative tabs that hold blocks (hover shows a tab's name), the search box (try "iron" and "@create"), blocks already in the mine framed in gold, the wheel scrolls the grid and the tabs.
- [ ] Click a new block: it appears at the bottom of the list with an empty box; the total turns red and says how much is left. Type a share (e.g. 2.5): the total follows.
- [ ] Mark a block REST: its box turns gold and shows what is left; the total shows 100.0% in teal and SAVE lights. Lower another share: REST grows. Raise the others past 100: "The REST block has nothing left".
- [ ] Without REST: SAVE lights only at exactly 100.0%. Type "abc" or 0: a red message, no SAVE.
- [ ] x removes a block (removing the REST block clears REST). A new, empty mine: the first block added is REST.
- [ ] SAVE: back on the MINE tab, the rows show the new shares and "REST" in gold; RESET NOW refills with the REST block as the base and the others as veins.
- [ ] Region map, MINE mode, pick a mine: EDIT FILL on the card opens the window; Esc comes back to the map.
- [ ] 25th block: "A mine takes at most 24 blocks."

## 2026-10-25: region outlines only with the wand (D120 to D124)

- [ ] As an admin, define a region with the wand and put the wand away: the region's outline and the gold selection go. Take the wand out again: every region shows.
- [ ] Wand in the off hand: outlines show too.
- [ ] Without the wand, press B: outlines show; press B again: they go. After rejoining, B is off again.
- [ ] Plots for sale keep their marker and outline with no wand and B off, for admins and players.
- [ ] A player holding the plot tool sees their own claim; putting it away hides it.

## 2026-10-25: names in the region list (B15)

- [ ] INFO tab, edit the name of a region (e.g. "Tadana Plot 1" for `tadana`), Enter: the list on the left shows "Tadana Plot 1" straight away; the header still shows "Tadana Plot 1 (tadana)".
- [ ] A region without a name set shows its id, as before. Search finds the region by its name and by its id.
- [ ] A long name is cut before the type letter and priority on the right of the row.
- [ ] From 2026-10-25 every build needs BSP-Core 1.4.6 or newer on the server and the client; with an older BSP-Core, Region Guard refuses to load.

## 2026-10-25: renaming changes the id (B16)

- [ ] A mine `mine-i` (renamed earlier to "Mine-II" but still `mine-i` underneath): INFO, set the name to "Mine-II" again, Enter. The header now reads "Mine-II (mine-ii)" and the list keeps it selected. `/rg define Mine-I` with a wand selection now works.
- [ ] The renamed mine still has its fill, flags, owners and reset timer; its shell is now `mine-ii_shell`; RESET NOW works.
- [ ] A plot with backups: rename it; the map's BACKUPS still lists them and RESTORE works.
- [ ] On the region map, rename a region from its card: the card stays on it; UNDO of an earlier move of that region still works.
- [ ] Rename to a name whose id exists ("Spawn" when `spawn` exists): refused in red, nothing changes.
- [ ] The world default can be given a name; its id stays `__global__`.
- [ ] With shared storage: another server's view of this server's regions shows the new id, and the old one is gone.
- [ ] Note: a player standing inside the region when it is renamed may see its greeting once more.

## 2026-10-27: every rank on the RANKS tab (B17)

- [ ] On a server with more than 18 LuckPerms groups (or RankUp's ladder plus other groups), open a region's RANKS tab: the grid is filled, and the bottom right says "N more: scroll for them".
- [ ] The mouse wheel over the pane scrolls the grid down to the last groups (vanguard, wanderer, warden, warlord...); every group LuckPerms has is there.
- [ ] Ticking a group further down works and stays ticked after scrolling back.
- [ ] A wider window shows more groups per row; the smallest window still shows three per row inside the pane.
- [ ] Switching to another tab and back starts the grid at the top.

## 2026-10-28: network passwords out of the server config (T14)

On a server that used shared storage with 0.6.0 (or a copy of its world and configs). Built and tested headless (112 GameTests, run twice, plus a start from an old server config).

- [ ] Start once with the new jar. The log has one warning: "Region Guard moved storage.host, ..., storage.password ... to config/bsp_region_guard-network.toml"; it shows no password or host.
- [ ] `config/bsp_region_guard-network.toml` now holds the host, port, database, user, password, tablePrefix and the Redis host, port, username and password; `serverconfig/bsp_region_guard-server.toml` shows them empty with "Moved to config/bsp_region_guard-network.toml".
- [ ] `/rg storage status` connects as before (database and Redis); other servers still see this one's regions; BSP-Teleport and BSP-RankUp still find this server's name.
- [ ] Restart: no second warning; nothing changes in either file.
- [ ] A player joins: their game never receives the network file (it is a common config); the server file they receive has no passwords.
- [ ] Change the database and Redis passwords in both the database/Redis and the network file on every server, and restart.

