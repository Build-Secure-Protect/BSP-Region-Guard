package com.mrgregles.bsp_region_guard.flag;

import org.jetbrains.annotations.Nullable;

/**
 * Region Guard's own flags. The list follows WorldGuard's, plus the BSP additions at the end; what each one does
 * and how it is enforced is in {@code docs/FLAGS.md}. Every flag carries a category and a one-line hint for the
 * screen.
 *
 * <p>Conventions: a <em>build-family</em> flag ({@link #BUILD} and the flags that fall back to it) lets owners and
 * members act and stops everyone else unless set explicitly. Every other state flag is simply allow or deny, with
 * the default shown here.
 */
public final class Flags {
    public static final String PROTECTION = "Protection", MOBS = "Mobs, fire and explosions", NATURE = "Natural events",
            MOVEMENT = "Movement", MAP_MAKING = "Map making", MISC = "Miscellaneous", BSP = "BSP";

    private Flags() {
    }

    // ----------------------------------------------------------------- overrides
    public static final StateFlag PASSTHROUGH = plain("passthrough", State.DENY, PROTECTION, "allow: this region does not protect its ground, only carries flags");

    // ----------------------------------------------------------------- protection (build family)
    public static final StateFlag BUILD = FlagRegistry.register(new StateFlag("build", null, true, null)).describe(PROTECTION, "the umbrella: members may build, others may not, unless set");
    public static final StateFlag BLOCK_BREAK = build("block-break", "breaking blocks");
    public static final StateFlag BLOCK_PLACE = build("block-place", "placing blocks");
    public static final StateFlag INTERACT = build("interact", "using blocks with a screen or state, animals, villagers, armour stands");
    public static final StateFlag USE = build("use", "doors, trapdoors, gates, buttons, levers, repeaters");
    public static final StateFlag CHEST_ACCESS = build("chest-access", "opening any inventory");
    public static final StateFlag RIDE = build("ride", "mounting boats, minecarts and animals");
    public static final StateFlag SLEEP = build("sleep", "beds");
    public static final StateFlag RESPAWN_ANCHORS = build("respawn-anchors", "charging or using respawn anchors");
    public static final StateFlag VEHICLE_PLACE = build("vehicle-place", "placing boats and minecarts");
    public static final StateFlag VEHICLE_DESTROY = build("vehicle-destroy", "breaking boats and minecarts");
    public static final StateFlag LIGHTER = build("lighter", "flint and steel, fire charges");
    public static final StateFlag BLOCK_TRAMPLING = build("block-trampling", "farmland and turtle eggs");
    public static final StateFlag ITEM_FRAME_ROTATION = build("item-frame-rotation", "rotating items in frames");
    public static final StateFlag USE_ANVIL = build("use-anvil", "anvils");
    public static final StateFlag USE_DRIPLEAF = build("use-dripleaf", "big dripleaf");
    public static final StateFlag DAMAGE_ANIMALS = build("damage-animals", "hurting animals, villagers, golems");

    // ----------------------------------------------------------------- protection (plain)
    public static final StateFlag PVP = plain("pvp", State.ALLOW, PROTECTION, "player against player, where either stands");
    public static final StateFlag TNT = plain("tnt", State.ALLOW, PROTECTION, "TNT and TNT minecarts");
    public static final StateFlag FIREWORK_DAMAGE = plain("firework-damage", State.ALLOW, PROTECTION, "firework rockets hurting players");

    // ----------------------------------------------------------------- mobs, fire and explosions
    public static final StateFlag CREEPER_EXPLOSION = plain("creeper-explosion", State.ALLOW, MOBS, "creeper blasts; also spares blocks inside");
    public static final StateFlag ENDERDRAGON_BLOCK_DAMAGE = plain("enderdragon-block-damage", State.ALLOW, MOBS, "the dragon breaking blocks");
    public static final StateFlag GHAST_FIREBALL = plain("ghast-fireball", State.ALLOW, MOBS, "fireballs and wither skulls");
    public static final StateFlag OTHER_EXPLOSION = plain("other-explosion", State.ALLOW, MOBS, "beds, crystals, modded explosions");
    public static final StateFlag WITHER_DAMAGE = plain("wither-damage", State.ALLOW, MOBS, "wither explosions and block breaking");
    public static final StateFlag FIRE_SPREAD = plain("fire-spread", State.ALLOW, MOBS, "fire catching on new blocks (mixin: fire)");
    public static final StateFlag FIRE_BURN = plain("fire-burn", State.ALLOW, MOBS, "fire destroying a block (mixin: fire)");
    public static final StateFlag LAVA_FIRE = plain("lava-fire", State.ALLOW, MOBS, "lava starting fires (mixin: fire)");
    public static final StateFlag ENDERMAN_GRIEF = plain("enderman-grief", State.ALLOW, MOBS, "endermen picking up or placing blocks");
    public static final StateFlag SNOWMAN_TRAILS = plain("snowman-trails", State.ALLOW, MOBS, "snow golems leaving snow");
    public static final StateFlag RAVAGER_GRIEF = plain("ravager-grief", State.ALLOW, MOBS, "ravagers breaking blocks");
    public static final StateFlag MOB_DAMAGE = plain("mob-damage", State.ALLOW, MOBS, "mobs hurting players");
    public static final StateFlag MOB_SPAWNING = plain("mob-spawning", State.ALLOW, MOBS, "natural, spawner and egg spawns");
    public static final StringSetFlag DENY_SPAWN = FlagRegistry.register(new StringSetFlag("deny-spawn")).describe(MOBS, "mob ids that may not spawn, comma separated");
    public static final StateFlag ENTITY_PAINTING_DESTROY = plain("entity-painting-destroy", State.ALLOW, MOBS, "non-players breaking paintings");
    public static final StateFlag ENTITY_ITEM_FRAME_DESTROY = plain("entity-item-frame-destroy", State.ALLOW, MOBS, "non-players breaking item frames");

