package com.mrgregles.bsp_region_guard.command;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab completion for the destination of {@code /rg trigger add <region> <cause> <destination>}: what the admin has
 * typed so far decides the next word. A timer starts with its seconds; then {@code here}, a world of this server, or
 * {@code server/world} for a world on another server; then x, y and z (the admin's own position is offered); then a
 * free message, which gets no suggestions. Kept free of Minecraft types so it can be tested on its own.
 */
public final class TriggerSuggestions {
    private TriggerSuggestions() {
    }

    /** Where the suggestions start in the typed text, and what they are. */
    public record Result(int start, List<String> suggestions) {
    }

    /**
     * @param cause  the cause already typed (enter, exit, timer, shutdown)
     * @param typed  the destination text so far, from its first character
     * @param at     the admin's block position {x, y, z}, or null from the console
     * @param worlds this server's world ids, e.g. minecraft:overworld
     * @param remote other servers' worlds as server/world, e.g. survival-2/minecraft:overworld
     */
    public static Result suggest(String cause, String typed, @Nullable int[] at, List<String> worlds, List<String> remote) {
        int start = typed.lastIndexOf(' ') + 1;
        String[] done = start == 0 ? new String[0] : typed.substring(0, start).trim().split("\\s+");
        String current = typed.substring(start);
        List<String> out = new ArrayList<>();
        int word = done.length;
        int dest = "timer".equalsIgnoreCase(cause) ? 1 : 0;
        if (word < dest) {
            for (String s : List.of("10", "30", "60", "300")) {
                out.add(s);
            }
        } else if (word == dest) {
            out.add("here");
            out.addAll(worlds);
            for (String w : worlds) {
                if (w.startsWith("minecraft:")) {
                    out.add(w.substring("minecraft:".length()));
                }
            }
            out.addAll(remote);
        } else if (!done[dest].equalsIgnoreCase("here") && word <= dest + 3 && at != null) {
            // x y z: offer the rest of the admin's own position from here on
            int axis = word - dest - 1;
            StringBuilder rest = new StringBuilder();
            for (int i = axis; i < 3; i++) {
                rest.append(rest.length() == 0 ? "" : " ").append(at[i]);
            }
            out.add(rest.toString());
            if (axis < 2) {
                out.add(String.valueOf(at[axis]));
            }
        }
        List<String> matching = new ArrayList<>();
        for (String s : out) {
            if (s.toLowerCase(java.util.Locale.ROOT).startsWith(current.toLowerCase(java.util.Locale.ROOT)) && !matching.contains(s)) {
                matching.add(s);
            }
        }
        return new Result(start, matching);
    }
}
