# BSP Region Guard API

Other mods compile against the package `com.mrgregles.bsp_region_guard.api` only. Everything outside it may change without notice.

## Getting the API

```java
RegionGuardApi api = RegionGuardApi.get();
```

Available from `FMLCommonSetupEvent` on. `RegionGuardApi.API_VERSION` is raised whenever a method is added or changed.

## Services

None yet beyond `modVersion()`. Regions, flags, claims, mines and teleport triggers are documented here as they are built.

## Exemptions

`com.mrgregles.bsp_region_guard.region.RegionQuery.addExemption((level, pos, flag, player) -> ...)` registers a rule that lifts a region's decision for a player in a spot, the way the Shatter Totem rule does. Exemptions are asked only when a region would refuse a player; never for the world's own events. This class will move into the `api` package in Phase 9; until then treat the method as stable.

## Events (Forge bus, `com.mrgregles.bsp_region_guard.api.event`)

| Event | When | Cancel means |
|---|---|---|
| `RegionEnterEvent` | a player is about to enter a region; one event per region, with `regionId()`, `dimension()` and `byTeleport()` | the player is put back where they came from |
| `RegionLeaveEvent` | a player is about to leave a region; same fields | the player is put back inside |

Both extend `PlayerEvent`; `getEntity()` is the `ServerPlayer`. They fire from the server tick, so a listener may teleport or message the player freely.