    // ----------------------------------------------------------------- natural events
    public static final StateFlag LIGHTNING = plain("lightning", State.ALLOW, NATURE, "lightning strikes");
    public static final StateFlag WATER_FLOW = plain("water-flow", State.ALLOW, NATURE, "water spreading in or into the region (mixin: fluids)");
    public static final StateFlag LAVA_FLOW = plain("lava-flow", State.ALLOW, NATURE, "lava spreading in or into the region (mixin: fluids)");
    public static final StateFlag SNOW_FALL = plain("snow-fall", State.ALLOW, NATURE, "snow settling (mixin: weather)");
    public static final StateFlag SNOW_MELT = plain("snow-melt", State.ALLOW, NATURE, "snow melting (mixin: weather)");
    public static final StateFlag ICE_FORM = plain("ice-form", State.ALLOW, NATURE, "water freezing (mixin: weather)");
    public static final StateFlag ICE_MELT = plain("ice-melt", State.ALLOW, NATURE, "ice melting (mixin: weather)");
    public static final StateFlag FROSTED_ICE_FORM = plain("frosted-ice-form", State.ALLOW, NATURE, "Frost Walker ice (mixin: weather)");
    public static final StateFlag FROSTED_ICE_MELT = plain("frosted-ice-melt", State.ALLOW, NATURE, "frosted ice melting (mixin: weather)");
    public static final StateFlag MUSHROOM_GROWTH = plain("mushroom-growth", State.ALLOW, NATURE, "mushrooms spreading (mixin: growth)");
    public static final StateFlag LEAF_DECAY = plain("leaf-decay", State.ALLOW, NATURE, "leaves decaying (mixin: growth)");
    public static final StateFlag GRASS_GROWTH = plain("grass-growth", State.ALLOW, NATURE, "grass spreading onto dirt (mixin: growth)");
    public static final StateFlag MYCELIUM_SPREAD = plain("mycelium-spread", State.ALLOW, NATURE, "mycelium spreading (mixin: growth)");
    public static final StateFlag VINE_GROWTH = plain("vine-growth", State.ALLOW, NATURE, "vines and kelp growing");
    public static final StateFlag ROCK_GROWTH = plain("rock-growth", State.ALLOW, NATURE, "dripstone and amethyst growing (mixin: growth)");
    public static final StateFlag SCULK_GROWTH = plain("sculk-growth", State.ALLOW, NATURE, "sculk spreading (mixin: growth)");
    public static final StateFlag CROP_GROWTH = plain("crop-growth", State.ALLOW, NATURE, "crops, saplings, cactus, sugar cane, bamboo");
    public static final StateFlag SOIL_DRY = plain("soil-dry", State.ALLOW, NATURE, "farmland turning back to dirt (mixin: growth)");
    public static final StateFlag CORAL_FADE = plain("coral-fade", State.ALLOW, NATURE, "coral dying out of water (mixin: growth)");
    public static final StateFlag COPPER_FADE = plain("copper-fade", State.ALLOW, NATURE, "copper oxidising (mixin: growth)");

