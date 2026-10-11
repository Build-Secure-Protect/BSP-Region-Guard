package com.mrgregles.bsp_region_guard.compat;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The only class that touches LuckPerms. Read-only: Region Guard stores rank names and never writes to LuckPerms.
 *
 * <p>On the BSP network LuckPerms is the Bukkit plugin on each Mohist backend, so the API classes may or may not be
 * reachable from a Forge mod. {@link #isPresent()} checks without loading anything else, and every other method
 * returns an empty answer when it is absent. Group lookups come with task T6.1.
 */
public final class LuckPermsBridge {
    private static Boolean present;

    private LuckPermsBridge() {
    }

    public static boolean isPresent() {
        if (present == null) {
            try {
                Class.forName("net.luckperms.api.LuckPermsProvider");
                net.luckperms.api.LuckPermsProvider.get();
                present = true;
            } catch (ClassNotFoundException | NoClassDefFoundError | IllegalStateException e) {
                present = false;
            }
        }
        return present;
    }

    /** Forgets the cached answer, for example after a reload. */
    public static void reset() {
        present = null;
    }

    /**
     * The groups a player is in, directly or by inheritance, lower-cased. Empty when LuckPerms is absent or the user
     * is not loaded (online players always are).
     */
    public static Set<String> groupsOf(UUID player) {
        if (!isPresent()) {
            return Collections.emptySet();
        }
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

    /** Names of every loaded group (rank). Empty when LuckPerms is absent. */
    public static List<String> groupNames() {
        if (!isPresent()) {
            return Collections.emptyList();
        }
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
}
