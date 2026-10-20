package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Moving, copying, backing up and restoring the blocks of a plot (owner decisions D67 to D70).
 *
 * <p>Blocks are handled in sections of 16 x 16 x 16 as structure templates, so block entities (chest contents, signs)
 * travel with them and turned plots turn their blocks; non-player entities are saved and placed separately. The work
 * runs a little each server tick against a time budget, so a big plot does not stall the server, and the admin sees a
 * progress line. Before anything is overwritten both areas are written to backup files; the spot a moved plot leaves
 * is rebuilt as natural ground from the world's generator (its base shape and the biome's surface, not trees or
 * ores, D68). Blocks are placed without neighbour updates and the box's faces are updated once at the end.
 */
public final class PlotBlocks {
    public static final String FOLDER = "bsp_region_guard/backups/plots";
    private static final int SECTION = 16;
    /** Milliseconds of each server tick the block work may use. */
    private static final long BUDGET_NS = 15_000_000L;
    private static final int QUIET = 2 | 16; // tell clients, keep neighbours' shapes as they are

    public enum Kind { MOVE, COPY, BACKUP, RESTORE }

    private enum Phase { CAPTURE_FROM, CAPTURE_TO, WRITE, CLEAR, PLACE, ENTITIES, EDGES, DONE }

    /** One running job. {@code from} is read; {@code to} is written (null for a backup); rotation in quarter turns clockwise. */
    static final class Job {
        final Kind kind;
        final ServerLevel level;
        final UUID admin;
        final String plot;
        final Cuboid from;
        @Nullable
        final Cuboid to;
        final int rotation;
        final List<int[]> offsets = new ArrayList<>();
        final ListTag fromSections = new ListTag(), toSections = new ListTag(), fromEntities = new ListTag(), toEntities = new ListTag();
        final List<Object[]> riders = new ArrayList<>(); // players inside: {uuid, rel x, y, z}
        Phase phase;
        int index;
        String fromFile = "", toFile = "";
        @Nullable
        Runnable then;
        int lastPercent = -1;

        Job(Kind kind, ServerLevel level, UUID admin, String plot, Cuboid from, @Nullable Cuboid to, int rotation) {
            this.kind = kind;
            this.level = level;
            this.admin = admin;
            this.plot = plot;
            this.from = from;
            this.to = to;
            this.rotation = Math.floorMod(rotation, 4);
            for (int dy = 0; dy < from.sizeY(); dy += SECTION) {
                for (int dz = 0; dz < from.sizeZ(); dz += SECTION) {
                    for (int dx = 0; dx < from.sizeX(); dx += SECTION) {
                        offsets.add(new int[]{dx, dy, dz});
                    }
                }
            }
            this.phase = kind == Kind.RESTORE ? Phase.CLEAR : Phase.CAPTURE_FROM;
        }
    }

    private static final ArrayDeque<Job> JOBS = new ArrayDeque<>();

    private PlotBlocks() {
    }

    // ------------------------------------------------------------------ starting work

    /** True while a job is working on this plot (actions on it wait until it is done). */
    public static boolean busy(String plot) {
        return JOBS.stream().anyMatch(j -> j.plot.equals(plot));
    }

    /** Moves (or copies) the blocks of {@code from} to {@code to}, turned {@code rotation} quarter turns; {@code then} runs when done. */
    public static Job transfer(ServerPlayer admin, String plot, Cuboid from, Cuboid to, int rotation, boolean move, @Nullable Runnable then) {
        Job j = new Job(move ? Kind.MOVE : Kind.COPY, admin.serverLevel(), admin.getUUID(), plot, from, to, rotation);
        j.then = then;
        if (move) {
            for (Player p : admin.serverLevel().players()) {
                if (from.contains(p.getX(), p.getY(), p.getZ())) {
                    j.riders.add(new Object[]{p.getUUID(), p.getX() - from.minX(), p.getY() - from.minY(), p.getZ() - from.minZ()});
                }
            }
        }
        JOBS.add(j);
        return j;
    }

    /** Writes a backup of a plot's blocks as they are now. */
    public static Job backup(ServerPlayer admin, String plot, Cuboid box) {
        Job j = new Job(Kind.BACKUP, admin.serverLevel(), admin.getUUID(), plot, box, null, 0);
        JOBS.add(j);
        return j;
    }