    // ----------------------------------------------------------------- movement
    public static final StateFlag ENTRY = FlagRegistry.register(new StateFlag("entry", null, true, null)).describe(MOVEMENT, "deny keeps non-members out; members always pass");
    public static final StateFlag EXIT = FlagRegistry.register(new StateFlag("exit", null, true, null)).describe(MOVEMENT, "deny keeps non-members in; members always pass");
    public static final StateFlag EXIT_VIA_TELEPORT = plain("exit-via-teleport", State.ALLOW, MOVEMENT, "leaving by teleport when exit is denied");
    public static final BooleanFlag EXIT_OVERRIDE = FlagRegistry.register(new BooleanFlag("exit-override", false)).describe(MOVEMENT, "true: everyone may always leave");
    public static final StringFlag ENTRY_DENY_MESSAGE = string("entry-deny-message", "You are not allowed in there.", MOVEMENT, "shown when entry is refused");
    public static final StringFlag EXIT_DENY_MESSAGE = string("exit-deny-message", "You are not allowed to leave here.", MOVEMENT, "shown when exit is refused");
    public static final BooleanFlag NOTIFY_ENTER = FlagRegistry.register(new BooleanFlag("notify-enter", false)).describe(MOVEMENT, "tell admins when someone enters");
    public static final BooleanFlag NOTIFY_LEAVE = FlagRegistry.register(new BooleanFlag("notify-leave", false)).describe(MOVEMENT, "tell admins when someone leaves");
    public static final StringFlag GREETING = string("greeting", null, MOVEMENT, "chat line on entering");
    public static final StringFlag GREETING_TITLE = string("greeting-title", null, MOVEMENT, "title on entering; \\n before the subtitle");
    public static final StringFlag FAREWELL = string("farewell", null, MOVEMENT, "chat line on leaving");
    public static final StringFlag FAREWELL_TITLE = string("farewell-title", null, MOVEMENT, "title on leaving; \\n before the subtitle");
    public static final StateFlag ENDERPEARL = plain("enderpearl", State.ALLOW, MOVEMENT, "ender pearls from or into the region");
    public static final StateFlag CHORUS_FRUIT_TELEPORT = plain("chorus-fruit-teleport", State.ALLOW, MOVEMENT, "chorus fruit from or into the region");
    public static final LocationFlag TELEPORT = FlagRegistry.register(new LocationFlag("teleport")).describe(MOVEMENT, "where /rg teleport goes; 'here' for your position");
    public static final LocationFlag SPAWN = FlagRegistry.register(new LocationFlag("spawn")).describe(MOVEMENT, "where players who die inside respawn");
    public static final StringFlag TELEPORT_MESSAGE = string("teleport-message", null, MOVEMENT, "shown after /rg teleport");
    public static final StringFlag BORDER_WARNING = string("border-warning", null, MOVEMENT, "world default: shown near the world border (border.warnDistance blocks before it)");
    public static final StringFlag BORDER_MESSAGE = string("border-message", null, MOVEMENT, "world default: shown when a player is pushed back at the world border");

    // ----------------------------------------------------------------- map making
    public static final StateFlag ITEM_PICKUP = plain("item-pickup", State.ALLOW, MAP_MAKING, "picking up items");
    public static final StateFlag ITEM_DROP = plain("item-drop", State.ALLOW, MAP_MAKING, "dropping items");
    public static final StateFlag EXP_DROPS = plain("exp-drops", State.ALLOW, MAP_MAKING, "experience orbs from mobs and blocks");
    public static final StringFlag DENY_MESSAGE = string("deny-message", "You can't do that here.", MAP_MAKING, "the refusal message");
    public static final StateFlag INVINCIBLE = plain("invincible", State.DENY, MAP_MAKING, "allow: players inside take no damage");
    public static final StateFlag FALL_DAMAGE = plain("fall-damage", State.ALLOW, MAP_MAKING, "fall damage");
    public static final GameModeFlag GAME_MODE = FlagRegistry.register(new GameModeFlag("game-mode")).describe(MAP_MAKING, "game mode forced while inside");
    public static final StringFlag TIME_LOCK = string("time-lock", null, MAP_MAKING, "day, noon, night, midnight or ticks; what players see");
    public static final StringFlag WEATHER_LOCK = FlagRegistry.register(new StringFlag("weather-lock", null, "clear", "downfall")).describe(MAP_MAKING, "clear or downfall; what players see");
    public static final StateFlag NATURAL_HEALTH_REGEN = plain("natural-health-regen", State.ALLOW, MAP_MAKING, "healing from food (mixin: hunger)");
    public static final StateFlag NATURAL_HUNGER_DRAIN = plain("natural-hunger-drain", State.ALLOW, MAP_MAKING, "getting hungry (mixin: hunger)");
    public static final IntFlag HEAL_DELAY = FlagRegistry.register(new IntFlag("heal-delay", null)).describe(MAP_MAKING, "seconds between heals");
    public static final IntFlag HEAL_AMOUNT = FlagRegistry.register(new IntFlag("heal-amount", null)).describe(MAP_MAKING, "half-hearts per heal; negative hurts");
    public static final DoubleFlag HEAL_MIN_HEALTH = FlagRegistry.register(new DoubleFlag("heal-min-health", null)).describe(MAP_MAKING, "hurting stops at this health");
    public static final DoubleFlag HEAL_MAX_HEALTH = FlagRegistry.register(new DoubleFlag("heal-max-health", null)).describe(MAP_MAKING, "healing stops at this health");
    public static final IntFlag FEED_DELAY = FlagRegistry.register(new IntFlag("feed-delay", null)).describe(MAP_MAKING, "seconds between feedings");
    public static final IntFlag FEED_AMOUNT = FlagRegistry.register(new IntFlag("feed-amount", null)).describe(MAP_MAKING, "food points per feeding; negative starves");
    public static final IntFlag FEED_MIN_HUNGER = FlagRegistry.register(new IntFlag("feed-min-hunger", null)).describe(MAP_MAKING, "starving stops at this food level");
    public static final IntFlag FEED_MAX_HUNGER = FlagRegistry.register(new IntFlag("feed-max-hunger", null)).describe(MAP_MAKING, "feeding stops at this food level");
    public static final StringSetFlag BLOCKED_CMDS = FlagRegistry.register(new StringSetFlag("blocked-cmds")).describe(MAP_MAKING, "commands refused inside, comma separated");
    public static final StringSetFlag ALLOWED_CMDS = FlagRegistry.register(new StringSetFlag("allowed-cmds")).describe(MAP_MAKING, "only these commands work inside");

