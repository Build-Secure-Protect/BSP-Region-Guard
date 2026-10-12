# BSP Region Guard

A Forge mod for Minecraft 1.20.1 that looks after areas of the world for the [Build Secure Protect](https://www.curseforge.com/minecraft/modpacks/build-secure-protect) modpack and server:

- **World protection**: admins draw regions and set flags (block breaking, PvP, explosions, entry by rank and many more), through an in-game screen with the regions drawn in the world.
- **Land claims**: admin-priced plots, or open areas where players outline their own plot and pay per block from their BSP Core bank (Shatter Pieces).
- **Automated mines**: regions filled with admin-chosen ores that refill on a timer, with rank-gated entry.
- **Region teleports**: with BSP Teleport, move players in or out of regions on entry, exit, timers or server shutdown.
- **Cross-server**: regions, mine resets and triggers shared across the BSP network through MySQL (Redis optional).
- **API** for the other BSP mods: see `docs/API.md`.

Requires [BSP Core](https://github.com/Build-Secure-Protect/BSP-Core). Works with [BSP Teleport](https://github.com/Build-Secure-Protect/BSP-Teleport) and LuckPerms when present.

## Building

Java 17 toolchain (downloaded automatically), Forge 47.4.10. BSP Core must be built first in the sibling folder `../BSP-Core` (see `gradle.properties`).

```
./gradlew build
```

The jar lands in `build/libs/`. Headless tests: `./gradlew runGameTestServer`. To include the LuckPerms test, prepare the dev-only LuckPerms jar as described in `tools/make_luckperms_dev_jar.py` (the `libs/` folder is not committed).

## License

MIT, see `LICENSE`.
