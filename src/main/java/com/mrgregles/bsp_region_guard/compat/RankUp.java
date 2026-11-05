package com.mrgregles.bsp_region_guard.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The only class that touches BSP-RankUp (owner decisions D149 to D157). RankUp is optional and nothing is compiled
 * against it: its {@code RankUpApi} (API 2 or later) is read by reflection, copied into the plain records below. Every
 * call answers "nothing known" when RankUp is missing, older or fails, so Region Guard falls back to LuckPerms names.
 */
public final class RankUp {
    public static final String MODID = "bsp_rankup";
    private static final String API = "com.mrgregles.bsp_rankup.api.RankUpApi";

    /** One rung: its LuckPerms group (lower case), the name players see, its place on the ladder (0 = first) and the points it needs. */
    public record Rung(String group, String displayName, int index, long points) {
    }

    /** One ladder (a rank-up path) with its rungs, lowest first. */
    public record Ladder(String name, List<Rung> rungs) {
        @Nullable
        public Rung rung(String group) {
            String g = group.toLowerCase(Locale.ROOT);
            for (Rung r : rungs) {
                if (r.group().equals(g)) {
                    return r;
                }
            }
            return null;
        }
    }

    /** Where a player stands on one ladder: the rung they hold (null for none) and their points. */
    public record Standing(@Nullable Rung rung, long points) {
    }

    private RankUp() {
    }

    public static boolean isPresent() {
        return ModList.get() != null && ModList.get().isLoaded(MODID);
    }

    /** RankUp's methods, looked up once (they are used on hot paths: every ranked entry or build check). */
    private record Calls(Method ladders, Method progressOf, Method hasRank) {
    }

    @Nullable
    private static volatile Calls calls;
    private static volatile boolean unusable;

    @Nullable
    private static Calls calls() {
        Calls c = calls;
        if (c != null || unusable) {
            return c;
        }
        try {
            Class<?> api = Class.forName(API);
            if ((int) api.getMethod("apiVersion").invoke(null) < 2) {
                unusable = true; // older than API 2: no ladders
                return null;
            }
            c = new Calls(api.getMethod("ladders", MinecraftServer.class),
                    api.getMethod("progressOf", MinecraftServer.class, UUID.class, String.class),
                    api.getMethod("hasRank", MinecraftServer.class, UUID.class, String.class, String.class));
            calls = c;
            return c;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            unusable = true;
            return null;
        }
    }

    /** Every ladder RankUp knows, or an empty list. */
    public static List<Ladder> ladders(MinecraftServer server) {
        Calls c = isPresent() ? calls() : null;
        if (c == null) {
            return List.of();
        }
        try {
            List<?> raw = (List<?>) c.ladders().invoke(null, server);
            List<Ladder> out = new ArrayList<>();
            for (Object l : raw) {
                List<Rung> rungs = new ArrayList<>();
                for (Object r : (List<?>) call(l, "rungs")) {
                    rungs.add(rung(r));
                }
                out.add(new Ladder((String) call(l, "name"), rungs));
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /** Where a player stands on a ladder, or a standing with no rung and 0 points when unknown. */
    public static Standing standing(MinecraftServer server, UUID player, String ladder) {
        Calls c = isPresent() ? calls() : null;
        if (c == null) {
            return new Standing(null, 0);
        }
        try {
            Optional<?> p = (Optional<?>) c.progressOf().invoke(null, server, player, ladder);
            if (p.isEmpty()) {
                return new Standing(null, 0);
            }
            Object info = p.get();
            Object held = call(info, "rank");
            return new Standing(held == null ? null : rung(held), (long) call(info, "points"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return new Standing(null, 0);
        }
    }

    /** True when the player holds one of these groups' rungs, or a higher one, on a ladder that has it (D152). */
    public static boolean holdsAtLeast(MinecraftServer server, UUID player, java.util.Collection<String> groups) {
        Calls c = isPresent() ? calls() : null;
        if (c == null) {
            return false;
        }
        try {
            for (Ladder l : ladders(server)) { // read once for all the groups
                for (String group : groups) {
                    Rung r = l.rung(group);
                    if (r != null && (boolean) c.hasRank().invoke(null, server, player, l.name(), r.group())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static Rung rung(Object r) throws ReflectiveOperationException {
        return new Rung(((String) call(r, "group")).toLowerCase(Locale.ROOT), (String) call(r, "displayName"), (int) call(r, "index"), (long) call(r, "points"));
    }

    private static Object call(Object target, String accessor) throws ReflectiveOperationException {
        return target.getClass().getMethod(accessor).invoke(target);
    }
}
