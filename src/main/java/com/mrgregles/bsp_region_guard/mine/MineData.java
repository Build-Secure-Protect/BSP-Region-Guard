package com.mrgregles.bsp_region_guard.mine;

import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.region.Region;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The mine settings kept in a region's type data: the fill table, the interval, the next reset and the spawn point. */
public final class MineData {
    public static final String FILL = "fill", INTERVAL = "intervalSeconds", NEXT_RESET = "nextResetAt", SPAWN = "mineSpawn";

    /** One row of the fill table. {@code weight} is relative: a block with weight 90 next to one with 10 covers about nine tenths. */
    public record Entry(ResourceLocation block, int weight) {
        @Nullable
        public Block resolve() {
            return ForgeRegistries.BLOCKS.containsKey(block) ? ForgeRegistries.BLOCKS.getValue(block) : null;
        }
    }

    private MineData() {
    }

    public static List<Entry> fill(Region mine) {
        List<Entry> out = new ArrayList<>();
        for (Tag t : mine.typeData().getList(FILL, Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            ResourceLocation id = ResourceLocation.tryParse(c.getString("block"));
            if (id != null && c.getInt("weight") > 0) {
                out.add(new Entry(id, c.getInt("weight")));
            }
        }
        return out;
    }

    public static void setFill(Region mine, List<Entry> entries) {
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag c = new CompoundTag();
            c.putString("block", e.block().toString());
            c.putInt("weight", e.weight());
            list.add(c);
        }
        mine.typeData().put(FILL, list);
    }

    /** Adds or replaces a row; weight 0 removes it. */
    public static void put(Region mine, ResourceLocation block, int weight) {
        List<Entry> entries = fill(mine);
        entries.removeIf(e -> e.block().equals(block));
        if (weight > 0) {
            entries.add(new Entry(block, weight));
        }
        setFill(mine, entries);
    }

    public static int totalWeight(List<Entry> entries) {
        int total = 0;
        for (Entry e : entries) {
            total += e.weight();
        }
        return total;
    }

    public static int intervalSeconds(Region mine) {
        return mine.typeData().getInt(INTERVAL);
    }

    public static void setIntervalSeconds(Region mine, int seconds) {
        if (seconds <= 0) {
            mine.typeData().remove(INTERVAL);
            mine.typeData().remove(NEXT_RESET);
        } else {
            mine.typeData().putInt(INTERVAL, seconds);
        }
    }

    /** When the next automatic reset is due (epoch ms), or 0 when none is scheduled. */
    public static long nextResetAt(Region mine) {
        return mine.typeData().getLong(NEXT_RESET);
    }

    public static void setNextResetAt(Region mine, long at) {
        mine.typeData().putLong(NEXT_RESET, at);
    }

    @Nullable
    public static FlagLocation spawn(Region mine) {
        return mine.typeData().contains(SPAWN) ? FlagLocation.load(mine.typeData().getCompound(SPAWN)) : null;
    }

    public static void setSpawn(Region mine, @Nullable FlagLocation spawn) {
        if (spawn == null) {
            mine.typeData().remove(SPAWN);
        } else {
            mine.typeData().put(SPAWN, spawn.save());
        }
    }
}
