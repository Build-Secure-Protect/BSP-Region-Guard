package com.mrgregles.bsp_region_guard.client;

import java.util.List;

/**
 * Where the BANNED inventory button stands, in plain numbers (no Minecraft classes, so GameTests can call it on the
 * server). Rectangles are {@code {x, y, width, height}} in GUI units.
 *
 * <p>The order in the top left corner is TEAM, KITS, SUPPORT BSP, BANNED (Server Utilities decision D587, SU-RG1).
 * BANNED starts on SUPPORT BSP's row when SUPPORT BSP is drawn, otherwise on BSP-Core's top row, right of the rightmost
 * key of either kind on that row (or the window's corner when there is none). It then steps right past anything in the
 * way, and down a row when the left half of the window is used.
 */
public final class BannedButtonRow {
    public static final int W = 40, H = 14, GAP = 2, MARGIN = 2;
    /** Room kept free above and below the inventory panel for tab rows, as BSP-Core keeps. */
    static final int TAB_ROWS = 32;

    private BannedButtonRow() {
    }

    /**
     * @param blocked     every other drawn widget (BSP-Core's and SUPPORT BSP's keys included)
     * @param core        BSP-Core's drawn TEAM and KITS keys
     * @param support     Server Utilities' drawn SUPPORT BSP key(s)
     * @param panel       the inventory panel {@code {guiLeft, guiTop, xSize, ySize}}
     * @param screenWidth the window's width in GUI units
     * @return {@code {x, y}}
     */
    public static int[] place(List<int[]> blocked, List<int[]> core, List<int[]> support, int[] panel, int screenWidth) {
        int x = MARGIN, y = MARGIN;
        if (!core.isEmpty() || !support.isEmpty()) {
            // SUPPORT BSP stands after BSP-Core's keys, so its row is the last; BSP-Core's keys start on their top row.
            y = support.isEmpty()
                    ? core.stream().mapToInt(r -> r[1]).min().getAsInt()
                    : support.stream().mapToInt(r -> r[1]).max().getAsInt();
            for (List<int[]> keys : List.of(core, support)) {
                for (int[] k : keys) {
                    if (k[1] == y) {
                        x = Math.max(x, k[0] + k[2] + GAP);
                    }
                }
            }
            // SUPPORT BSP may end close to the middle of the window (it keeps to the left half, BANNED's 40 more may not
            // fit): then the next row, as when stepping past something. Not checked after BSP-Core's keys alone, so
            // BANNED stands exactly where it did before SU-RG1 when SUPPORT BSP is not drawn.
            if (!support.isEmpty() && x + W > screenWidth / 2) {
                x = MARGIN;
                y += H + GAP;
            }
        }
        int[] panelBand = {panel[0], panel[1] - TAB_ROWS, panel[2], panel[3] + 2 * TAB_ROWS};
        for (int tries = 0; tries < 64; tries++) {
            int[] hit = overlap(blocked, panelBand, x, y);
            if (hit == null) {
                return new int[]{x, y};
            }
            x = hit[0] + hit[2] + GAP;
            if (x + W > screenWidth / 2) {
                x = MARGIN;
                y += H + GAP;
            }
        }
        return new int[]{x, y};
    }

    private static int[] overlap(List<int[]> blocked, int[] panelBand, int x, int y) {
        for (int[] b : blocked) {
            if (hits(b, x, y)) {
                return b;
            }
        }
        return hits(panelBand, x, y) ? panelBand : null;
    }

    private static boolean hits(int[] b, int x, int y) {
        return x < b[0] + b[2] && x + W > b[0] && y < b[1] + b[3] && y + H > b[1];
    }
}
