package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.network.RegionActionPacket;
import com.mrgregles.bsp_region_guard.network.RegionDetailPacket;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import com.mrgregles.bsp_region_guard.network.RegionSync;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import com.mrgregles.bsp_region_guard.selection.Selections;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The screen's server side: packets survive the wire, and every action the screen sends does what the command would. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class SyncGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    @GameTest(template = EMPTY)
    public void packets_survive_encoding(GameTestHelper h) {
        Cuboid box = new Cuboid(-5, 1, -5, 10, 20, 12);
        RegionListPacket list = new RegionListPacket(ResourceLocation.parse("minecraft:overworld"),
                List.of(new RegionListPacket.Summary("spawn", "Spawn", "protected", 10, null, box, 2, 0, 3),
                        new RegionListPacket.Summary("__global__", "World default", "global", Integer.MIN_VALUE, null, null, 0, 0, 1)),
                "spawn", true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RegionListPacket.encode(list, buf);
        RegionListPacket back = RegionListPacket.decode(buf);
        h.assertTrue(back.equals(list), "list packet round trip");

        RegionDetailPacket detail = new RegionDetailPacket("spawn", "Spawn", "protected", 10, "parent", box,
                List.of(new RegionDetailPacket.Person(UUID.randomUUID(), "Mrgregles")), List.of(), List.of("vip"),
                Map.of("pvp", "deny", "greeting", "Welcome to spawn"), Map.of("mob-spawning", "deny"), List.of("default", "vip"), 7, 123456789L, Map.of("price", "1200"));
        buf = new FriendlyByteBuf(Unpooled.buffer());
        RegionDetailPacket.encode(detail, buf);
        RegionDetailPacket back2 = RegionDetailPacket.decode(buf);
        h.assertTrue(back2.equals(detail), "detail packet round trip");

        RegionActionPacket action = new RegionActionPacket(RegionActionPacket.FLAG, "spawn", "greeting", "Hello there, with spaces");
        buf = new FriendlyByteBuf(Unpooled.buffer());
        RegionActionPacket.encode(action, buf);
        h.assertTrue(RegionActionPacket.decode(buf).equals(action), "action packet round trip");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void screen_actions_edit_the_region(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        BlockPos a = h.absolutePos(new BlockPos(2, 1, 2)), b = h.absolutePos(new BlockPos(6, 4, 6));
        admin.setPos(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
        try {
            Selections.of(admin).setPos1(level.dimension(), a);
            Selections.of(admin).setPos2(level.dimension(), b);
            String msg = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.CREATE, "", "sync_test", ""));
            Region r = store(h).get("sync_test");
            h.assertTrue(r != null && r.bounds().equals(Cuboid.of(a, b)), "create from the wand selection: " + msg);

            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.FLAG, "sync_test", "pvp", "deny"));
            h.assertTrue(r.flag(Flags.PVP) == State.DENY, "state flag set");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.FLAG, "sync_test", "greeting", "Hello"));
            h.assertTrue("Hello".equals(r.flag(Flags.GREETING)), "text flag set");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.FLAG, "sync_test", "heal-delay", "abc"));
            h.assertTrue(r.flag(Flags.HEAL_DELAY) == null, "a bad value is refused, not stored");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.FLAG, "sync_test", "teleport", "here"));
            FlagLocation tp = r.flag(Flags.TELEPORT);
            h.assertTrue(tp != null && (int) tp.x() == a.getX() && tp.dimension().equals(level.dimension()), "'here' takes the player's position");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.FLAG, "sync_test", "pvp", ""));
            h.assertTrue(r.flag(Flags.PVP) == null, "empty value clears");

            UUID friend = UUID.randomUUID();
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.ADD_MEMBER, "sync_test", friend.toString(), ""));
            h.assertTrue(r.isMember(friend) && !r.isOwner(friend), "member added by uuid");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.ADD_OWNER, "sync_test", friend.toString(), ""));
            h.assertTrue(r.isOwner(friend), "promoted to owner");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.REMOVE_OWNER, "sync_test", friend.toString(), ""));
            h.assertTrue(!r.isMember(friend), "removed");
            String unknown = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.ADD_MEMBER, "sync_test", "nobody_here_xyz", ""));
            h.assertTrue(unknown != null && unknown.contains("No player"), "unknown name reported");

            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.ADD_RANK, "sync_test", "VIP", ""));
            h.assertTrue(r.hasRank("vip"), "rank added, lower-cased");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.PRIORITY, "sync_test", "7", ""));
            h.assertTrue(r.priority() == 7, "priority set");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.RENAME, "sync_test", "Sync Test", ""));
            h.assertTrue("Sync Test".equals(r.name()), "renamed");

            Region parent = new Region("sync_parent", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(0, 0, 0)), h.absolutePos(new BlockPos(20, 8, 20))), RegionType.PROTECTED);
            store(h).add(parent);
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.PARENT, "sync_test", "sync_parent", ""));
            h.assertTrue("sync_parent".equals(r.parentId()), "parent set");
            String loop = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.PARENT, "sync_parent", "sync_test", ""));
            h.assertTrue(loop != null && loop.contains("loop") && parent.parentId() == null, "a parent loop is refused");

            String claim = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.TYPE, "sync_test", "claim", ""));
            h.assertTrue(r.type() == RegionType.PROTECTED && claim != null && claim.contains("PvP"), "claims are refused in a PvP world");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.TYPE, "sync_test", "mine", ""));
            h.assertTrue(r.type() == RegionType.MINE, "type set to mine");

            Selections.of(admin).setPos1(level.dimension(), h.absolutePos(new BlockPos(10, 1, 10)));
            Selections.of(admin).setPos2(level.dimension(), h.absolutePos(new BlockPos(12, 3, 12)));
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.REDEFINE, "sync_test", "", ""));
            h.assertTrue(r.bounds().sizeX() == 3, "redefined from the new selection");

            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.DELETE, "sync_test", "", ""));
            h.assertTrue(store(h).get("sync_test") == null, "deleted");
            h.assertTrue(RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.FLAG, "sync_test", "pvp", "deny")).contains("No region"), "acting on a missing region is reported");
        } finally {
            store(h).remove("sync_test");
            store(h).remove("sync_parent");
            Selections.forget(admin.getUUID());
            admin.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void players_only_receive_their_own_claims_and_plots_for_sale(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        Region secret = new Region("list_secret", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(3, 3, 3))), RegionType.PROTECTED);
        Region mine = new Region("list_mine", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(5, 1, 1)), h.absolutePos(new BlockPos(7, 3, 3))), RegionType.CLAIM);
        mine.addOwner(player.getUUID());
        Region sale = new Region("list_sale", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(9, 1, 1)), h.absolutePos(new BlockPos(11, 3, 3))), RegionType.PLOT_FOR_SALE);
        store(h).add(secret);
        store(h).add(mine);
        store(h).add(sale);
        try {
            List<String> ids = RegionSync.listFor(player).stream().map(RegionListPacket.Summary::id).toList();
            h.assertTrue(!RegionSync.mayEdit(player), "the mock player is not an admin");
            h.assertTrue(ids.contains("list_mine") && ids.contains("list_sale") && !ids.contains("list_secret"),
                    "a player sees their claim and the plot for sale, not the admin region: " + ids);
        } finally {
            store(h).remove("list_secret");
            store(h).remove("list_mine");
            store(h).remove("list_sale");
            player.discard();
        }
        h.succeed();
    }
}
