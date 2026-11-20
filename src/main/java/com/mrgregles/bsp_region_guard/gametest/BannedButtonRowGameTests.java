package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.client.BannedButtonRow;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where the BANNED inventory button stands (SU-RG1, Server Utilities' D587: TEAM, KITS, SUPPORT BSP, BANNED). Plain
 * arithmetic: the client is not needed. The keys stand where BSP-Core and Server Utilities 0.15.0 put them in the
 * survival inventory (176 x 166, centred).
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class BannedButtonRowGameTests {
    private static final String EMPTY = "empty";
    private static final int[] TEAM = {2, 2, 40, 14}, KITS = {44, 2, 40, 14};

    private static int[] panel(int w, int h) {
        return new int[]{(w - 176) / 2, (h - 166) / 2, 176, 166};
    }

    private static int[] place(int w, int h, List<int[]> core, List<int[]> support) {
        List<int[]> blocked = new ArrayList<>(core);
        blocked.addAll(support);
        return BannedButtonRow.place(blocked, core, support, panel(w, h), w);
    }

    private static void at(GameTestHelper h, int[] got, int x, int y, String what) {
        h.assertTrue(got[0] == x && got[1] == y, what + ": expected " + x + "," + y + ", got " + Arrays.toString(got));
    }

    /** 1366 x 768 at GUI scale 3: SUPPORT BSP (62 wide) drops to the second row, so BANNED follows it there. */
    @GameTest(template = EMPTY)
    public void banned_follows_support_bsp_on_a_455_by_256_window(GameTestHelper h) {
        // Server Utilities: after KITS at 86 the key would reach the inventory's tab-row band (x 139), so it moves down
        int[] support = {2, 18, 62, 14};
        at(h, place(455, 256, List.of(TEAM, KITS), List.of(support)), 66, 18, "with SUPPORT BSP: TEAM KITS / SUPPORT BSP BANNED");
        at(h, place(455, 256, List.of(TEAM, KITS), List.of()), 86, 2, "SUPPORT BSP not drawn: after KITS on the first row, as before");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void banned_follows_support_bsp_when_bsp_core_is_absent(GameTestHelper h) {
        at(h, place(455, 256, List.of(), List.of(new int[]{2, 2, 62, 14})), 66, 2, "no BSP-Core: right after SUPPORT BSP");
        at(h, place(455, 256, List.of(), List.of()), 2, 2, "no keys at all: the window's corner");
        h.succeed();
    }

    /** A wide window (1920 x 1080 at GUI scale 3): all four keys on one row, in order. */
    @GameTest(template = EMPTY)
    public void banned_ends_the_row_on_a_wide_window(GameTestHelper h) {
        at(h, place(640, 360, List.of(TEAM, KITS), List.of(new int[]{86, 2, 62, 14})), 150, 2, "TEAM KITS SUPPORT BSP BANNED");
        at(h, place(640, 360, List.of(TEAM, KITS), List.of()), 86, 2, "TEAM KITS BANNED");
        h.succeed();
    }

    /** The smallest window (320 x 240): BSP-Core's KITS already sits on the second row, SUPPORT BSP on the third. */
    @GameTest(template = EMPTY)
    public void banned_follows_support_bsp_when_every_key_has_its_own_row(GameTestHelper h) {
        List<int[]> core = List.of(TEAM, new int[]{2, 18, 40, 14});
        at(h, place(320, 240, core, List.of(new int[]{2, 34, 62, 14})), 2, 50, "TEAM / KITS / SUPPORT BSP / BANNED");
        at(h, place(320, 240, core, List.of()), 2, 34, "SUPPORT BSP not drawn: below KITS, as before");
        h.succeed();
    }

    /** SUPPORT BSP pushed to the middle of the window by another mod's widget: BANNED goes down a row, not past the middle. */
    @GameTest(template = EMPTY)
    public void banned_keeps_to_the_left_half_after_support_bsp(GameTestHelper h) {
        List<int[]> core = List.of(TEAM, KITS);
        List<int[]> support = List.of(new int[]{256, 2, 62, 14}); // ends at 318, the middle of a 640 wide window is 320
        List<int[]> blocked = new ArrayList<>(core);
        blocked.add(new int[]{86, 2, 168, 14}); // the other widget
        blocked.addAll(support);
        at(h, BannedButtonRow.place(blocked, core, support, panel(640, 360), 640), 2, 18, "the next row, from the left");
        // the creative inventory (195 x 136) on the smallest window: SUPPORT BSP ends at 148, BANNED would end at 190
        List<int[]> creative = List.of(new int[]{86, 2, 62, 14});
        List<int[]> keys = new ArrayList<>(core);
        keys.addAll(creative);
        int[] creativePanel = {(320 - 195) / 2, (240 - 136) / 2, 195, 136};
        at(h, BannedButtonRow.place(keys, core, creative, creativePanel, 320), 2, 18, "creative 320 x 240: TEAM KITS SUPPORT BSP / BANNED");
        h.succeed();
    }

    /** BANNED still steps past what stands in its way and moves down a row at the middle of the window. */
    @GameTest(template = EMPTY)
    public void banned_steps_past_other_widgets_after_support_bsp(GameTestHelper h) {
        List<int[]> core = List.of(TEAM, KITS);
        List<int[]> support = List.of(new int[]{2, 18, 62, 14});
        List<int[]> blocked = new ArrayList<>(core);
        blocked.addAll(support);
        blocked.add(new int[]{70, 18, 20, 14}); // another mod's widget right after SUPPORT BSP
        at(h, BannedButtonRow.place(blocked, core, support, panel(455, 256), 455), 92, 18, "past the other widget");
        blocked.add(new int[]{92, 18, 100, 14}); // and one filling the rest of the left half
        at(h, BannedButtonRow.place(blocked, core, support, panel(455, 256), 455), 2, 34, "down to the next row");
        h.succeed();
    }
}
