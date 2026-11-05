package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.region.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which plots each Land Office lists (owner decisions D140 to D148), per world, kept in that world's save as
 * {@code bsp_region_guard_offices}. An office with no entry, or set to EVERY PLOT, lists every plot for sale and open
 * area in its world, as before. Set to CHOSEN, it lists the plots ticked for it and every plot in the sale groups
 * ticked for it. A plot's sale group is its own {@code sale-group} flag, so a copied plot keeps its group. Offices are
 * BSP Core's blocks; this only remembers them by position.
 */
public final class OfficeLists extends SavedData {
    private static final String NAME = "bsp_region_guard_offices";

    /** One office's choice. */
    public static final class Office {
        public boolean chosenOnly;
        public final Set<String> plots = new TreeSet<>();
        public final Set<String> groups = new TreeSet<>();

        boolean isEmpty() {
            return !chosenOnly && plots.isEmpty() && groups.isEmpty();
        }
    }

    private final Map<Long, Office> offices = new TreeMap<>();
    /** Group names made in the OFFICE PLOTS screen before any plot was put in them. */
    private final Set<String> groupNames = new TreeSet<>();

    public static OfficeLists get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(OfficeLists::load, OfficeLists::new, NAME);
    }

    /** The office at this position, or null when it has never been set up. */
    @Nullable
    public Office at(BlockPos pos) {
        return offices.get(pos.asLong());
    }

    /** The office at this position, made empty (EVERY PLOT) if it was never set up. */
    public Office edit(BlockPos pos) {
        setDirty();
        return offices.computeIfAbsent(pos.asLong(), k -> new Office());
    }

    /** Forgets an office (its block was broken). */
    public void forget(BlockPos pos) {
        if (offices.remove(pos.asLong()) != null) {
            setDirty();
        }
    }

    /** Whether the office at {@code pos} lists this plot or open area. Offices never set up list everything. */
    public boolean lists(BlockPos pos, Region plot) {
        Office o = at(pos);
        if (o == null || !o.chosenOnly) {
            return true;
        }
        if (o.plots.contains(plot.id())) {
            return true;
        }
        String g = group(plot);
        return !g.isEmpty() && o.groups.contains(g);
    }

    /** A plot's sale group, lower case, or "" for none. */
    public static String group(Region plot) {
        String g = plot.flag(Flags.SALE_GROUP);
        return g == null ? "" : normal(g);
    }

    /** How group names are kept: trimmed, lower case, spaces as underscores. */
    public static String normal(String group) {
        return group.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    /** The OFFICE PLOTS search, shared by the screen and the server: id, name or group contains the text, ignoring case; empty matches all. */
    public static boolean matches(String id, String name, String group, String search) {
        String q = search.trim().toLowerCase(Locale.ROOT);
        return q.isEmpty() || id.contains(q) || name.toLowerCase(Locale.ROOT).contains(q) || group.contains(q);
    }

    public Set<String> madeGroups() {
        return groupNames;
    }

    public void makeGroup(String name) {
        String g = normal(name);
        if (!g.isEmpty() && groupNames.add(g)) {
            setDirty();
        }
    }

    /** A plot got a new id (renamed): every office that ticked it keeps it ticked. */
    public void renamed(String oldId, String newId) {
        for (Office o : offices.values()) {
            if (o.plots.remove(oldId)) {
                o.plots.add(newId);
                setDirty();
            }
        }
    }

    /** A plot was copied on the region map: the copy is ticked wherever the original is (D146). */
    public void copied(String sourceId, String copyId) {
        for (Office o : offices.values()) {
            if (o.plots.contains(sourceId) && o.plots.add(copyId)) {
                setDirty();
            }
        }
    }

    // ------------------------------------------------------------------ saving

    private static OfficeLists load(CompoundTag tag) {
        OfficeLists out = new OfficeLists();
        for (Tag t : tag.getList("offices", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Office o = new Office();
            o.chosenOnly = c.getBoolean("chosen");
            c.getList("plots", Tag.TAG_STRING).forEach(s -> o.plots.add(s.getAsString()));
            c.getList("groups", Tag.TAG_STRING).forEach(s -> o.groups.add(s.getAsString()));
            out.offices.put(c.getLong("pos"), o);
        }
        tag.getList("groupNames", Tag.TAG_STRING).forEach(s -> out.groupNames.add(s.getAsString()));
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        offices.forEach((pos, o) -> {
            if (o.isEmpty()) {
                return;
            }
            CompoundTag c = new CompoundTag();
            c.putLong("pos", pos);
            c.putBoolean("chosen", o.chosenOnly);
            c.put("plots", strings(o.plots));
            c.put("groups", strings(o.groups));
            list.add(c);
        });
        tag.put("offices", list);
        tag.put("groupNames", strings(groupNames));
        return tag;
    }

    private static ListTag strings(Set<String> values) {
        ListTag l = new ListTag();
        values.forEach(v -> l.add(StringTag.valueOf(v)));
        return l;
    }
}
