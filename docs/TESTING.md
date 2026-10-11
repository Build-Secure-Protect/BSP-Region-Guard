# Testing BSP Region Guard in game

What to try after each build, in the order the features were added. Tick what works, note what does not. Claude cannot run the client, so this list is the hand-off between a build and the owner.

Headless checks already run by Claude: `./gradlew runGameTestServer` (region model and resolution rules).

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

Not yet in this build: in-world region drawing (Phase 3b), claims (Phase 4), mines (Phase 5).
