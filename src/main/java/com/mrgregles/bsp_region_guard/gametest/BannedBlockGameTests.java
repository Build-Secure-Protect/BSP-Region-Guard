package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.protection.BannedBlocks;
import com.mrgregles.bsp_region_guard.protection.MovementTracker;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Banned blocks per region (D51 to D56): matching, the lists adding up, place/use/break, carrying, pickup, containers. */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class BannedBlockGameTests {
    private static final String EMPTY = "empty";
    private static final BlockPos OUT = new BlockPos(12, 2, 5), NEAR = new BlockPos(10, 2, 5), IN = new BlockPos(8, 2, 5), DEEP = new BlockPos(5, 2, 5);

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    private static Region define(GameTestHelper h, String id, BlockPos from, BlockPos to) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(from), h.absolutePos(to)), RegionType.PROTECTED);
        store(h).add(r);
        return r;
    }

    private static Region define(GameTestHelper h, String id) {
        return define(h, id, new BlockPos(2, 1, 2), new BlockPos(9, 8, 9));
    }

    private static void finish(GameTestHelper h, Runnable body, List<? extends Player> players, String... ids) {
        try {
            body.run();
        } finally {
            for (String id : ids) {
                store(h).remove(id);
            }
            MovementTracker.clearAll();
            players.forEach(Player::discard);
        }
        h.succeed();
    }

    private static void moveTo(GameTestHelper h, ServerPlayer p, BlockPos rel) {
        BlockPos a = h.absolutePos(rel);
        p.setPos(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
    }

    private static boolean standsAt(GameTestHelper h, ServerPlayer p, BlockPos rel) {
        return p.blockPosition().equals(h.absolutePos(rel));
    }

    /** Walks a player from outside up to the region and one step in; returns the tick counter. */
    private static long walkIn(GameTestHelper h, ServerPlayer p, long t) {
        for (BlockPos step : new BlockPos[]{OUT, NEAR, IN}) {
            moveTo(h, p, step);
            MovementTracker.tick(p, t++);
        }
        return t;
    }

    private static ItemStack shulkerWith(ItemStack inside) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        CompoundTag slot = inside.save(new CompoundTag());
        slot.putByte("Slot", (byte) 0);
        ListTag items = new ListTag();
        items.add(slot);
        CompoundTag be = new CompoundTag();
        be.put("Items", items);
        box.getOrCreateTag().put("BlockEntityTag", be);
        return box;
    }

    @GameTest(template = EMPTY)
    public void entries_match_ids_whole_mods_and_tags(GameTestHelper h) {
        Map<String, String> tnt = Map.of("minecraft:tnt", "r");
        h.assertTrue(BannedBlocks.match(tnt, new ItemStack(Items.TNT)) != null, "an exact id matches the item");
        h.assertTrue(BannedBlocks.match(tnt, Blocks.TNT.defaultBlockState()) != null, "and the block");
        h.assertTrue(BannedBlocks.match(tnt, new ItemStack(Items.STONE)) == null, "and nothing else");
        h.assertTrue(BannedBlocks.match(Map.of("@minecraft", "r"), new ItemStack(Items.STONE)) != null, "@mod bans a whole mod");
        h.assertTrue(BannedBlocks.match(Map.of("minecraft:*", "r"), new ItemStack(Items.STONE)) != null, "so does mod:*");
        h.assertTrue(BannedBlocks.match(Map.of("@create", "r"), new ItemStack(Items.STONE)) == null, "another mod's name does not");
        Map<String, String> beds = Map.of("#minecraft:beds", "r");
        h.assertTrue(BannedBlocks.match(beds, new ItemStack(Items.RED_BED)) != null, "a tag matches the item");
        h.assertTrue(BannedBlocks.match(beds, Blocks.BLUE_BED.defaultBlockState()) != null, "and the block");
        h.assertTrue(BannedBlocks.match(Map.of("minecraft:torch", "r"), Blocks.WALL_TORCH.defaultBlockState()) != null,
                "banning the torch item also bans the wall torch it places");
        h.assertTrue(BannedBlocks.bannedIn(tnt, shulkerWith(new ItemStack(Items.TNT, 3))) != null, "TNT packed in a shulker box is found");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void bans_add_up_across_parents_and_carry_counts_for_every_rule(GameTestHelper h) {
        finish(h, () -> {
            Region parent = define(h, "bb_parent");
            parent.setFlag(Flags.DENY_CARRY, Set.of("minecraft:tnt"));
            store(h).touch(parent);
            Region child = define(h, "bb_child", new BlockPos(4, 1, 4), new BlockPos(7, 6, 7));
            child.setParentId("bb_parent");
            child.setPriority(5);
            child.setFlag(Flags.DENY_PLACE, Set.of("@create"));
            store(h).touch(child);
            ServerLevel level = h.getLevel();
            BlockPos spot = h.absolutePos(new BlockPos(5, 2, 5));
            var place = BannedBlocks.entries(level, spot, BannedBlocks.Rule.PLACE);
            h.assertTrue(place.containsKey("@create") && place.containsKey("minecraft:tnt"), "the child's own ban and the parent's carry ban both apply: " + place);
            var carry = BannedBlocks.entries(level, spot, BannedBlocks.Rule.CARRY);
            h.assertTrue(carry.keySet().equals(Set.of("minecraft:tnt")) && "bb_parent".equals(carry.get("minecraft:tnt")), "only CARRY lists carry, named by their region: " + carry);
            h.assertTrue(BannedBlocks.entries(level, h.absolutePos(new BlockPos(12, 2, 12)), BannedBlocks.Rule.PLACE).isEmpty(), "nothing outside");
        }, List.of(), "bb_child", "bb_parent");
    }

    @GameTest(template = EMPTY)
    public void banned_blocks_are_refused_to_members_but_not_to_admins(GameTestHelper h) {
        ServerPlayer member = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        finish(h, () -> {
            Region r = define(h, "bb_rules");
            r.addMember(member.getUUID());
            r.setFlag(Flags.DENY_PLACE, Set.of("minecraft:tnt"));
            r.setFlag(Flags.DENY_BREAK, Set.of("minecraft:bookshelf"));
            r.setFlag(Flags.DENY_USE, Set.of("minecraft:flint_and_steel", "minecraft:lever"));
            store(h).touch(r);
            ServerLevel level = h.getLevel();
            BlockPos at = h.absolutePos(DEEP);

            level.setBlock(at, Blocks.TNT.defaultBlockState(), 3);
            var placeTnt = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, at), Blocks.STONE.defaultBlockState(), member);
            MinecraftForge.EVENT_BUS.post(placeTnt);
            h.assertTrue(placeTnt.isCanceled(), "a member may not place banned TNT");
            level.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
            var placeStone = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, at), Blocks.STONE.defaultBlockState(), member);
            MinecraftForge.EVENT_BUS.post(placeStone);
            h.assertFalse(placeStone.isCanceled(), "but may place stone");

            level.setBlock(at, Blocks.BOOKSHELF.defaultBlockState(), 3);
            var breakShelf = new BlockEvent.BreakEvent(level, at, level.getBlockState(at), member);
            MinecraftForge.EVENT_BUS.post(breakShelf);
            h.assertTrue(breakShelf.isCanceled(), "a member may not break a banned bookshelf");

            h.assertTrue(BannedBlocks.refuses(level, at, BannedBlocks.Rule.USE, null, new ItemStack(Items.FLINT_AND_STEEL), member), "nor use banned flint and steel");
            h.assertTrue(BannedBlocks.refuses(level, at, BannedBlocks.Rule.USE, Blocks.LEVER.defaultBlockState(), null, member), "nor a banned lever");
            h.assertFalse(BannedBlocks.refuses(level, at, BannedBlocks.Rule.USE, null, new ItemStack(Items.STICK), member), "a stick is fine");
            h.assertFalse(BannedBlocks.refuses(level, at, BannedBlocks.Rule.PLACE, Blocks.TNT.defaultBlockState(), null, admin), "an admin with bypass may place TNT");
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        }, List.of(member, admin), "bb_rules");
        BspCore.setAdminNames(new ArrayList<>(previousAdmins));
    }

    @GameTest(template = EMPTY)
    public void carrying_a_banned_item_keeps_everyone_out_until_it_is_gone(GameTestHelper h) {
        ServerPlayer member = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        finish(h, () -> {
            Region r = define(h, "bb_carry");
            r.addMember(member.getUUID());
            r.setFlag(Flags.DENY_CARRY, Set.of("minecraft:tnt"));
            store(h).touch(r);
            ServerLevel level = h.getLevel();
            long t = 1;

            member.getInventory().add(new ItemStack(Items.TNT, 4));
            t = walkIn(h, member, t);
            h.assertTrue(standsAt(h, member, NEAR), "a member carrying TNT is put back at the edge");
            h.assertFalse(RegionQuery.allows(level, h.absolutePos(DEEP), Flags.TELEPORT_IN, member), "and may not teleport in");

            member.getInventory().clearContent();
            member.getInventory().add(shulkerWith(new ItemStack(Items.TNT)));
            t = walkIn(h, member, t);
            h.assertTrue(standsAt(h, member, NEAR), "TNT packed in a shulker box counts too");

            member.getInventory().clearContent();
            t = walkIn(h, member, t);
            h.assertTrue(standsAt(h, member, IN), "with the TNT gone they walk in");
            h.assertTrue(RegionQuery.allows(level, h.absolutePos(DEEP), Flags.TELEPORT_IN, member), "and may teleport in");

            admin.getInventory().add(new ItemStack(Items.TNT));
            walkIn(h, admin, t);
            h.assertTrue(standsAt(h, admin, IN), "an admin with bypass walks in carrying TNT");
        }, List.of(member, admin), "bb_carry");
        BspCore.setAdminNames(new ArrayList<>(previousAdmins));
    }

    @GameTest(template = EMPTY)
    public void inside_banned_items_cannot_be_picked_up_and_holders_are_moved_out(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        finish(h, () -> {
            Region r = define(h, "bb_inside");
            r.setFlag(Flags.DENY_CARRY, Set.of("minecraft:tnt"));
            store(h).touch(r);
            ServerLevel level = h.getLevel();
            moveTo(h, p, DEEP);
            BlockPos deep = h.absolutePos(DEEP);
            ItemEntity lying = new ItemEntity(level, deep.getX() + 0.5, deep.getY(), deep.getZ() + 0.5, new ItemStack(Items.TNT));
            var pickup = new EntityItemPickupEvent(p, lying);
            MinecraftForge.EVENT_BUS.post(pickup);
            h.assertTrue(pickup.isCanceled(), "TNT lying inside cannot be picked up");
            ItemEntity dirt = new ItemEntity(level, deep.getX() + 0.5, deep.getY(), deep.getZ() + 0.5, new ItemStack(Items.DIRT));
            var pickDirt = new EntityItemPickupEvent(p, dirt);
            MinecraftForge.EVENT_BUS.post(pickDirt);
            h.assertFalse(pickDirt.isCanceled(), "dirt can");

            // already inside with TNT (logged in there, or given it): the next check moves them out
            long t = 20 - (p.getId() % 20); // a tick on which this player is checked
            MovementTracker.tick(p, t - 1); // inside, empty-handed: nothing happens
            h.assertTrue(standsAt(h, p, DEEP), "an empty-handed player stays inside");
            p.getInventory().add(new ItemStack(Items.TNT));
            MovementTracker.tick(p, t);
            Cuboid box = r.bounds();
            h.assertFalse(box.contains(p.blockPosition()), "a player holding TNT inside is moved out: " + p.blockPosition());
            dirt.discard();
            lying.discard();
        }, List.of(p), "bb_inside");
    }

    @GameTest(template = EMPTY)
    public void the_ender_chest_will_not_hand_out_banned_items_inside(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        finish(h, () -> {
            Region r = define(h, "bb_ender");
            r.setFlag(Flags.DENY_CARRY, Set.of("minecraft:tnt"));
            store(h).touch(r);
            moveTo(h, p, DEEP);
            var ender = p.getEnderChestInventory();
            ender.setItem(0, new ItemStack(Items.TNT, 8));
            ender.setItem(1, new ItemStack(Items.DIRT, 8));
            ChestMenu menu = ChestMenu.threeRows(7, p.getInventory(), ender);
            p.containerMenu = menu;
            menu.clicked(0, 0, ClickType.PICKUP, p);
            h.assertTrue(menu.getCarried().isEmpty() && ender.getItem(0).is(Items.TNT), "clicking the TNT takes nothing");
            menu.clicked(0, 0, ClickType.QUICK_MOVE, p);
            h.assertTrue(ender.getItem(0).getCount() == 8 && !p.getInventory().contains(new ItemStack(Items.TNT)), "nor does shift-clicking it");
            menu.clicked(1, 0, ClickType.PICKUP, p);
            h.assertTrue(menu.getCarried().is(Items.DIRT), "dirt comes out as usual");
            menu.setCarried(ItemStack.EMPTY);
            p.containerMenu = p.inventoryMenu;
            ender.clearContent();

            // outside the region the TNT comes out
            moveTo(h, p, OUT);
            ender.setItem(0, new ItemStack(Items.TNT));
            ChestMenu outside = ChestMenu.threeRows(8, p.getInventory(), ender);
            p.containerMenu = outside;
            outside.clicked(0, 0, ClickType.PICKUP, p);
            h.assertTrue(outside.getCarried().is(Items.TNT), "outside the region the ender chest hands it out");
            outside.setCarried(ItemStack.EMPTY);
            p.containerMenu = p.inventoryMenu;
            ender.clearContent();
        }, List.of(p), "bb_ender");
    }

    /** The editor's search (D56): @mod as in JEI, otherwise names and ids. */
    @GameTest(template = EMPTY)
    public void the_editor_search_understands_at_mod(GameTestHelper h) {
        var tnt = new net.minecraft.resources.ResourceLocation("minecraft", "tnt");
        var drill = new net.minecraft.resources.ResourceLocation("create", "mechanical_drill");
        h.assertTrue(BannedBlocks.searchMatches("@create", drill, "Mechanical Drill"), "@create lists Create's blocks");
        h.assertFalse(BannedBlocks.searchMatches("@create", tnt, "TNT"), "and nothing else");
        h.assertTrue(BannedBlocks.searchMatches("@cre", drill, "Mechanical Drill"), "a mod name can be typed in part");
        h.assertTrue(BannedBlocks.searchMatches("@create drill", drill, "Mechanical Drill"), "@mod then words narrows it");
        h.assertFalse(BannedBlocks.searchMatches("@create saw", drill, "Mechanical Drill"), "and the words must match");
        h.assertTrue(BannedBlocks.searchMatches("tn", tnt, "TNT"), "plain words match the name");
        h.assertTrue(BannedBlocks.searchMatches("minecraft:tnt", tnt, "TNT"), "or the id");
        h.assertTrue(BannedBlocks.searchMatches("  ", tnt, "TNT"), "an empty search lists everything");
        h.succeed();
    }

    /** What the editor sends: each rule's whole list through the screen's flag action, and an empty list to clear it. */
    @GameTest(template = EMPTY)
    public void the_editor_saves_lists_through_the_flag_action(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        finish(h, () -> {
            Region r = define(h, "bb_editor");
            com.mrgregles.bsp_region_guard.network.RegionSync.apply(admin, new com.mrgregles.bsp_region_guard.network.RegionActionPacket(
                    com.mrgregles.bsp_region_guard.network.RegionActionPacket.FLAG, "bb_editor", "deny-carry", "minecraft:tnt,@create"));
            h.assertTrue(Set.of("minecraft:tnt", "@create").equals(r.flag(Flags.DENY_CARRY)), "a list is stored: " + r.flag(Flags.DENY_CARRY));
            com.mrgregles.bsp_region_guard.network.RegionSync.apply(admin, new com.mrgregles.bsp_region_guard.network.RegionActionPacket(
                    com.mrgregles.bsp_region_guard.network.RegionActionPacket.FLAG, "bb_editor", "deny-place", "#minecraft:beds"));
            h.assertTrue(Set.of("#minecraft:beds").equals(r.flag(Flags.DENY_PLACE)), "tags too");
            com.mrgregles.bsp_region_guard.network.RegionSync.apply(admin, new com.mrgregles.bsp_region_guard.network.RegionActionPacket(
                    com.mrgregles.bsp_region_guard.network.RegionActionPacket.FLAG, "bb_editor", "deny-carry", ""));
            h.assertTrue(r.flag(Flags.DENY_CARRY) == null, "an empty list clears the rule");
            h.assertTrue(BannedBlocks.entries(h.getLevel(), h.absolutePos(DEEP), BannedBlocks.Rule.PLACE).containsKey("#minecraft:beds"), "and what is left still applies");
        }, List.of(admin), "bb_editor");
        BspCore.setAdminNames(new ArrayList<>(previousAdmins));
    }

    /** The pop-up (D54): what the player carries and where, carried intact to the client, more than six counted. */
    @GameTest(template = EMPTY)
    public void the_popup_shows_what_is_carried_and_where(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        finish(h, () -> {
            Region r = define(h, "bb_popup");
            r.setFlag(Flags.DENY_CARRY, Set.of("minecraft:tnt", "minecraft:lava_bucket"));
            store(h).touch(r);
            p.getInventory().add(new ItemStack(Items.TNT, 12));
            p.getInventory().add(shulkerWith(new ItemStack(Items.LAVA_BUCKET)));
            var carried = BannedBlocks.carried(h.getLevel(), h.absolutePos(DEEP), p);
            var popup = BannedBlocks.popup(carried);
            h.assertTrue("bb_popup".equals(popup.regionId()) && popup.items().size() == 2, "two banned things, named by their region: " + popup);
            h.assertTrue(popup.items().get(0).is(Items.TNT) && "x 12".equals(popup.where().get(0)), "the TNT with its count: " + popup.where());
            h.assertTrue(popup.items().get(1).is(Items.LAVA_BUCKET) && popup.where().get(1).startsWith("in "), "the lava bucket inside the box: " + popup.where());

            var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            com.mrgregles.bsp_region_guard.network.BannedPopupPacket.encode(popup, buf);
            var back = com.mrgregles.bsp_region_guard.network.BannedPopupPacket.decode(buf);
            h.assertTrue(back.regionId().equals("bb_popup") && back.items().get(0).is(Items.TNT) && back.items().get(0).getCount() == 12
                    && back.where().equals(popup.where()), "the packet carries it intact");

            List<ItemStack> many = new ArrayList<>();
            List<String> where = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                many.add(new ItemStack(Items.TNT));
                where.add("x 1");
            }
            var buf2 = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            com.mrgregles.bsp_region_guard.network.BannedPopupPacket.encode(new com.mrgregles.bsp_region_guard.network.BannedPopupPacket("x", many, where), buf2);
            var big = com.mrgregles.bsp_region_guard.network.BannedPopupPacket.decode(buf2);
            h.assertTrue(big.items().size() == 8 && big.items().stream().filter(s -> !s.isEmpty()).count() == 6, "six are shown, the rest counted");
        }, List.of(p), "bb_popup");
    }
}
