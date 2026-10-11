package com.mrgregles.bsp_region_guard.flag;

import org.jetbrains.annotations.Nullable;

/** The value of a state flag. Absence (inherit) is modelled as {@code null}. */
public enum State {
    ALLOW, DENY;

    @Nullable
    public static State parse(String input) {
        return switch (input.toLowerCase()) {
            case "allow", "yes", "true", "on" -> ALLOW;
            case "deny", "no", "false", "off" -> DENY;
            default -> null;
        };
    }

    public String key() {
        return name().toLowerCase();
    }
}
