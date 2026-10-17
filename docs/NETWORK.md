# Running Region Guard across several servers

Region Guard works on a single server with no setup. On a network (the BSP network runs four machines), every backend shares regions and messages through MySQL, and Redis makes the messages fast. Both live on the main machine; the other machines reach them over the local network.

## What travels where

| What | Where | How fast |
|---|---|---|
| Regions (each server owns its own; the others read them for the map) | MySQL table `<prefix>regions` | written as they change, read on start and for the OTHER SERVERS map view |
| Messages: mine resets, region triggers, BSP Teleport's landings, other mods' `publish` calls | Redis channel `bsp_rg:<channel>` **and** MySQL table `<prefix>messages` | Redis: milliseconds. The table is read every `safetyPollSeconds` (30) while Redis is up, every `pollSeconds` (5) while it is down |

Each message has a unique id, so a server handles it once whether the Redis copy or the table copy arrives first. If Redis goes down, nothing is lost: messages arrive through the table a few seconds late, and Region Guard reconnects to Redis by itself.

Region Guard bundles no database or Redis library. MySQL is reached through the driver BSP Core's full jar ships (Forge) or Mohist's own; Redis through Region Guard's own small client. Nothing can clash with Mohist or the other BSP mods.

## Redis on the main machine

Redis is reached over a plain connection, so keep it on the local network:

1. In `redis.conf`: `bind 127.0.0.1 <the main machine's LAN address>`, keep `protected-mode yes`, and set `requirepass <a long password>` (or, with Redis 6.2 or later, an ACL user limited to Region Guard's channels: `user <name> on ><password> resetchannels &bsp_rg:* +subscribe +publish +ping`, and fill in `username` below).
2. Allow port 6379 only from the four server machines in the firewall; never forward it from the internet.
3. Restart Redis and check from another machine: `redis-cli -h <main LAN address> -a <password> ping` answers `PONG`.

## Each backend's `serverconfig/bsp_region_guard-server.toml`

```toml
[storage]
    mode = "mysql"
    host = "<main LAN address>"
    port = 3306
    database = "bsp"
    user = "bsp"
    password = "..."
    tablePrefix = "bsp_rg_"     # the same on every server of one network
    serverId = "survival-1"     # different on every server
    pollSeconds = 5
    syncMineResets = true

    [storage.redis]
        enabled = true
        host = "<main LAN address>"
        port = 6379
        username = ""            # only for an ACL user
        password = "..."
        channel = "messages"     # the same on every server of one network; a test network uses another
        safetyPollSeconds = 30
```

On the main machine itself, `host = "localhost"` works for both.

## Checking it

`/rg storage status` shows the database, how often the message table is read, and a second line for Redis: `connected`, `receiving only`, `sending only` or `down`, with counts of messages sent, received and not sent, and the last error (for example "Redis refused the login" for a wrong password). The server log says `Redis connected` once, warns once per new problem, and says `Redis connected again` after an outage.

## Server names

Every backend's `storage.serverId` must be **the same name the proxy (Velocity) uses for that server**, which is also BSP Teleport's `network.serverName`. Cross-server triggers and edge links name the destination by its `serverId`, and BSP Teleport sends the player to the proxy server of that name. Region Guard warns in the log at start when the two differ.

## Edge links between servers

With a world border on each side (`/rg border set <width>`), an edge of one server's world can lead to an edge of another server's world: walk off survival-1's east edge, arrive near survival-2's west edge. Links are made on either server and work both ways; each edge leads to at most one other edge.

- **Admin screen:** select the world default, open the BORDER tab. This world sits in the middle with its border (CHANGE sets width and centre); each side shows where that edge leads, or + LINK, which lists the other servers' worlds that have a border and a free edge (the matching edge, south for north, first). The small x unlinks.
- **Commands:** `/rg border links` (this world's edges and the other servers' worlds Region Guard knows), `/rg border link <side> <server> <world> <their side>`, `/rg border unlink <side>`.
- **Players:** 10 blocks before a linked edge they are warned as usual; within a block of the edge (standing against Minecraft's wall in the Overworld, or stepping past the edge elsewhere) a window asks whether to travel; admins with bypass on are asked too. TRAVEL hands them to BSP Teleport, which sends them through the proxy; they arrive at the same fraction along the other edge, 16 blocks inside it, on the ground open to the sky (never in a cave: if that spot is a tree, water or lava, the nearest dry ground within 12 blocks). STAY (or Esc) pushes them back, and they are not asked again until they have walked out of the warning band and come back.
- **What it needs:** shared storage on every server involved (Region Guard reads the other servers' world defaults from it, at start, every 5 minutes, whenever a border or link changes, and when the BORDER tab opens), BSP Teleport on every server, and matching server names (above). Without BSP Teleport a player who chooses TRAVEL is told travel is not available.

