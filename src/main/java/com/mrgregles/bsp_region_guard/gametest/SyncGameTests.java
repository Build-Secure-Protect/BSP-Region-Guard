package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.flag.FlagLocation;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.network.RegionActionPacket;
import com.mrgregles.bsp_region_guard.network.RegionDetailPacket;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import com.mrgregles.bsp_region_guard.network.RegionSync;
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

        RegionListPacket remote = new RegionListPacket(ResourceLocation.parse("minecraft:overworld"), List.of(), "hub", false, "survival-2", List.of("survival-1", "survival-2"));
        buf = new FriendlyByteBuf(Unpooled.buffer());
        RegionListPacket.encode(remote, buf);
        h.assertTrue(RegionListPacket.decode(buf).equals(remote), "list packet with the server switcher's fields");

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

    @GameTest(template = EMPTY)
    public void screen_trigger_actions_add_and_remove(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
        admin.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        Region r = new Region("sync_trig", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(5, 5, 5))), RegionType.PROTECTED);
        store(h).add(r);
        try {
            String added = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.TRIGGER_ADD, "sync_trig", "enter", "here Welcome in"));
            var triggers = com.mrgregles.bsp_region_guard.trigger.Trigger.of(r.typeData());
            h.assertTrue(triggers.size() == 1 && (int) triggers.get(0).x() == at.getX() && "Welcome in".equals(triggers.get(0).message()), "an enter trigger at 'here' with a message: " + added);
            String bad = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.TRIGGER_ADD, "sync_trig", "timer", "here"));
            h.assertTrue(bad != null && bad.contains("seconds") && com.mrgregles.bsp_region_guard.trigger.Trigger.of(r.typeData()).size() == 1, "a timer without seconds is refused");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.TRIGGER_ADD, "sync_trig", "timer", "30 lobby/minecraft:overworld 0 64 0"));
            triggers = com.mrgregles.bsp_region_guard.trigger.Trigger.of(r.typeData());
            h.assertTrue(triggers.size() == 2 && "lobby".equals(triggers.get(1).server()) && triggers.get(1).intervalSeconds() == 30, "a cross-server timer trigger");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.TRIGGER_REMOVE, "sync_trig", "1", ""));
            triggers = com.mrgregles.bsp_region_guard.trigger.Trigger.of(r.typeData());
            h.assertTrue(triggers.size() == 1 && "lobby".equals(triggers.get(0).server()), "removing the first leaves the timer");
        } finally {
            store(h).remove("sync_trig");
            admin.discard();
        }
        h.succeed();
    }

    /** T9.3: the screen and the commands go through {@code RegionEdits}, so they follow the same rules. */
    @GameTest(template = EMPTY)
    public void screen_and_commands_share_the_edit_rules(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        RegionStore store = store(h);
        com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(true);
        Region plot = new Region("edit_plot", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(4, 4, 4))), RegionType.PROTECTED);
        Region mine = new Region("edit_mine", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(6, 1, 6)), h.absolutePos(new BlockPos(9, 4, 9))), RegionType.PROTECTED);
        store.add(plot);
        store.add(mine);
        try {
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.SET_DATA, "edit_plot", "price", "100"));
            h.assertTrue(plot.type() == RegionType.PLOT_FOR_SALE, "a price puts it up for sale");
            var offSale = com.mrgregles.bsp_region_guard.command.RegionEdits.sale(level, store, plot, 0);
            h.assertTrue(offSale.ok() && plot.type() == RegionType.PROTECTED, "price 0 takes it off sale (the command used to leave it for sale without a price)");

            com.mrgregles.bsp_region_guard.command.RegionEdits.makeMine(store, mine);
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.SET_DATA, "edit_mine", "price", "0"));
            h.assertTrue(mine.type() == RegionType.MINE, "price 0 on something not for sale leaves its type alone (the screen used to make it protected)");

            plot.setType(RegionType.CLAIM);
            var open = com.mrgregles.bsp_region_guard.command.RegionEdits.open(level, store, plot, 5);
            h.assertTrue(!open.ok() && plot.type() == RegionType.CLAIM, "an owned claim cannot be opened for buying (the command used to allow it): " + open.message());

            String refused = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.PRIORITY, Region.GLOBAL_ID, "5", ""));
            h.assertTrue(refused != null && store.global().priority() != 5, "the world default's priority stays the lowest (the screen used to change it)");

            String notMine = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.MINE_INTERVAL, "edit_plot", "60", ""));
            h.assertTrue(notMine != null && notMine.contains("not a mine"), "mine settings need a mine, from the screen too: " + notMine);
        } finally {
            com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(false);
            store.remove("edit_plot");
            store.remove("edit_mine");
            admin.discard();
        }
        h.succeed();
    }

    /** D44: the server switcher shows another server's regions from the shared storage, read-only. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public void another_servers_regions_are_shown_read_only(GameTestHelper h) throws java.io.IOException {
        ServerLevel level = h.getLevel();
        String dim = level.dimension().location().toString();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        var backend = new com.mrgregles.bsp_region_guard.storage.MemoryBackend();
        var alpha = com.mrgregles.bsp_region_guard.storage.RegionMirror.startWith(level.getServer(), backend, "alpha");
        com.mrgregles.bsp_region_guard.storage.RegionMirror.setActive(alpha);
        // a region "rv_hub" here, and beta's own "rv_hub" with other flags, as beta stored it
        Region local = new Region("rv_hub", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(4, 4, 4))), RegionType.PROTECTED);
        store(h).add(local);
        Region theirs = new Region("rv_hub", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(9, 4, 9))), RegionType.PROTECTED);
        theirs.setFlag(Flags.PVP, State.DENY);
        backend.upsertRegion(new com.mrgregles.bsp_region_guard.storage.MirrorBackend.RegionRow("beta", dim, "rv_hub", 1, 1,
                com.mrgregles.bsp_region_guard.storage.RegionMirror.serialize(theirs), false));
        com.mrgregles.bsp_region_guard.protection.EdgeLinks.setNetwork(List.of(new com.mrgregles.bsp_region_guard.protection.EdgeLinks.World("beta", dim, null, Map.of())));
        alpha.drain();
        h.assertTrue(com.mrgregles.bsp_region_guard.network.RemoteViews.servers().equals(List.of("alpha", "beta")), "the picker offers this server first, then beta");
        RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.VIEW_SERVER, "", "beta", ""));
        alpha.drain(); // the read happens on the storage worker; the answer comes back on the next tick
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(com.mrgregles.bsp_region_guard.network.RemoteViews.of(admin) != null, "now looking at beta"))
                .thenExecute(() -> {
                    var view = com.mrgregles.bsp_region_guard.network.RemoteViews.of(admin);
                    h.assertTrue(view.server().equals("beta") && view.regions().get("rv_hub").flag(Flags.PVP) == State.DENY, "beta's own region, with beta's flags");
                    h.assertTrue(RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.SELECT, "rv_hub", "", "")) == null, "selecting reads it");
                    String refused = RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.FLAG, "rv_hub", "pvp", "allow"));
                    h.assertTrue(refused != null && refused.contains("Read-only"), "every edit is refused while looking: " + refused);
                    h.assertTrue(local.flag(Flags.PVP) == null && !theirs.flags().isEmpty(), "and this server's own rv_hub was not touched");
                    RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.VIEW_SERVER, "", "", ""));
                    h.assertTrue(com.mrgregles.bsp_region_guard.network.RemoteViews.of(admin) == null, "BACK returns to this server");
                    h.assertTrue(RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.FLAG, "rv_hub", "pvp", "deny")) == null
                            && local.flag(Flags.PVP) == State.DENY, "and editing works again");
                })
                .thenExecute(() -> {
                    store(h).remove("rv_hub");
                    com.mrgregles.bsp_region_guard.network.RemoteViews.clearAll();
                    com.mrgregles.bsp_region_guard.protection.EdgeLinks.clearAll();
                    com.mrgregles.bsp_region_guard.storage.RegionMirror.setActive(null);
                    admin.discard();
                })
                .thenSucceed();
    }

    /** D45: the screen's wipe goes through ClaimWipe's two steps, code and all, and RESTORE puts the claims back. */
    @GameTest(template = EMPTY, batch = "wipe-screen") // a wipe removes every claim in the world: never beside other tests
    public void the_screen_wipes_claims_with_the_code_and_restores_them(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        var previousAdmins = com.mrgregles.bsp_region_guard.compat.BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        Region claim = new Region("sw_claim", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(3, 3, 3))), RegionType.CLAIM);
        claim.addOwner(admin.getUUID());
        store(h).add(claim);
        try {
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.WIPE_REQUEST, "", "", ""));
            var st = com.mrgregles.bsp_region_guard.claim.ClaimWipe.state(admin, level);
            h.assertTrue(st.code().length() == 4 && st.secondsLeft() > 50 && st.claims() >= 1, "asking gives a code for a minute: " + st);
            String wrong = st.code().equals("0000") ? "1111" : "0000";
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.WIPE_CONFIRM, "", wrong, ""));
            h.assertTrue(store(h).has("sw_claim"), "a wrong code wipes nothing");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.WIPE_CONFIRM, "", st.code(), ""));
            h.assertTrue(!store(h).has("sw_claim"), "the right code wipes the claims");
            var after = com.mrgregles.bsp_region_guard.claim.ClaimWipe.state(admin, level);
            h.assertTrue(after.code().isEmpty() && !after.backups().isEmpty(), "the code is used up and a backup exists");
            RegionSync.apply(admin, new RegionActionPacket(RegionActionPacket.WIPE_RESTORE, "", after.backups().get(0), ""));
            h.assertTrue(store(h).has("sw_claim") && store(h).get("sw_claim").isOwner(admin.getUUID()), "RESTORE brings the claim back with its owner");
        } finally {
            store(h).remove("sw_claim");
            com.mrgregles.bsp_region_guard.compat.BspCore.setAdminNames(new java.util.ArrayList<>(previousAdmins));
            admin.discard();
        }
        h.succeed();
    }

    /** D50: screens are drawn twice as big when the window has room, as large as fits when not, never smaller than before. */
    @GameTest(template = "empty")
    public void screens_are_drawn_larger_but_fit_the_window(GameTestHelper h) {
        h.assertTrue(com.mrgregles.bsp_region_guard.RegionGuardClientConfig.screenScale(2.0, 960, 540, 420, 236, 8) == 2f, "room to spare: twice as big");
        float fit = com.mrgregles.bsp_region_guard.RegionGuardClientConfig.screenScale(2.0, 682, 511, 420, 236, 8);
        h.assertTrue(Math.abs(fit - (682 - 8) / 420f) < 0.001, "a narrower window: as wide as fits, " + fit);
        h.assertTrue(com.mrgregles.bsp_region_guard.RegionGuardClientConfig.screenScale(2.0, 300, 200, 420, 236, 8) == 1f, "a tiny window: never below the old size");
        h.assertTrue(com.mrgregles.bsp_region_guard.RegionGuardClientConfig.screenScale(1.0, 1920, 1080, 420, 236, 8) == 1f, "ui.scale 1: the old size");
        h.succeed();
    }
}