    /** Puts a backup file's blocks back where they were taken. Returns null and an error message through {@code error} when unreadable. */
    @Nullable
    public static Job restore(ServerPlayer admin, String file, @Nullable Runnable then, java.util.function.Consumer<String> error) {
        ServerLevel level = admin.serverLevel();
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(folder(level.getServer()).resolve(file).toFile());
        } catch (IOException | RuntimeException e) {
            error.accept("Can't read the backup " + file + ": " + e.getMessage());
            return null;
        }
        if (!root.getString("dimension").equals(level.dimension().location().toString())) {
            error.accept("That backup is of " + root.getString("dimension") + "; go there to restore it.");
            return null;
        }
        Cuboid box = Cuboid.load(root.getCompound("box"));
        Job j = new Job(Kind.RESTORE, level, admin.getUUID(), root.getString("plot"), box, box, 0);
        j.fromSections.addAll(root.getList("sections", Tag.TAG_COMPOUND));
        j.fromEntities.addAll(root.getList("entities", Tag.TAG_COMPOUND));
        j.then = then;
        JOBS.add(j);
        return j;
    }

    // ------------------------------------------------------------------ the tick

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            work(BUDGET_NS);
        }
    }

    /** Runs jobs until the time budget is spent. For tests, a large budget finishes them. */
    public static void work(long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        while (!JOBS.isEmpty() && System.nanoTime() < end) {
            Job j = JOBS.peek();
            try {
                step(j);
            } catch (RuntimeException e) {
                BSPRegionGuard.LOGGER.error("Plot block job for {} failed", j.plot, e);
                tell(j, "Moving the blocks of " + j.plot + " failed: " + e.getMessage(), false);
                j.phase = Phase.DONE;
            }
            if (j.phase == Phase.DONE) {
                JOBS.poll();
                if (j.then != null) {
                    j.then.run();
                }
            } else {
                progress(j);
            }
        }
    }

    /** For tests: finishes every job now. */
    public static void finishAll() {
        while (!JOBS.isEmpty()) {
            work(Long.MAX_VALUE / 4);
        }
    }

    private static void step(Job j) {
        switch (j.phase) {
            case CAPTURE_FROM -> {
                if (j.index < j.offsets.size()) {
                    j.fromSections.add(capture(j.level, j.from, j.offsets.get(j.index++)));
                    return;
                }
                captureEntities(j.level, j.from, j.fromEntities);
                j.index = 0;
                j.phase = j.to == null ? Phase.WRITE : Phase.CAPTURE_TO;
            }
            case CAPTURE_TO -> {
                List<int[]> toOffsets = sections(j.to);
                if (j.index < toOffsets.size()) {
                    j.toSections.add(capture(j.level, j.to, toOffsets.get(j.index++)));
                    return;
                }
                captureEntities(j.level, j.to, j.toEntities);
                j.index = 0;
                j.phase = Phase.WRITE;
            }
            case WRITE -> {
                String when = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                if (j.kind != Kind.COPY) { // a copy leaves its source alone: only the destination needs a backup
                    j.fromFile = write(j.level, j.plot, when, j.kind == Kind.BACKUP ? "backup" : "before-from", j.from, j.fromSections, j.fromEntities);
                }
                if (j.to != null) {
                    j.toFile = write(j.level, j.plot, when, "before-to", j.to, j.toSections, j.toEntities);
                }
                j.phase = j.kind == Kind.BACKUP ? Phase.DONE : j.kind == Kind.MOVE ? Phase.CLEAR : Phase.PLACE;
                if (j.kind == Kind.BACKUP) {
                    tell(j, "Backup of " + j.plot + " saved: " + j.fromFile, true);
                }
            }
            case CLEAR -> {
                if (j.kind == Kind.RESTORE) {
                    removeEntities(j.level, j.to); // the box's current mobs and frames give way to the saved ones
                    j.phase = Phase.PLACE;
                    return;
                }
                int columns = j.from.sizeX() * j.from.sizeZ();
                if (j.index == 0) {
                    removeEntities(j.level, j.from);
                }
                for (int n = 0; n < 8 && j.index < columns; n++, j.index++) {
                    rebuildColumn(j.level, j.from, j.from.minX() + j.index % j.from.sizeX(), j.from.minZ() + j.index / j.from.sizeX());
                }
                if (j.index >= columns) {
                    j.index = 0;
                    j.phase = Phase.PLACE;
                }
            }
            case PLACE -> {
                if (j.index < j.fromSections.size()) {
                    place(j.level, j.from, j.to, j.rotation, j.fromSections.getCompound(j.index++));
                    return;
                }
                j.index = 0;
                j.phase = Phase.ENTITIES;
            }
            case ENTITIES -> {
                placeEntities(j.level, j.from, j.to, j.rotation, j.fromEntities);
                for (Object[] r : j.riders) {
                    Player p = j.level.getPlayerByUUID((UUID) r[0]);
                    if (p instanceof ServerPlayer sp) {
                        Vec3 at = turn(j.to, j.from, j.rotation, (double) r[1], (double) r[2], (double) r[3]);
                        sp.teleportTo(j.level, at.x, at.y, at.z, sp.getYRot(), sp.getXRot());
                    }
                }
                j.phase = Phase.EDGES;
            }
            case EDGES -> {
                updateFaces(j.level, j.to);
                if (j.kind == Kind.MOVE) {
                    updateFaces(j.level, j.from);
                }
                tell(j, switch (j.kind) {
                    case MOVE -> "The blocks of " + j.plot + " are moved.";
                    case COPY -> "The blocks of " + j.plot + " are copied.";
                    default -> "The backup of " + j.plot + " is restored.";
                }, true);
                j.phase = Phase.DONE;
            }
            default -> j.phase = Phase.DONE;
        }
    }

    private static List<int[]> sections(Cuboid box) {
        List<int[]> out = new ArrayList<>();
        for (int dy = 0; dy < box.sizeY(); dy += SECTION) {
            for (int dz = 0; dz < box.sizeZ(); dz += SECTION) {
                for (int dx = 0; dx < box.sizeX(); dx += SECTION) {
                    out.add(new int[]{dx, dy, dz});
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ blocks

    private static CompoundTag capture(ServerLevel level, Cuboid box, int[] off) {
        StructureTemplate t = new StructureTemplate();
        BlockPos at = new BlockPos(box.minX() + off[0], box.minY() + off[1], box.minZ() + off[2]);
        Vec3i size = new Vec3i(Math.min(SECTION, box.sizeX() - off[0]), Math.min(SECTION, box.sizeY() - off[1]), Math.min(SECTION, box.sizeZ() - off[2]));
        t.fillFromWorld(level, at, size, false, Blocks.STRUCTURE_VOID);
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("off", off);
        tag.put("t", t.save(new CompoundTag()));
        return tag;
    }

    /**
     * Places one captured section into {@code to}, turned. A block at (x, z) from the old box's corner lands at
     * (x, z), (d-1-z, x), (w-1-x, d-1-z) or (z, w-1-x) from the new corner for 0 to 3 quarter turns.
     */
    private static void place(ServerLevel level, Cuboid from, Cuboid to, int rotation, CompoundTag tag) {
        int[] off = tag.getIntArray("off");
        StructureTemplate t = new StructureTemplate();
        t.load(level.holderLookup(Registries.BLOCK), tag.getCompound("t"));
        int w = from.sizeX(), d = from.sizeZ();
        int px, pz;
        switch (rotation) {
            case 1 -> { px = d - 1 - off[2]; pz = off[0]; }
            case 2 -> { px = w - 1 - off[0]; pz = d - 1 - off[2]; }
            case 3 -> { px = off[2]; pz = w - 1 - off[0]; }
            default -> { px = off[0]; pz = off[2]; }
        }
        BlockPos at = new BlockPos(to.minX() + px, to.minY() + off[1], to.minZ() + pz);
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(ROTATIONS[rotation]).setRotationPivot(BlockPos.ZERO)
                .setIgnoreEntities(true).setKnownShape(true);
        t.placeInWorld(level, at, at, settings, level.random, QUIET);
    }

    private static final Rotation[] ROTATIONS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};

    /** Where a point (relative to the old box's corner) lands in the new box after the turn. */
    private static Vec3 turn(Cuboid to, Cuboid from, int rotation, double rx, double ry, double rz) {
        double w = from.sizeX(), d = from.sizeZ();
        double nx, nz;
        switch (rotation) {
            case 1 -> { nx = d - rz; nz = rx; }
            case 2 -> { nx = w - rx; nz = d - rz; }
            case 3 -> { nx = rz; nz = w - rx; }
            default -> { nx = rx; nz = rz; }
        }
        return new Vec3(to.minX() + nx, to.minY() + ry, to.minZ() + nz);
    }

    /**
     * The natural ground for one column (D68): the generator's base shape (stone, water, lava, caves), the biome's
     * surface on top (grass and dirt; sand on beaches, deserts and under water), deepslate below y 0, bedrock at the
     * bottom. Containers are emptied first so nothing spills.
     */
    private static void rebuildColumn(ServerLevel level, Cuboid box, int x, int z) {
        var source = level.getChunkSource();
        NoiseColumn column = source.getGenerator().getBaseColumn(x, z, level, source.randomState());
        int top = level.getMaxBuildHeight() - 1;
        while (top > level.getMinBuildHeight() && !solid(column.getBlock(top))) {
            top--;
        }
        boolean underWater = !column.getBlock(top + 1).getFluidState().isEmpty();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Holder<Biome> biome = level.getBiome(pos.set(x, top, z));
        boolean sandy = underWater || biome.is(BiomeTags.IS_BEACH) || biome.is(Biomes.DESERT);
        for (int y = box.minY(); y <= box.maxY(); y++) {
            pos.set(x, y, z);
            BlockState state = column.getBlock(y);
            if (y <= level.getMinBuildHeight()) {
                state = Blocks.BEDROCK.defaultBlockState();
            } else if (solid(state)) {
                int depth = top - y;
                if (depth == 0) {
                    state = sandy ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                } else if (depth <= 3) {
                    state = sandy ? Blocks.SAND.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                } else if (y < 0 && state.is(Blocks.STONE)) {
                    state = Blocks.DEEPSLATE.defaultBlockState();
                }
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                Clearable.tryClear(be);
            }
            level.setBlock(pos, state, QUIET);
        }
    }

    private static boolean solid(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty();
    }

    private static void updateFaces(ServerLevel level, Cuboid box) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                boolean edge = x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ();
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    if (edge || y == box.minY() || y == box.maxY()) {
                        pos.set(x, y, z);
                        level.getBlockState(pos).updateNeighbourShapes(level, pos, 3);
                        level.blockUpdated(pos, level.getBlockState(pos).getBlock());
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ entities

    private static AABB aabb(Cuboid b) {
        return new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
    }

    private static void captureEntities(ServerLevel level, Cuboid box, ListTag out) {
        for (Entity e : level.getEntities((Entity) null, aabb(box), e -> !(e instanceof Player) && !e.isPassenger())) {
            CompoundTag tag = new CompoundTag();
            if (e.save(tag)) {
                CompoundTag item = new CompoundTag();
                item.putDouble("x", e.getX() - box.minX());
                item.putDouble("y", e.getY() - box.minY());
                item.putDouble("z", e.getZ() - box.minZ());
                item.put("e", tag);
                out.add(item);
            }
        }
    }

    private static void removeEntities(ServerLevel level, Cuboid box) {
        for (Entity e : level.getEntities((Entity) null, aabb(box), e -> !(e instanceof Player))) {
            e.discard();
        }
    }

    private static void placeEntities(ServerLevel level, Cuboid from, Cuboid to, int rotation, ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            CompoundTag tag = item.getCompound("e").copy();
            tag.remove("UUID"); // a fresh one: the copy must not clash with the original
            EntityType.create(tag, level).ifPresent(e -> {
                Vec3 at = turn(to, from, rotation, item.getDouble("x"), item.getDouble("y"), item.getDouble("z"));
                float yaw = e.rotate(ROTATIONS[rotation]);
                e.moveTo(at.x, at.y, at.z, yaw, e.getXRot());
                level.addFreshEntity(e);
            });
        }
    }

    // ------------------------------------------------------------------ backup files

    static Path folder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
    }

    private static String write(ServerLevel level, String plot, String when, String what, Cuboid box, ListTag sections, ListTag entities) {
        String name = plot + "__" + when + "__" + what + ".nbt";
        CompoundTag root = new CompoundTag();
        root.putString("plot", plot);
        root.putString("dimension", level.dimension().location().toString());
        root.put("box", box.save());
        root.put("sections", sections.copy());
        root.put("entities", entities.copy());
        Path dir = folder(level.getServer());
        Util.ioPool().execute(() -> {
            try {
                Files.createDirectories(dir);
                NbtIo.writeCompressed(root, dir.resolve(name).toFile());
            } catch (IOException e) {
                BSPRegionGuard.LOGGER.error("Could not write the plot backup {}", name, e);
            }
        });
        return name;
    }

    /** A plot's backup files, newest first. */
    public static List<String> backups(MinecraftServer server, String plot) {
        Path dir = folder(server);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(plot + "__") && n.endsWith(".nbt"))
                    .sorted(java.util.Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ telling the admin

    private static void progress(Job j) {
        int total = j.offsets.size() * (j.kind == Kind.BACKUP ? 1 : 2) + (j.kind == Kind.MOVE ? 1 : 0);
        int done = switch (j.phase) {
            case CAPTURE_FROM -> j.index;
            case CAPTURE_TO, WRITE -> j.offsets.size();
            case CLEAR -> j.offsets.size();
            case PLACE -> j.offsets.size() + (j.kind == Kind.MOVE ? 1 : 0) + j.index;
            default -> total;
        };
        int percent = total == 0 ? 100 : Math.min(99, done * 100 / total);
        if (percent / 10 != j.lastPercent / 10) {
            j.lastPercent = percent;
            ServerPlayer admin = j.level.getServer().getPlayerList().getPlayer(j.admin);
            if (admin != null) {
                admin.displayClientMessage(Component.literal("Working on the blocks of " + j.plot + ": " + percent + "%"), true);
            }
        }
    }

    private static void tell(Job j, String message, boolean ok) {
        ServerPlayer admin = j.level.getServer().getPlayerList().getPlayer(j.admin);
        if (admin != null) {
            admin.displayClientMessage(Component.literal(message), false);
        }
    }

    /** The backup files a finished transfer wrote: before the source, before the destination. */
    public static Map<String, String> files(Job j) {
        return Map.of("from", j.fromFile, "to", j.toFile);
    }

    public static void clearAll() {
        JOBS.clear();
    }
}
