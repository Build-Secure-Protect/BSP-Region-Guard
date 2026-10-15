package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Wiping every claim of one world (owner decision D23). Two steps: the first reports what would go and hands out a
 * one-time code; the second, within a minute and with that code, writes a backup file and removes the claims. Only
 * BSP Core admins may do it; every admin online is told. {@link #restore} puts a backup back.
 */
public final class ClaimWipe {
    private static final long CODE_TTL_MS = 60_000;
    private static final String FOLDER = "bsp_region_guard";

    private record Pending(String code, String dimension, long expires) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private ClaimWipe() {
    }

    /** The claims that a wipe of this world would remove. */
    public static List<Region> claimsIn(ServerLevel level) {
        List<Region> out = new ArrayList<>();
        for (Region r : RegionStore.get(level).all()) {
            if (r.type() == RegionType.CLAIM) {
                out.add(r);
            }
        }
        return out;
    }

    /** What the admin screen shows about a wipe (D45): claims and owners in the world, the code waiting for this admin, the backups. */
    public record State(int claims, int owners, String code, long secondsLeft, List<String> backups) {
    }

    public static State state(ServerPlayer admin, ServerLevel level) {
        List<Region> claims = claimsIn(level);
        int owners = (int) claims.stream().flatMap(r -> r.owners().stream()).distinct().count();
        Pending p = PENDING.get(admin.getUUID());
        boolean live = p != null && p.dimension().equals(level.dimension().location().toString()) && System.currentTimeMillis() < p.expires();
        return new State(claims.size(), owners, live ? p.code() : "", live ? (p.expires() - System.currentTimeMillis() + 999) / 1000 : 0, backups(level.getServer()));
    }

    /** Step one: the summary and a code. Returns the message to show. */
    public static String request(ServerPlayer admin, ServerLevel level) {
        if (!BspCore.isAdmin(admin)) {
            return "Only BSP Core admins can wipe claims.";
        }
        List<Region> claims = claimsIn(level);
        if (claims.isEmpty()) {
            return "There are no claims in " + level.dimension().location() + ".";
        }
        int owners = (int) claims.stream().flatMap(r -> r.owners().stream()).distinct().count();
        String code = String.format("%04d", (int) (Math.random() * 10000));
        PENDING.put(admin.getUUID(), new Pending(code, level.dimension().location().toString(), System.currentTimeMillis() + CODE_TTL_MS));
        return "WIPE CLAIMS in " + level.dimension().location() + ": this removes " + claims.size() + " claim" + (claims.size() == 1 ? "" : "s")
                + " of " + owners + " player" + (owners == 1 ? "" : "s") + " in THIS world only. Other worlds and other servers are not touched. "
                + "A backup is written first. To go ahead, within 60 seconds, run: /rg claims wipe " + code;
    }

    /** Step two. Returns the message to show. */
    public static String confirm(ServerPlayer admin, ServerLevel level, String code) {
        if (!BspCore.isAdmin(admin)) {
            return "Only BSP Core admins can wipe claims.";
        }
        Pending p = PENDING.get(admin.getUUID());
        if (p == null || System.currentTimeMillis() > p.expires()) {
            PENDING.remove(admin.getUUID());
            return "No wipe is waiting for a code, or the code expired. Run /rg claims wipe first.";
        }
        if (!p.dimension().equals(level.dimension().location().toString())) {
            return "That code was for " + p.dimension() + ". Stand in that world, or run /rg claims wipe again here.";
        }
        if (!p.code().equals(code.trim())) {
            return "Wrong code. Nothing was wiped.";
        }
        PENDING.remove(admin.getUUID());
        List<Region> claims = claimsIn(level);
        String file;
        try {
            file = backup(level, claims);
        } catch (IOException e) {
            BSPRegionGuard.LOGGER.error("Claim wipe aborted: could not write the backup", e);
            return "Could not write the backup file (" + e.getMessage() + "). Nothing was wiped.";
        }
        RegionStore store = RegionStore.get(level);
        for (Region r : claims) {
            store.remove(r.id());
        }
        String note = admin.getGameProfile().getName() + " wiped " + claims.size() + " claims in " + level.dimension().location() + " (backup " + file + ")";
        BSPRegionGuard.LOGGER.warn(note);
        for (ServerPlayer other : admin.server.getPlayerList().getPlayers()) {
            if (other != admin && BspCore.isAdmin(other)) {
                other.sendSystemMessage(Component.literal("[Region Guard] " + note));
            }
        }
        return "Wiped " + claims.size() + " claims in " + level.dimension().location() + ". Backup: " + file + " (restore with /rg claims restore " + file + ").";
    }

    private static Path folder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
    }

    /** Writes the claims to {@code <world>/bsp_region_guard/claims-<dimension>-<time>.nbt}; returns the file name. */
    static String backup(ServerLevel level, List<Region> claims) throws IOException {
        Path dir = folder(level.getServer());
        Files.createDirectories(dir);
        String name = "claims-" + level.dimension().location().getPath() + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".nbt";
        CompoundTag root = new CompoundTag();
        root.putString("dimension", level.dimension().location().toString());
        root.putLong("time", System.currentTimeMillis());
        ListTag list = new ListTag();
        for (Region r : claims) {
            list.add(r.save());
        }
        root.put("claims", list);
        NbtIo.writeCompressed(root, dir.resolve(name).toFile());
        return name;
    }

    /** Backup files that exist, newest first. */
    public static List<String> backups(MinecraftServer server) {
        Path dir = folder(server);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("claims-") && n.endsWith(".nbt")).sorted((a, b) -> b.compareTo(a)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Puts the claims of a backup back into the world they came from. Claims whose id now exists are skipped. */
    public static String restore(ServerPlayer admin, String fileName) {
        if (!BspCore.isAdmin(admin)) {
            return "Only BSP Core admins can restore claims.";
        }
        if (fileName.contains("/") || fileName.contains("\\") || !fileName.endsWith(".nbt")) {
            return "Give a file name from /rg claims backups.";
        }
        Path file = folder(admin.server).resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return "No backup called " + fileName + ".";
        }
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file.toFile());
        } catch (IOException e) {
            return "Could not read " + fileName + ": " + e.getMessage();
        }
        ServerLevel level = levelFor(admin.server, root.getString("dimension"));
        if (level == null) {
            return "The world " + root.getString("dimension") + " is not loaded.";
        }
        RegionStore store = RegionStore.get(level);
        int restored = 0, skipped = 0;
        for (Tag t : root.getList("claims", Tag.TAG_COMPOUND)) {
            Region r = Region.load((CompoundTag) t);
            if (store.has(r.id())) {
                skipped++;
            } else {
                store.add(r);
                restored++;
            }
        }
        BSPRegionGuard.LOGGER.warn("{} restored {} claims into {} from {} ({} skipped)", admin.getGameProfile().getName(), restored, level.dimension().location(), fileName, skipped);
        return "Restored " + restored + " claim" + (restored == 1 ? "" : "s") + " into " + level.dimension().location() + (skipped > 0 ? ", skipped " + skipped + " whose id already exists." : ".");
    }

    @Nullable
    private static ServerLevel levelFor(MinecraftServer server, String dimension) {
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().location().toString().equals(dimension)) {
                return l;
            }
        }
        return null;
    }
}
