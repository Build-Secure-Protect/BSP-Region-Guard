package com.mrgregles.bsp_region_guard.region;

import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.flag.Flag;
import com.mrgregles.bsp_region_guard.flag.FlagRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A named area of one world with an owner list, a member list, allowed ranks and flag values.
 *
 * <p>Ids are unique within a world, lower-case, and chosen by whoever creates the region (an admin, or the claim
 * code). The {@linkplain #isGlobal() global} region has no bounds and applies to the whole world. A region with a
 * {@link #parentId() parent} takes the parent's flag values where it has none of its own.
 *
 * <p>Mutations go through the setters; whoever changes a region must call {@link RegionStore#touch(Region)} so the
 * store re-indexes it and saves.
 */
public final class Region {
    public static final String GLOBAL_ID = "__global__";
    public static final int MAX_ID_LENGTH = 32;
    /**
     * The box saved for a region that has no corners yet (D73): one block far below any world, so it contains nothing
     * and an older Region Guard reading it never takes it for the world default (which has no box at all).
     */
    public static final Cuboid NO_CORNERS = new Cuboid(0, -30000, 0, 0, -30000, 0);

    private final String id;
    private final ResourceKey<Level> dimension;
    private String name;
    @Nullable
    private Cuboid bounds;
    private int priority;
    @Nullable
    private String parentId;
    private RegionType type;
    private final Set<UUID> owners = new LinkedHashSet<>();
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<String> ranks = new LinkedHashSet<>();
    private final Map<Flag<?>, Object> flags = new LinkedHashMap<>();
    /** Flag values saved by a flag this server does not know (another mod not installed). Kept so nothing is lost. */
    private final CompoundTag unknownFlags = new CompoundTag();
    /** Type-specific data (price, cost per block, mine fill table). Owned by the feature that uses the type. */
    private CompoundTag typeData = new CompoundTag();
    private long updatedAt;
    private int version;

    public Region(String id, ResourceKey<Level> dimension, @Nullable Cuboid bounds, RegionType type) {
        this.id = validateId(id);
        this.dimension = dimension;
        this.bounds = bounds;
        this.type = type;
        this.name = id;
        this.updatedAt = System.currentTimeMillis();
    }

    public static Region global(ResourceKey<Level> dimension) {
        Region r = new Region(GLOBAL_ID, dimension, null, RegionType.GLOBAL);
        r.priority = Integer.MIN_VALUE;
        r.name = "World default";
        return r;
    }

    /** Checks an id typed by a player: 1 to 32 characters, letters, digits, '_' and '-', or the global id. */
    public static String validateId(String id) {
        if (id.equals(GLOBAL_ID)) {
            return id;
        }
        if (id.isEmpty() || id.length() > MAX_ID_LENGTH || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("region ids are 1 to " + MAX_ID_LENGTH + " letters, digits, '_' or '-'");
        }
        return id.toLowerCase();
    }

    // ------------------------------------------------------------------ identity and shape

    public String id() {
        return id;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isGlobal() {
        return bounds == null;
    }

    /** False for a region made without corners (D73), which applies nowhere until it gets some; true for the rest. */
    public boolean hasCorners() {
        return bounds == null || !bounds.equals(NO_CORNERS);
    }

    /** True for the box a region without corners carries ({@link #NO_CORNERS}); the screens use it too. */
    public static boolean noCorners(@Nullable Cuboid box) {
        return NO_CORNERS.equals(box);
    }

    /** The box, or {@code null} for the global region. */
    @Nullable
    public Cuboid bounds() {
        return bounds;
    }

    public void setBounds(Cuboid bounds) {
        if (isGlobal()) {
            throw new IllegalStateException("the global region has no bounds");
        }
        this.bounds = bounds;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean contains(int x, int y, int z) {
        return bounds == null || hasCorners() && bounds.contains(x, y, z);
    }

    public int priority() {
        return priority;
    }

    public void setPriority(int priority) {
        if (isGlobal()) {
            throw new IllegalStateException("the global region always has the lowest priority");
        }
        this.priority = priority;
    }

    @Nullable
    public String parentId() {
        return parentId;
    }

    public void setParentId(@Nullable String parentId) {
        this.parentId = parentId;
    }

    public RegionType type() {
        return type;
    }

    public void setType(RegionType type) {
        this.type = type;
    }

    // ------------------------------------------------------------------ people

    public Set<UUID> owners() {
        return Collections.unmodifiableSet(owners);
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public Set<String> ranks() {
        return Collections.unmodifiableSet(ranks);
    }

    public boolean addOwner(UUID uuid) {
        members.remove(uuid);
        return owners.add(uuid);
    }

    public boolean removeOwner(UUID uuid) {
        return owners.remove(uuid);
    }

    public boolean addMember(UUID uuid) {
        return !owners.contains(uuid) && members.add(uuid);
    }

    public boolean removeMember(UUID uuid) {
        return members.remove(uuid);
    }

    public boolean addRank(String rank) {
        return ranks.add(rank.toLowerCase());
    }

    public boolean removeRank(String rank) {
        return ranks.remove(rank.toLowerCase());
    }

    public boolean isOwner(UUID uuid) {
        return owners.contains(uuid);
    }

    /** Owners count as members. */
    public boolean isMember(UUID uuid) {
        return owners.contains(uuid) || members.contains(uuid);
    }

    public boolean hasRank(String rank) {
        return ranks.contains(rank.toLowerCase());
    }

    // ------------------------------------------------------------------ flags

    /** The region's own value for a flag, without parent or default. */
    @Nullable
    public <T> T flag(Flag<T> flag) {
        Object value = flags.get(flag);
        return value == null ? null : flag.cast(value);
    }

    public <T> void setFlag(Flag<T> flag, @Nullable T value) {
        if (value == null) {
            flags.remove(flag);
        } else {
            flags.put(flag, value);
        }
    }

    public Map<Flag<?>, Object> flags() {
        return Collections.unmodifiableMap(flags);
    }

    public CompoundTag typeData() {
        return typeData;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public int version() {
        return version;
    }

    /** Called by the store whenever the region is saved after a change. */
    void bumpVersion() {
        version++;
        updatedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ NBT

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("dim", dimension.location().toString());
        tag.putString("name", name);
        if (bounds != null) {
            tag.put("bounds", bounds.save());
        }
        tag.putInt("priority", priority);
        if (parentId != null) {
            tag.putString("parent", parentId);
        }
        tag.putString("type", type.key());
        tag.put("owners", uuids(owners));
        tag.put("members", uuids(members));
        ListTag rankList = new ListTag();
        ranks.forEach(r -> rankList.add(net.minecraft.nbt.StringTag.valueOf(r)));
        tag.put("ranks", rankList);
        CompoundTag flagTag = unknownFlags.copy();
        for (Map.Entry<Flag<?>, Object> e : flags.entrySet()) {
            flagTag.put(e.getKey().name(), saveFlag(e.getKey(), e.getValue()));
        }
        tag.put("flags", flagTag);
        if (!typeData.isEmpty()) {
            tag.put("typeData", typeData.copy());
        }
        tag.putLong("updatedAt", updatedAt);
        tag.putInt("version", version);
        return tag;
    }

    @SuppressWarnings("unchecked")
    private static <T> Tag saveFlag(Flag<T> flag, Object value) {
        return flag.save((T) value);
    }

    public static Region load(CompoundTag tag) {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("dim")));
        Cuboid bounds = tag.contains("bounds") ? Cuboid.load(tag.getCompound("bounds")) : null;
        RegionType type = RegionType.byKey(tag.getString("type"));
        Region r = new Region(tag.getString("id"), dim, bounds, type == null ? RegionType.PROTECTED : type);
        r.name = tag.contains("name") ? tag.getString("name") : r.id;
        r.priority = r.isGlobal() ? Integer.MIN_VALUE : tag.getInt("priority");
        r.parentId = tag.contains("parent") ? tag.getString("parent") : null;
        readUuids(tag.getList("owners", Tag.TAG_INT_ARRAY), r.owners);
        readUuids(tag.getList("members", Tag.TAG_INT_ARRAY), r.members);
        for (Tag t : tag.getList("ranks", Tag.TAG_STRING)) {
            r.ranks.add(t.getAsString());
        }
        CompoundTag flagTag = tag.getCompound("flags");
        for (String key : flagTag.getAllKeys()) {
            Flag<?> flag = FlagRegistry.get(key);
            Object value = flag == null ? null : flag.load(flagTag.get(key));
            if (value != null) {
                r.flags.put(flag, value);
            } else {
                r.unknownFlags.put(key, flagTag.get(key).copy());
            }
        }
        if (tag.contains("typeData")) {
            r.typeData = tag.getCompound("typeData").copy();
        }
        r.updatedAt = tag.getLong("updatedAt");
        r.version = tag.getInt("version");
        return r;
    }

    private static ListTag uuids(Set<UUID> set) {
        ListTag list = new ListTag();
        set.forEach(u -> list.add(NbtUtils.createUUID(u)));
        return list;
    }

    private static void readUuids(ListTag list, Set<UUID> into) {
        for (Tag t : list) {
            into.add(NbtUtils.loadUUID(t));
        }
    }

    @Override
    public String toString() {
        return "Region[" + id + " in " + dimension.location() + (bounds == null ? ", global" : ", " + bounds) + ", priority " + priority + "]";
    }
}
