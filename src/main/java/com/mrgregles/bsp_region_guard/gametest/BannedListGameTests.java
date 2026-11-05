package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.network.BannedListPacket;
import com.mrgregles.bsp_region_guard.protection.BannedBlocks;
import com.mrgregles.bsp_region_guard.protection.BannedList;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** BANNED HERE (D133 to D139): the lists players are sent, where they stand and for a region by id, and the packet. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class BannedListGameTests {
    private static final String EMPTY = "empty";

    private static Region define(GameTestHelper h, String id, BlockPos from, BlockPos to) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(from), h.absolutePos(to)), RegionType.PLOT_FOR_SALE);
        RegionStore.get(h.getLevel()).add(r);
        return r;
    }

    private static void moveTo(GameTestHelper h, ServerPlayer p, BlockPos rel) {
        BlockPos a = h.absolutePos(rel);
        p.setPos(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
    }

    @GameTest(template = EMPTY)
    public void players_get_the_lists_where_they_stand_and_for_a_plot_by_id(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        RegionStore store = RegionStore.get(h.getLevel());
        try {
            Region plot = define(h, "bl_plot", new BlockPos(2, 1, 2), new BlockPos(9, 8, 9));
            plot.setFlag(Flags.DENY_PLACE, Set.of("#minecraft:swords"));
            plot.setFlag(Flags.DENY_CARRY, Set.of("minecraft:tnt"));
            store.touch(plot);

            moveTo(h, p, new BlockPos(5, 2, 5));
            BannedListPacket here = BannedList.here(p, BannedListPacket.STATUS);
            h.assertTrue(here.any() && "bl_plot".equals(here.regionId()), "inside the plot the lists are named after it: " + here.regionId());
            h.assertTrue(here.lists().get(BannedBlocks.Rule.PLACE).containsAll(List.of("#minecraft:swords", "minecraft:tnt")),
                    "PLACE has the plot's own ban and its carry ban: " + here.lists());
            h.assertTrue(here.lists().get(BannedBlocks.Rule.USE).equals(List.of("minecraft:tnt")), "USE has only the carry ban: " + here.lists());
            h.assertTrue(here.lists().get(BannedBlocks.Rule.CARRY).equals(List.of("minecraft:tnt")), "CARRY has only the carry ban");

            // what the player's game does with the lists: the same matching the server enforces
            Map<String, String> place = new LinkedHashMap<>();
            here.lists().get(BannedBlocks.Rule.PLACE).forEach(e -> place.put(e, "bl_plot"));
            h.assertTrue(BannedBlocks.match(place, new ItemStack(Items.DIAMOND_SWORD)) != null, "a sword matches the swords tag");
            h.assertTrue(BannedBlocks.match(place, new ItemStack(Items.STONE)) == null, "stone is not banned");

            moveTo(h, p, new BlockPos(14, 2, 14));
            h.assertFalse(BannedList.here(p, BannedListPacket.STATUS).lists().get(BannedBlocks.Rule.PLACE).contains("#minecraft:swords"),
                    "outside the plot its bans are not listed");

            BannedListPacket byId = BannedList.region(p, "bl_plot", BannedListPacket.INFO);
            h.assertTrue(byId != null && byId.lists().equals(BannedList.at(h.getLevel(), h.absolutePos(new BlockPos(5, 4, 5)))),
                    "a plot by id is read at its middle, wherever the player stands");
            h.assertTrue(BannedList.region(p, "bl_no_such_region", BannedListPacket.INFO) == null, "an unknown region gives nothing");

            // audit fix: a region inside the plot that bans nothing itself does not take the plot's name
            Region shop = define(h, "bl_shop", new BlockPos(4, 1, 4), new BlockPos(6, 4, 6));
            shop.setParentId("bl_plot");
            shop.setPriority(10);
            store.touch(shop);
            moveTo(h, p, new BlockPos(5, 2, 5));
            h.assertTrue("bl_plot".equals(BannedList.here(p, BannedListPacket.STATUS).regionId()), "the lists are named after the region that bans");

                        Region cornerless = new Region("bl_cornerless", h.getLevel().dimension(), Region.NO_CORNERS, RegionType.PROTECTED);
            store.add(cornerless);
            h.assertTrue(BannedList.region(p, "bl_cornerless", BannedListPacket.INFO) == null, "a region without corners has no middle to read");
        } finally {
            store.remove("bl_shop");
            store.remove("bl_plot");
            store.remove("bl_cornerless");
            MovementTracker.clearAll();
            p.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_banned_list_packet_carries_every_rule_through(GameTestHelper h) {
        var lists = new java.util.EnumMap<BannedBlocks.Rule, List<String>>(BannedBlocks.Rule.class);
        lists.put(BannedBlocks.Rule.PLACE, List.of("#bsp:sanctuary_bans", "@ballistix"));
        lists.put(BannedBlocks.Rule.USE, List.of("#bsp:sanctuary_bans"));
        lists.put(BannedBlocks.Rule.BREAK, List.of());
        lists.put(BannedBlocks.Rule.CARRY, List.of("minecraft:tnt"));
        BannedListPacket sent = new BannedListPacket(BannedListPacket.OPEN, "sanctuary_plot_3", "Plot 3", lists);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BannedListPacket.encode(sent, buf);
        BannedListPacket got = BannedListPacket.decode(buf);
        h.assertTrue(got.equals(sent), "decoded as sent: " + got);
        h.assertTrue(!new BannedListPacket(BannedListPacket.STATUS, "", "", new java.util.EnumMap<>(BannedBlocks.Rule.class)).any(), "empty lists ban nothing");
        h.succeed();
    }
}
