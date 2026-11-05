package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.compat.RankUp;
import com.mrgregles.bsp_region_guard.protection.RankMessage;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/** The rank-needed message on entry refusal (D149 to D157), fed made-up BSP-RankUp ladders. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class RankMessageGameTests {
    private static final String EMPTY = "empty";
    private static final RankUp.Rung PEASANT = new RankUp.Rung("peasant", "Peasant", 0, 0), SQUIRE = new RankUp.Rung("squire", "Squire", 1, 100),
            KNIGHT = new RankUp.Rung("knight", "Knight", 2, 500), LORD = new RankUp.Rung("lord", "Lord", 3, 2000);
    private static final RankUp.Ladder MAIN = new RankUp.Ladder("Main", List.of(PEASANT, SQUIRE, KNIGHT, LORD));
    private static final RankUp.Ladder MINERS = new RankUp.Ladder("Miners", List.of(new RankUp.Rung("digger", "Digger", 0, 0)));

    private static void is(GameTestHelper h, String got, String want) {
        h.assertTrue(got.equals(want), "expected \"" + want.replace("\n", " / ") + "\" but got \"" + got.replace("\n", " / ") + "\"");
    }

    @GameTest(template = EMPTY)
    public void the_rank_needed_is_named_with_where_the_player_stands(GameTestHelper h) {
        List<RankUp.Ladder> one = List.of(MAIN);
        is(h, RankMessage.build(Set.of("knight"), one, l -> new RankUp.Standing(SQUIRE, 300), null),
                "You need Knight to enter\nYou are Squire: 200 more points.");
        is(h, RankMessage.build(Set.of("knight"), one, l -> new RankUp.Standing(PEASANT, 20), null),
                "You need Knight to enter\nYou are Peasant, 2 ranks to go.");
        is(h, RankMessage.build(Set.of("knight"), one, l -> new RankUp.Standing(null, 0), null),
                "You need Knight to enter\nYou have no rank on this ladder yet.");
        is(h, RankMessage.build(Set.of("lord", "knight", "vip"), one, l -> new RankUp.Standing(SQUIRE, 300), null),
                "You need Knight to enter\nYou are Squire: 200 more points.");
        is(h, RankMessage.build(Set.of("lord"), one, l -> new RankUp.Standing(KNIGHT, 1000), null),
                "You need Lord to enter\nYou are Knight: 1,000 more points.");
        is(h, RankMessage.build(Set.of("knight"), List.of(MAIN, MINERS), l -> new RankUp.Standing(SQUIRE, 450), null),
                "You need Knight (Main ladder) to enter\nYou are Squire: 50 more points.");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void ranks_on_no_ladder_and_the_regions_own_message(GameTestHelper h) {
        is(h, RankMessage.build(Set.of("vip", "donor"), List.of(MAIN), l -> new RankUp.Standing(null, 0), null), "You need to be donor or vip to enter");
        is(h, RankMessage.build(Set.of("knight"), List.of(), l -> new RankUp.Standing(null, 0), null), "You need to be knight to enter");
        is(h, RankMessage.build(Set.of("a", "b", "c"), List.of(), l -> new RankUp.Standing(null, 0), null), "You need to be a, b or c to enter");
        is(h, RankMessage.build(Set.of("knight"), List.of(MAIN), l -> new RankUp.Standing(SQUIRE, 0), "Only {rank} and above may enter the Vault."),
                "Only Knight and above may enter the Vault.");
        is(h, RankMessage.build(Set.of("vip"), List.of(), l -> new RankUp.Standing(null, 0), "Members only"), "Members only");
        h.succeed();
    }
}
