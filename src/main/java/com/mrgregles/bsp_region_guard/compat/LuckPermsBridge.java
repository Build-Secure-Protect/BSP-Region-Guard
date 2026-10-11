package com.mrgregles.bsp_region_guard.compat;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The only class that touches LuckPerms. Read-only: Region Guard stores rank names and never writes to LuckPerms.
 *
 * <p>Two ways in. On a Forge server with the LuckPerms Forge mod, the API classes are on our class path and are used
 * directly. On a Mohist server with the LuckPerms Bukkit plugin, the plugin lives in its own class loader and our
 * mod cannot see its classes, so the bridge finds the plugin through Bukkit's plugin manager and talks to the same
 * API by reflection. Either way the rest of the mod only sees group names.
 */
public final class LuckPermsBridge {
    private static final String PROVIDER = "net.luckperms.api.LuckPermsProvider";

    /** How LuckPerms was reached. */
    public enum Route { NONE, FORGE, BUKKIT }

    @Nullable
    private static Route route;
    /** The LuckPerms API object when reached by reflection (a class from the plugin's loader). */
    @Nullable
    private static Object bukkitApi;

    private LuckPermsBridge() {
    }

    public static boolean isPresent() {
        return route() != Route.NONE;
    }

    public static Route route() {
        if (route == null) {
            route = detect();
            if (route != Route.NONE) {
                BSPRegionGuard.LOGGER.info("LuckPerms reached through the {} edition", route == Route.FORGE ? "Forge" : "Bukkit");
            }
        }
        return route;
    }

    private static Route detect() {
        try {
            Class.forName(PROVIDER);
            net.luckperms.api.LuckPermsProvider.get();
            return Route.FORGE;
        } catch (ClassNotFoundException | NoClassDefFoundError | IllegalStateException e) {
            // not on our class path: try Bukkit
        }
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object pluginManager = bukkit.getMethod("getPluginManager").invoke(null);
            if (pluginManager == null) {
                return Route.NONE;
            }
            Object plugin = pluginManager.getClass().getMethod("getPlugin", String.class).invoke(pluginManager, "LuckPerms");
            if (plugin == null) {
                return Route.NONE;
            }
            Class<?> provider = plugin.getClass().getClassLoader().loadClass(PROVIDER);
            bukkitApi = provider.getMethod("get").invoke(null);
            return bukkitApi == null ? Route.NONE : Route.BUKKIT;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPRegionGuard.LOGGER.debug("LuckPerms not reachable through Bukkit: {}", e.toString());
            return Route.NONE;
        }
    }

    /** Forgets the cached answer, for example after a reload. */
    public static void reset() {
        route = null;
        bukkitApi = null;
    }

    // ------------------------------------------------------------------ lookups

    /**
     * The groups a player is in, directly or by inheritance, lower-cased. Empty when LuckPerms is absent or the user
     * is not loaded (online players always are).
     */
    public static Set<String> groupsOf(UUID player) {
        switch (route()) {
            case FORGE -> {
                try {
                    net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(player);
                    if (user == null) {
                        return Collections.emptySet();
                    }
                    Set<String> out = new TreeSet<>();
                    out.add(user.getPrimaryGroup().toLowerCase(Locale.ROOT));
                    for (net.luckperms.api.model.group.Group g : user.getInheritedGroups(user.getQueryOptions())) {
                        out.add(g.getName().toLowerCase(Locale.ROOT));
                    }
                    return out;
                } catch (RuntimeException e) {
                    BSPRegionGuard.LOGGER.warn("LuckPerms user lookup failed", e);
                    return Collections.emptySet();
                }
            }
            case BUKKIT -> {
                try {
                    Object userManager = call(bukkitApi, "getUserManager");
                    Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player);
                    if (user == null) {
                        return Collections.emptySet();
                    }
                    Set<String> out = new TreeSet<>();
                    out.add(((String) call(user, "getPrimaryGroup")).toLowerCase(Locale.ROOT));
                    Object queryOptions = call(user, "getQueryOptions");
                    Method inherited = find(user.getClass(), "getInheritedGroups");
                    Collection<?> groups = (Collection<?>) inherited.invoke(user, queryOptions);
                    for (Object g : groups) {
                        out.add(((String) call(g, "getName")).toLowerCase(Locale.ROOT));
                    }
                    return out;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    BSPRegionGuard.LOGGER.warn("LuckPerms (Bukkit) user lookup failed", e);
                    return Collections.emptySet();
                }
            }
            default -> {
                return Collections.emptySet();
            }
        }
    }

    /** Names of every loaded group (rank). Empty when LuckPerms is absent. */
    public static List<String> groupNames() {
        switch (route()) {
            case FORGE -> {
                try {
                    return net.luckperms.api.LuckPermsProvider.get().getGroupManager().getLoadedGroups().stream()
                            .map(net.luckperms.api.model.group.Group::getName)
                            .sorted()
                            .toList();
                } catch (RuntimeException e) {
                    BSPRegionGuard.LOGGER.warn("LuckPerms group lookup failed", e);
                    return Collections.emptyList();
                }
            }
            case BUKKIT -> {
                try {
                    Object groupManager = call(bukkitApi, "getGroupManager");
                    Collection<?> groups = (Collection<?>) call(groupManager, "getLoadedGroups");
                    Set<String> out = new TreeSet<>();
                    for (Object g : groups) {
                        out.add((String) call(g, "getName"));
                    }
                    return List.copyOf(out);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    BSPRegionGuard.LOGGER.warn("LuckPerms (Bukkit) group lookup failed", e);
                    return Collections.emptyList();
                }
            }
            default -> {
                return Collections.emptyList();
            }
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    /** Calls a public no-argument method by name, looking through the class and its interfaces. */
    private static Object call(Object target, String method) throws ReflectiveOperationException {
        Method m = find(target.getClass(), method);
        return m.invoke(target);
    }

    private static Method find(Class<?> type, String name) throws NoSuchMethodException {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
