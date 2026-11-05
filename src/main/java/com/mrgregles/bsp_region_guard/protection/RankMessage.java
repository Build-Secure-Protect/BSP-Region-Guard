package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.compat.RankUp;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a player refused by a region's {@code allowed-ranks} is told (owner decisions D149 to D157), as a title line and
 * a subtitle (joined by a newline, shown in the middle of the screen). A rank on a BSP-RankUp ladder is named as the
 * rung to reach, with where the player stands; a rank on no ladder is named as one to be. A region's own
 * {@code entry-deny-message} still wins, with {@code {rank}} filled in.
 */
public final class RankMessage {
    private RankMessage() {
    }

    /** The message for this player, reading RankUp's ladders and their standing on the server. */
    public static String forPlayer(ServerPlayer player, Set<String> ranks, @Nullable String custom) {
        var server = player.getServer();
        List<RankUp.Ladder> ladders = server == null ? List.of() : RankUp.ladders(server);
        return build(ranks, ladders, l -> server == null ? new RankUp.Standing(null, 0) : RankUp.standing(server, player.getUUID(), l), custom);
    }

    /**
     * Builds the message from the region's ranks, the ladders and a way to read the player's standing on a ladder.
     * Pure, so the GameTests can feed it ladders without RankUp installed.
     */
    public static String build(Set<String> ranks, List<RankUp.Ladder> ladders, Function<String, RankUp.Standing> standing, @Nullable String custom) {
        RankUp.Ladder bestLadder = null;
        RankUp.Rung best = null;
        for (String rank : ranks) {
            for (RankUp.Ladder l : ladders) {
                RankUp.Rung r = l.rung(rank);
                if (r != null && (best == null || r.index() < best.index())) {
                    best = r;
                    bestLadder = l;
                }
            }
        }
        String label;
        String title, subtitle = "";
        if (best != null) {
            // D151: the lowest ladder rung among the region's ranks is the one to reach
            label = name(best);
            title = "You need " + label + (ladders.size() > 1 ? " (" + bestLadder.name() + " ladder)" : "") + " to enter";
            RankUp.Standing s = standing.apply(bestLadder.name());
            if (s.rung() == null) {
                subtitle = "You have no rank on this ladder yet.";
            } else if (s.rung().index() == best.index() - 1) {
                long more = Math.max(0, best.points() - s.points());
                subtitle = "You are " + name(s.rung()) + ": " + String.format(Locale.ROOT, "%,d", more) + " more points.";
            } else if (s.rung().index() < best.index()) {
                int steps = best.index() - s.rung().index();
                subtitle = "You are " + name(s.rung()) + ", " + steps + " ranks to go.";
            }
        } else {
            // D151, D156: ranks on no ladder (or no RankUp) are named as ranks to be
            Set<String> sorted = new TreeSet<>();
            ranks.forEach(r -> sorted.add(r.toLowerCase(Locale.ROOT)));
            List<String> names = new ArrayList<>(sorted);
            label = names.size() <= 1 ? String.join("", names)
                    : names.subList(0, names.size() - 1).stream().collect(Collectors.joining(", ")) + " or " + names.get(names.size() - 1);
            title = "You need to be " + label + " to enter";
        }
        if (custom != null && !custom.isBlank()) {
            return custom.replace("{rank}", label); // D154: the region's own message wins
        }
        return subtitle.isEmpty() ? title : title + "\n" + subtitle;
    }

    private static String name(RankUp.Rung r) {
        return r.displayName() == null || r.displayName().isBlank() ? r.group() : r.displayName();
    }
}