    // ----------------------------------------------------------------- miscellaneous
    public static final StateFlag PISTONS = plain("pistons", State.ALLOW, MISC, "pistons moving, including into the region");
    public static final StateFlag SEND_CHAT = plain("send-chat", State.ALLOW, MISC, "chatting from inside");
    public static final StateFlag RECEIVE_CHAT = plain("receive-chat", State.ALLOW, MISC, "hearing chat while inside");
    public static final StateFlag POTION_SPLASH = plain("potion-splash", State.ALLOW, MISC, "splash and lingering potions landing");

    // ----------------------------------------------------------------- BSP additions
    public static final StateFlag DISPENSER_USE = plain("dispenser-use", State.ALLOW, BSP, "dispensers firing into the region (mixin: dispensers)");
    public static final StateFlag FALLING_BLOCKS = plain("falling-blocks", State.ALLOW, BSP, "sand and gravel landing inside (mixin: fallingBlocks)");
    // banned blocks (D51 to D55): block or item ids, "@mod" or "mod:*" for a whole mod, "#mod:tag" for a tag
    public static final StringSetFlag DENY_PLACE = FlagRegistry.register(new StringSetFlag("deny-place")).describe(BSP, "blocks nobody may place here: ids, @mod, #tag");
    public static final StringSetFlag DENY_USE = FlagRegistry.register(new StringSetFlag("deny-use")).describe(BSP, "blocks and items nobody may right-click here");
    public static final StringSetFlag DENY_BREAK = FlagRegistry.register(new StringSetFlag("deny-break")).describe(BSP, "blocks nobody may break here");
    public static final StringSetFlag DENY_CARRY = FlagRegistry.register(new StringSetFlag("deny-carry")).describe(BSP, "blocks and items not allowed in at all, even carried");
    public static final StringSetFlag ALLOWED_RANKS = FlagRegistry.register(new StringSetFlag("allowed-ranks")).describe(BSP, "LuckPerms groups that may enter");
    public static final StateFlag TELEPORT_IN = plain("teleport-in", State.ALLOW, BSP, "BSP Teleport: teleporting into the region");
    public static final StateFlag TELEPORT_OUT = plain("teleport-out", State.ALLOW, BSP, "BSP Teleport: teleporting out");
    public static final StateFlag SET_HOME = plain("set-home", State.ALLOW, BSP, "BSP Teleport: setting a home here");
    public static final StateFlag TOTEM_PLACE = plain("totem-place", State.ALLOW, BSP, "BSP Core: placing Shatter Totems");
    public static final StateFlag TOTEM_DROP = plain("totem-drop", State.ALLOW, BSP, "BSP Core: dropping Shatter Totems");
    public static final StateFlag MINE_ONLY_BREAK = plain("mine-only-break", State.DENY, BSP, "mines: only the mine's blocks may be broken");

    /** Forces the class to load, registering every flag. */
    public static void init() {
    }

    private static StateFlag plain(String name, State defaultValue, String category, String hint) {
        return FlagRegistry.register(new StateFlag(name, defaultValue, false, null)).describe(category, hint);
    }

    private static StateFlag build(String name, String hint) {
        return FlagRegistry.register(new StateFlag(name, null, true, BUILD)).describe(PROTECTION, hint);
    }

    private static StringFlag string(String name, @Nullable String defaultValue, String category, String hint) {
        return FlagRegistry.register(new StringFlag(name, defaultValue)).describe(category, hint);
    }
}
