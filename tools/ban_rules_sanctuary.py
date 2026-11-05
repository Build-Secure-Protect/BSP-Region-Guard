"""Banned-list rules for Sanctuary plots (owner's scope, 2026-11-05).

Sanctuary: one-chunk backup homes. Store anything, build beautiful homes, but
no making, mining or growing resources, no totems, no weapons or explosives in
use, no other dimensions, small power, small ME/RS without autocrafting.

One tag, sanctuary_bans, set on deny-place and deny-use (stored is fine).
The owner chose no deny-carry tag (2026-11-05): players may store anything, so
cells, drives, cards and upgrades slotted into a machine's screen are not stopped.
Lava stays allowed (cobblestone generators are fine).
Patterns are full-match regexes on ids; "#ns:tag" adds an existing tag.
"""

SCOPE = "sanctuary"
P = "sanctuary_bans"

# Never match these (storage blocks of metals, molten buckets, decoration).
EXCLUDE = [
    r"createbigcannons:(.*_block|.*_scrap|molten_.*|casting_sand|traffic_cone|gas_mask)",
    r"createarmory:molten_brass.*",
    r"rftoolsdim:dimensional_.*block.*",
]

RULES = [
    # ------------------------------------------------------------ totems
    (P, "Totems", "No totem placing on Sanctuary", [
        r"bsp_core:(shatter_totem|decoy_totem|decoy_power_base|totem_projector)"]),
    (P, "Totems", "Makes totems (production)", [r"bsp_core:totem_forge"]),
    (P, "Totems", "Sends totem plasma to other worlds", [
        r"bsp_core:(accelerator_frame|accelerator_intake|decelerator_core|decelerator_port)"]),

    # ------------------------------------------------------------ explosives
    (P, "Explosives", "Explodes", [
        r"minecraft:(tnt|tnt_minecart|end_crystal)", r"mekanismadditions:obsidian_tnt", r"ae2:tiny_tnt",
        r"thermal:.*_(tnt|tnt_minecart|grenade|grenade_thrown)", r"thermal:(detonator.*|earth_charge|ice_charge|lightning_charge)",
        r"nuclear_bombs:(.*_bomb|nuke.*|lit_bomb|thermonuclearbomb)", r"industrialforegoing:infinity_nuke",
        r"botania:mana_bomb", r"botania:lens_explosive", r"pneumaticcraft:(micromissiles|gun_ammo_explosive)",
        r"apotheosis:explosive_arrow"]),
    (P, "Explosives", "Explodes outside the Nether", [r"minecraft:respawn_anchor"]),
    (P, "Explosives", "Firework rockets explode (and boost elytra)", [r"minecraft:firework_rocket"]),
    (P, "Explosives", "Military mod: missiles, launchers, turrets, radar", [r"ballistix:.*"]),
    (P, "Explosives", "Decoy trap charges (blast, hex, warp)", [
        r"bsp_core:(blast_charge|fatigue_charge|hex_charge|poison_charge|warp_charge|trap_amplifier)"]),
    (P, "Explosives", "Burns explosives for power", [
        r"industrialforegoing:mycelial_(explosive|rocket)", r"botania:(floating_)?entropinnyum"]),
    (P, "Explosives", "Makes explosives (production)", [r"nuclear_bombs:explosive_crafter"]),

    # ------------------------------------------------------------ weapons
    (P, "Weapons", "Sword", ["#minecraft:swords", "#forge:tools/swords", r".*:([a-z0-9_]*_)?sword",
                             r"gobber2:gobber2_sword.*", r"tconstruct:(cleaver|dagger)"]),
    (P, "Weapons", "Bow, crossbow or trident", [
        "#forge:tools/bows", "#forge:tools/crossbows", "#forge:tools/tridents",
        r"minecraft:(bow|crossbow|trident)", r"gobber2:gobber2_bow(_end|_nether)?",
        r"botania:(livingwood_bow|crystal_bow)", r"mekanism:electric_bow",
        r"tconstruct:(longbow|crossbow|javelin)"]),
    (P, "Weapons", "Arrows and ammo", [
        r"minecraft:(arrow|spectral_arrow|tipped_arrow)",
        r"apotheosis:(broadhead_arrow|obsidian_arrow|iron_mining_arrow|diamond_mining_arrow)",
        r"pneumaticcraft:gun_ammo.*"]),
    (P, "Weapons", "Thrown weapon", [
        r"tconstruct:(shuriken|flint_shuriken|quartz_shuriken|throwing_axe)",
        r"botania:(thorn_chakram|flare_chakram|slingshot|vine_ball)"]),
    (P, "Weapons", "Gun, cannon or launcher", [
        r"createarmory:.*", r"createbigcannons:.*", r"create:potato_cannon", r"ae2:matter_cannon",
        r"botania:mana_gun", r"industrialforegoing:(infinity_launcher.*|infinity_trident.*)",
        r"electrodynamics:(railgunkinetic|railgunplasma|mechanizedcrossbow)",
        r"pneumaticcraft:(minigun|air_cannon|vortex_cannon)",
        r"tconstruct:(seared_fluid_cannon|scorched_fluid_cannon|end_fluid_cannon)",
        r"immersive_aircraft:(rotary_cannon|heavy_crossbow|bomb_bay|warship)",
        r"powersuits:(plasma_cannon|railgun|blade_launcher|lightning_summoner)",
        r"gobber2:gobber2_staff_sniper"]),
    (P, "Weapons", "Melee weapon", [
        r"electrodynamics:electricbaton(on)?", r"industrialforegoing:infinity_hammer",
        r"powersuits:(powerfist|melee_assist)", r"mekanism:atomic_disassembler"]),
    (P, "Weapons", "Fire weapon", [r"mekanism:flamethrower"]),
    (P, "Weapons", "Turret or attack drone", [
        r"mekanism_turrets:.*", r"pneumaticcraft:(sentry_turret|guard_drone)"]),
    (P, "Weapons", "Trap that hurts", [r"blastcraft:spike(fire|poison)?", r"tconstruct:punji",
                                       r"mob_grinding_utils:spikes"]),
    (P, "Weapons", "Force field that hurts or takes items", [
        r"mffs:(anti_hostile_module|anti_personnel_module|anti_friendly_module|shock_module|confiscation_module|interdiction_matrix)"]),

    # ------------------------------------------------------------ fire
    (P, "Fire", "Starts fires (also lights nether portals)", [
        r"minecraft:(flint_and_steel|fire_charge)", r"tconstruct:flint_and_brick",
        r"enderio:cold_fire_igniter", r"powersuits:flint_and_steel"]),

    # ------------------------------------------------------------ dimensions and teleports
    (P, "Dimensions and teleports", "Goes to another dimension", [
        r"compactmachines:(machine_.*|personal_shrinking_device|tunnel|wall)", r"allthemodium:teleport_pad",
        r"rftoolsdim:.*", r"powersuits:dim_rift_gen", r"botania:alfheim_portal",
        r"createteleporters:(custom_portal.*|customportal|quantum_portal_block|pocket_d_block|pocket_dimension_remote)",
        r"minecraft:ender_eye"]),
    (P, "Dimensions and teleports", "Teleporter", [
        r"mekanism:(teleporter|teleporter_frame|portable_teleporter|module_teleportation_unit)",
        r"createteleporters:(teleporter|item_tp|block_teleporter|entity_teleporter_.*|tp_link|adv_tplink|redstone_pearl)",
        r"wormhole:(portal|portal_frame|portal_stabilizer|target_device|advanced_target_device|basic_target_cell|advanced_target_cell)",
        r"nuclearscience:teleporter",
        r"rftoolsutility:(matter_transmitter|matter_receiver|dialing_device|simple_dialer|destination_analyzer|matter_booster|charged_porter.*|advanced_charged_porter.*)",
        r"waystones:(.*waystone|sharestone|scoped_sharestone|portstone|warp_plate|warp_stone|return_scroll|bound_scroll|warp_scroll.*)",
        r"travelanchors:.*", r"enderio:(travel_anchor|painted_travel_anchor|staff_of_travelling)",
        r"gobber2:gobber2_ring_(teleport|blink|return)", r"powersuits:blink_drive",
        r"minecraft:ender_pearl"]),
    (P, "Dimensions and teleports", "Player-built BSP portal (admins bypass)", [
        r"bsp_teleport:(portal_core|portal_frame|portal_field)"]),

    # ------------------------------------------------------------ chunk loaders
    (P, "Chunk loaders", "Keeps chunks loaded", [
        r"thermal:chunk_loader", r"nuclearscience:chunkloader", r"mekanism:dimensional_stabilizer",
        r"ae2:spatial_anchor", r"railcraft:(world_spike|personal_world_spike|world_spike_minecart)",
        r"compactmachines:chunkloader_upgrade"]),

    # ------------------------------------------------------------ mining
    (P, "Mining and block breaking", "Quarry or miner", [
        r"electrodynamics:(quarry|seismicmarker|seismicrelay|seismicscanner)",
        r"mekanism:(digital_miner|seismic_vibrator)", r"industrialforegoing:(laser_drill|fluid_laser_base)",
        r"rftoolsbuilder:(builder|space_chamber|space_chamber_controller|shape_card_quarry.*|shape_card_pump.*|shape_card_void)",
        r"railcraft:tunnel_bore", r"pneumaticcraft:(drone|harvesting_drone|programmable_controller|gas_lift)",
        r"xycraft_machines:extractor"]),
    (P, "Mining and block breaking", "Breaks blocks automatically", [
        r"ae2:annihilation_plane", r"refinedstorage:destructor", r"industrialforegoing:block_breaker",
        r"assemblyline:blockbreaker", r"integratedtunnels:(part_importer_world_block|part_player_simulator)",
        r"mffs:disintegration_module", r"create:(mechanical_drill|mechanical_saw|mechanical_harvester|mechanical_plough)",
        r"ae2:entropy_manipulator"]),

    # ------------------------------------------------------------ resource production
    (P, "Resource generators", "Makes resources from nothing", [
        r"thermal:(device_rock_gen|device_fisher|device_hive_extractor|device_tree_extractor|device_soil_infuser|device_xp_condenser|device_composter)",
        r"industrialforegoing:(material_stonework_factory|lava_fabricator|fluid_extractor|marine_fisher|spores_recreator|sewage_composter|sludge_refiner|fluid_sieving_machine)",
        r"ae2:(condenser|growth_accelerator)", r"expatternprovider:infinity_cell",
        r"botania:(floating_)?(orechid|orechid_ignem|marimorphosis|clayconia|loonium)(_chibi)?",
        r"botania:(alchemy_catalyst|conjuration_catalyst)", r"gobber2:gobber2_lucky_block.*",
        r"nuclearscience:(atomicassembler|particleinjector)", r"mekanism:antiprotonic_nucleosynthesizer"]),
    (P, "Resource generators", "Coins earn interest (accumulation)", [r"bsp_core:coin_vault"]),
    (P, "Farms", "Crop or tree farm", [
        r"industrialforegoing:(plant_.*|hydroponic_bed|simulated_hydroponic_bed|hydroponic_simulation_processor)",
        r"thermal:machine_insolator", r"assemblyline:farmer", r"xycraft_machines:planter",
        r"sliceanddice:sprinkler", r"botania:(floating_)?(agricarnation|jaded_amaranthus)(_chibi)?"]),
    (P, "Farms", "Mob farm, spawner or mob drops", [
        r"minecraft:spawner", r"apotheosis:(spawner|boss_spawner|boss_summoner)",
        r"industrialforegoing:(animal_.*|mob_crusher|mob_duplicator|mob_slaughter_factory|wither_builder)",
        r"enderio:powered_spawner", r"rftoolsutility:(spawner|matter_beamer|syringe.*)",
        r"hostilenetworks:(sim_chamber|loot_fabricator|deep_learner)",
        r"mob_grinding_utils:(entity_spawner|saw|fan|absorption_hopper.*|xpsolidifier|xp_tap|golden_egg|rotten_egg|gm_chicken_feed.*|nutritious_chicken_feed|dreadful_dirt|delightful_dirt)",
        r"assemblyline:(mobgrinder|rancher)", r"botania:(spawner_mover|spawner_claw|gaia_pylon)",
        r"pneumaticcraft:(spawner_agitator|spawner_extractor|pressurized_spawner|empty_spawner|spawner_core|vacuum_trap)"]),

    # ------------------------------------------------------------ processing / factories
    (P, "Factories and processing", "Ore processing or doubling", [
        r"mekanism:(basic|advanced|elite|ultimate)_[a-z]+_factory",
        r"mekanism:(enrichment_chamber|crusher|energized_smelter|osmium_compressor|combiner|metallurgic_infuser|purification_chamber|chemical_injection_chamber|chemical_dissolution_chamber|chemical_washer|chemical_crystallizer|precision_sawmill)",
        r"thermal:machine_(furnace|sawmill|pulverizer|smelter|centrifuge|press|crucible|chiller|refinery|pyrolyzer|bottler|brewer|crystallizer)",
        r"enderio:(alloy_smelter|primitive_alloy_smelter|sag_mill|slice_and_splice|soul_binder)",
        r"electrodynamics:(electricfurnace.*|electricarcfurnace.*|mineralcrusher.*|mineralgrinder.*|mineralwasher|chemicalmixer|chemicalcrystallizer|energizedalloyer|reinforcedalloyer|oxidationfurnace|wiremill.*|lathe)",
        r"create:(millstone|crushing_wheel|crushing_wheel_controller|mechanical_press|mechanical_mixer|encased_fan|deployer|spout)",
        r"create_connected:fan_.*_catalyst", r"createaddition:rolling_mill", r"sliceanddice:slicer",
        r"tconstruct:(smeltery_controller|foundry_controller|seared_melter|scorched_alloyer)",
        r"railcraft:(coke_oven_bricks|blast_furnace_bricks|steam_oven|crusher|powered_rolling_machine)",
        r"ironfurnaces:(.*_furnace|million_furnace|augment_.*|heater|item_heater)",
        r"industrialforegoing:(resourceful_furnace|washing_factory|dissolution_chamber|fermentation_station|froster|hydrator|potion_brewer)",
        r"botania:terra_plate"]),
    (P, "Factories and processing", "Chemical and material processing", [
        r"mekanism:(chemical_oxidizer|chemical_infuser|pressurized_reaction_chamber|isotopic_centrifuge|electrolytic_separator|rotary_condensentrator|nutritional_liquifier|pigment_extractor|pigment_mixer|solar_neutron_activator|thermal_evaporation_(block|controller|valve)|sps_casing|sps_port)",
        r"electrodynamics:(electrolyticseparator|fermentationplant)",
        r"nuclearscience:(chemicalextractor|gascentrifuge|msrfuelpreprocessor|fuelreprocessor|nuclearboiler)",
        r"nuclear_bombs:(gas_centrifuge|isotopic_sepereator|chemical_proc_chamer|c_hem_compress|micro_reactor_block)",
        r"deepresonance:(smelter|purifier|crystallizer|laser|liquid_injector)",
        r"pneumaticcraft:(assembly_controller|assembly_platform|assembly_drill|assembly_laser|assembly_io_unit.*|pressure_chamber_valve|pressure_chamber_interface|refinery|refinery_output|thermopneumatic_processing_plant|etching_tank|uv_light_box|fluid_mixer)",
        r"ae2:(inscriber|charger)", r"expatternprovider:(ex_inscriber|ex_charger|circuit_cutter|caner)",
        r"advanced_ae:reaction_chamber", r"powah:energizing_(orb|rod.*)", r"rftoolsbase:machine_infuser",
        r"industrialforegoing:(bioreactor|protein_reactor)"]),
    (P, "Factories and processing", "Enchanting or XP production", [
        r"industrialforegoing:enchantment_(extractor|factory|applicator|sorter)", r"enderio:enchanter",
        r"apotheosis:salvaging_table", r"industrialforegoing:villager_trade_exchanger"]),
    (P, "Factories and processing", "Laser mining and drill chain", [
        r"mekanism:(laser|laser_amplifier|laser_tractor_beam)"]),
    (P, "Factories and processing", "BSP-Core production multiblock", [
        r"bsp_core:(illyrium_crucible|illyrium_refinery|tetrium_crucible|magnetic_centrifuge|shatter_coin_factory|factory_press|combination_forge)"]),
    (P, "Factories and processing", "Low-level factory block", [
        r"assemblyline:autocrafter", r"xycraft_machines:(fabricator|nitrogen_extractor)"]),

    # ------------------------------------------------------------ autocrafting
    (P, "Autocrafting", "Autocrafting (patterns, crafters, CPUs)", [
        r"ae2:(pattern_provider|cable_pattern_provider|molecular_assembler|crafting_unit|crafting_accelerator|[0-9]+k_crafting_storage|crafting_monitor|pattern_encoding_terminal|pattern_access_terminal)",
        r"ae2wtlib:wireless_pattern_(access|encoding)_terminal",
        r"advanced_ae:(adv_pattern_.*|small_adv_pattern_.*|quantum_(core|unit|storage_128|storage_256|accelerator|multi_threader|structure|crafter|crafter_terminal)|wireless_quantum_crafter_terminal|data_entangler)",
        r"expatternprovider:(ex_pattern_provider.*|ex_molecular_assembler|assembler_matrix_.*|pattern_provider_upgrade|ex_pattern_access_part|wireless_ex_pat|pattern_terminal_upgrade)",
        r"megacells:([0-9]+m_crafting_storage|mega_crafting_.*|mega_pattern_provider|cable_mega_pattern_provider)",
        r"merequester:.*", r"rsrequestify:.*",
        r"refinedstorage:([a-z_]+_)?crafter(_manager)?", r"refinedstorage:([a-z_]+_)?pattern_grid",
        r"refinedstorage:(crafting_monitor|wireless_crafting_monitor.*|creative_wireless_crafting_monitor)",
        r"extrastorage:(iron|gold|diamond|netherite)_crafter", r"integratedcrafting:.*",
        r"create:(mechanical_crafter|factory_gauge)", r"mekanism:formulaic_assemblicator",
        r"thermal:machine_crafter", r"enderio:crafter", r"rftoolsutility:crafter[123]",
        r"rftoolsstorage:crafting_manager", r"rftoolscontrol:craftingstation", r"botania:crafty_crate"]),

    # ------------------------------------------------------------ network size
    (P, "Network size", "Dense ME cable or conduit (32 channels)", [
        r"ae2:.*dense_cable.*", r"enderio:dense_me_conduit"]),
    (P, "Network size", "Multiplies channels or links networks far apart", [
        r"ae2:(me_p2p_tunnel|quantum_ring|quantum_link|wireless_booster|spatial_io_port|spatial_pylon)",
        r"expatternprovider:(wireless_connect|wireless_hub)",
        r"refinedstorage:(network_transmitter|network_receiver|network_card)"]),
    (P, "Network size", "Bigger drive than the base one", [
        r"expatternprovider:(ex_drive|drive_upgrade)"]),
    (P, "Network size", "RS speed tiers (factory-scale import/export)", [r"cabletiers:.*"]),
    (P, "Network size", "Storage block above 64k", [
        r"refinedstorage:(256k|1024k|4096k)_fluid_storage_block", r"refinedstorage:creative_(fluid_)?storage_block",
        r"extrastorage:block_.*"]),
    (P, "Power", "Large generator or reactor", [
        r"bigreactors:(basic|reinforced)_(reactor|turbine).*", r"bigreactors:(fluidizer|reprocessor).*",
        r"mekanismgenerators:(fission_.*|fusion_.*|turbine_.*|rotational_complex|saturating_condenser|electromagnetic_coil|reactor_glass|laser_focus_matrix|gas_burning_generator)",
        r"mekanism:(boiler_casing|boiler_valve|superheating_element|pressure_disperser)",
        r"nuclearscience:(fissionreactorcore|msreactorcore|fusionreactorcore|turbine|fissioncontrolrod|mscontrolrod|fissioninterface|msinterface|fusioninterface|heatexchanger)",
        r"powah:reactor_.*", r"deepresonance:(generator_part|generator_controller|energy_collector)"]),
    (P, "Power", "High-tier generator (above mid tier)", [
        r"powah:(solar_panel|furnator|magmator|thermo_generator)_(blazing|niotic|spirited|nitro)"]),
    (P, "Power", "Wireless power across worlds", [r"fluxnetworks:.*"]),

]

# Not in the scan: Solar Flux panels are made from config at load.
EXTRA = {
    "solarflux:sp_6": (P, "Power", "High-tier generator (above mid tier)"),
    "solarflux:sp_7": (P, "Power", "High-tier generator (above mid tier)"),
    "solarflux:sp_8": (P, "Power", "High-tier generator (above mid tier)"),
}
