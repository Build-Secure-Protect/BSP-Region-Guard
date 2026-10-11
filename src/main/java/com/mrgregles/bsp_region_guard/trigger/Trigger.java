package com.mrgregles.bsp_region_guard.trigger;

import com.mrgregles.bsp_region_guard.api.event.RegionTriggerEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One teleport trigger on a region (owner brief, point 4): when {@code cause} happens, send the player to
 * {@code server/world/x y z}. {@code server} empty means this server. {@code intervalSeconds} is for TIMER causes.
 */
public record Trigger(RegionTriggerEvent.Cause cause, String server, String world, double x, double y, double z, int intervalSeconds, String message) {
    public static final String KEY = "triggers";

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("cause", cause.name().toLowerCase(Locale.ROOT));
        t.putString("server", server);
        t.putString("world", world);
        t.putDouble("x", x);
        t.putDouble("y", y);
        t.putDouble("z", z);
        t.putInt("interval", intervalSeconds);
        t.putString("message", message);
        return t;
    }

    @Nullable
    public static Trigger load(CompoundTag t) {
        try {
            return new Trigger(RegionTriggerEvent.Cause.valueOf(t.getString("cause").toUpperCase(Locale.ROOT)), t.getString("server"), t.getString("world"),
                    t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), t.getInt("interval"), t.getString("message"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static List<Trigger> of(CompoundTag typeData) {
        List<Trigger> out = new ArrayList<>();
        for (Tag t : typeData.getList(KEY, Tag.TAG_COMPOUND)) {
            Trigger trigger = load((CompoundTag) t);
            if (trigger != null) {
                out.add(trigger);
            }
        }
        return out;
    }

    public static void store(CompoundTag typeData, List<Trigger> triggers) {
        if (triggers.isEmpty()) {
            typeData.remove(KEY);
            return;
        }
        ListTag list = new ListTag();
        triggers.forEach(tr -> list.add(tr.save()));
        typeData.put(KEY, list);
    }

    public String describe() {
        return cause.name().toLowerCase(Locale.ROOT) + (cause == RegionTriggerEvent.Cause.TIMER ? " every " + intervalSeconds + " s" : "")
                + " -> " + (server.isEmpty() ? "" : server + " / ") + world + " " + (int) x + " " + (int) y + " " + (int) z
                + (message.isEmpty() ? "" : " \"" + message + "\"");
    }
}
