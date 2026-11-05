package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.claim.ClaimData;
import com.mrgregles.bsp_region_guard.claim.ClaimFlags;
import com.mrgregles.bsp_region_guard.claim.Claims;
import com.mrgregles.bsp_region_guard.claim.OwnerFlags;
import com.mrgregles.bsp_region_guard.command.RegionEdits;
import com.mrgregles.bsp_region_guard.flag.FlagContext;
import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.State;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Plot owner flags (D159 to D168): owners and members of a plot read their own lists, so a world default that denies
 * building stops visitors but not owners. These tests change the world default, so they run in their own batch.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class OwnerFlagGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    private static Region plot(GameTestHelper h, String id, RegionType type) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(6, 6, 6))), type);
        r.setPriority(1);
        store(h).add(r);
        return r;
    }

    private static boolean may(GameTestHelper h, ServerPlayer p, com.mrgregles.bsp_region_guard.flag.StateFlag flag) {
        return RegionQuery.allows(h.getLevel(), h.absolutePos(new BlockPos(3, 2, 3)), flag, p);
    }

    @GameTest(template = EMPTY, batch = "owner-flags")
    public void owners_build_under_a_world_that_denies_building(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Region global = store(h).global();
        State oldBuild = global.flag(Flags.BUILD);
        ServerPlayer owner = TestPlayers.mockServerPlayer(h), member = TestPlayers.mockServerPlayer(h), visitor = TestPlayers.mockServerPlayer(h);
        Region claim = plot(h, "of_claim", RegionType.CLAIM);
        try {
            global.setFlag(Flags.BUILD, State.DENY);
            store(h).touch(global);
            claim.addOwner(owner.getUUID());
            claim.addMember(member.getUUID());
            store(h).touch(claim);

            h.assertTrue(may(h, owner, Flags.BLOCK_BREAK) && may(h, owner, Flags.BLOCK_PLACE), "inherit on OWNERS: the owner builds although the world denies it (D161)");
            h.assertTrue(may(h, member, Flags.BLOCK_BREAK), "inherit on MEMBERS: same as owners");
            h.assertFalse(may(h, visitor, Flags.BLOCK_BREAK), "visitors get the world default's deny");

            // the admin denies lighters for owners: members are locked out too, and the owner can't change it
            h.assertTrue(RegionEdits.listFlag(store(h), claim, OwnerFlags.OWNERS, Flags.LIGHTER, "deny", new FlagContext(level, null)).ok(), "set on OWNERS");
            h.assertFalse(may(h, owner, Flags.LIGHTER) || may(h, member, Flags.LIGHTER), "denied for owners, so for members too");
            h.assertFalse(Claims.INSTANCE.setMemberFlag(owner, "of_claim", "lighter", "allow").ok(), "the owner can't open it for members (D162)");

            // the owner closes chests to members; the owner still opens them
            h.assertTrue(Claims.INSTANCE.setMemberFlag(owner, "of_claim", "chest-access", "deny").ok(), "the owner sets a member value");
            h.assertFalse(may(h, member, Flags.CHEST_ACCESS), "members may not open chests now");
            h.assertTrue(may(h, owner, Flags.CHEST_ACCESS), "the owner still may");
            h.assertFalse(Claims.INSTANCE.setMemberFlag(visitor, "of_claim", "build", "allow").ok(), "a visitor can't set member values");

            // visitors' list opens chests to strangers; that does not reach the owner's member choice
            claim.setFlag(Flags.CHEST_ACCESS, State.ALLOW);
            store(h).touch(claim);
            h.assertTrue(may(h, visitor, Flags.CHEST_ACCESS), "visitors may open chests");
            h.assertFalse(may(h, member, Flags.CHEST_ACCESS), "members follow the owner's choice, not the visitors' list");

            // members never get more than owners
            RegionEdits.listFlag(store(h), claim, OwnerFlags.OWNERS, Flags.BLOCK_PLACE, "deny", new FlagContext(level, null));
            OwnerFlags.set(claim, OwnerFlags.MEMBER_SET, Flags.BLOCK_PLACE, State.ALLOW);
            store(h).touch(claim);
            h.assertFalse(may(h, owner, Flags.BLOCK_PLACE) || may(h, member, Flags.BLOCK_PLACE), "an owners deny caps members");

            // a player flag outside the build family: inherit on OWNERS means same as visitors
            claim.setFlag(Flags.ITEM_DROP, State.DENY);
            store(h).touch(claim);
            h.assertFalse(may(h, owner, Flags.ITEM_DROP), "item-drop: owners follow visitors when not set");
            RegionEdits.listFlag(store(h), claim, OwnerFlags.OWNERS, Flags.ITEM_DROP, "allow", new FlagContext(level, null));
            h.assertTrue(may(h, owner, Flags.ITEM_DROP) && may(h, member, Flags.ITEM_DROP), "allowed for owners, members follow");
            h.assertFalse(may(h, visitor, Flags.ITEM_DROP), "visitors still may not");

            // giving the claim up (or a copy) drops the owner's member choices (D168)
            OwnerFlags.resetMembers(claim);
            h.assertTrue(OwnerFlags.values(claim, OwnerFlags.MEMBER_SET).isEmpty() && OwnerFlags.own(claim, OwnerFlags.OWNERS, Flags.LIGHTER) == State.DENY,
                    "member choices gone, the admin's lists kept");

            h.assertFalse(RegionEdits.listFlag(store(h), claim, OwnerFlags.OWNERS, Flags.FIRE_SPREAD, "deny", new FlagContext(level, null)).ok(),
                    "world events have one value (D164)");
            h.succeed();
        } finally {
            global.setFlag(Flags.BUILD, oldBuild);
            store(h).touch(global);
            store(h).remove("of_claim");
        }
    }

    /** A region as an older Region Guard saved it: without the owner flags mark, added the way storage or a backup adds it. */
    private static Region fromOldSave(GameTestHelper h, Region r) {
        net.minecraft.nbt.CompoundTag tag = r.save();
        tag.remove(Region.OWNER_FLAGS_FORMAT);
        Region old = Region.load(tag);
        store(h).add(old);
        return old;
    }

    @GameTest(template = EMPTY, batch = "owner-flags")
    public void old_plots_keep_their_build_denies_for_owners(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Region town = new Region("of_town", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(0, 0, 0)), h.absolutePos(new BlockPos(20, 10, 10))), RegionType.PROTECTED);
        town.setFlag(Flags.BLOCK_BREAK, State.DENY);
        store(h).add(town);
        try {
            Region sale = new Region("of_sale", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(6, 6, 6))), RegionType.PLOT_FOR_SALE);
            sale.setFlag(Flags.BLOCK_PLACE, State.DENY);
            sale.setFlag(Flags.PVP, State.DENY);
            sale.setParentId("of_town");
            Region claim = new Region("of_old", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(8, 1, 1)), h.absolutePos(new BlockPos(12, 6, 6))), RegionType.CLAIM);
            claim.typeData().putString(ClaimData.PLAYER_FLAGS, "chest-access");
            claim.setFlag(Flags.CHEST_ACCESS, State.DENY); // the buyer's choice, meant for strangers
            claim.setFlag(Flags.USE, State.DENY); // the admin's
            sale = fromOldSave(h, sale);
            claim = fromOldSave(h, claim);

            h.assertTrue(OwnerFlags.own(sale, OwnerFlags.OWNERS, Flags.BLOCK_PLACE) == State.DENY, "a plot for sale keeps its build deny for owners (D166)");
            h.assertTrue(OwnerFlags.own(sale, OwnerFlags.OWNERS, Flags.BLOCK_BREAK) == State.DENY, "and the deny it inherited from its town");
            h.assertTrue(OwnerFlags.own(sale, OwnerFlags.OWNERS, Flags.PVP) == null, "only build flags are copied");
            h.assertTrue(OwnerFlags.own(claim, OwnerFlags.OWNERS, Flags.USE) == State.DENY, "the admin's deny on a claim is copied");
            h.assertTrue(OwnerFlags.own(claim, OwnerFlags.OWNERS, Flags.CHEST_ACCESS) == null, "the buyer's own deny stays for strangers only");
            h.assertFalse(sale.beforeOwnerFlags() || OwnerFlags.migrate(store(h), sale), "it runs once");
            h.assertTrue(sale.save().contains(Region.OWNER_FLAGS_FORMAT), "saves carry the mark");

            // BUYER chips: taking the last one off leaves nothing, not the config's list
            RegionEdits.buyerFlag(store(h), claim, Flags.CHEST_ACCESS);
            h.assertTrue(ClaimFlags.allowedFor(store(h), claim).isEmpty(), "no flags for the buyer: " + ClaimFlags.allowedFor(store(h), claim));
            RegionEdits.buyerFlag(store(h), claim, Flags.USE);
            h.assertTrue(ClaimFlags.allowedFor(store(h), claim).equals(java.util.List.of("use")), "one chip on");

            // back on sale by the TYPE button: the old owner's member choices go (D168)
            OwnerFlags.set(claim, OwnerFlags.MEMBER_SET, Flags.BUILD, State.DENY);
            com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(true); // the test world is a PvP world
            h.assertTrue(RegionEdits.type(level, store(h), claim, "plot_for_sale").ok(), "back on sale");
            h.assertTrue(OwnerFlags.values(claim, OwnerFlags.MEMBER_SET).isEmpty(), "member choices reset when set back to a plot for sale");
            h.succeed();
        } finally {
            store(h).remove("of_sale");
            store(h).remove("of_old");
            store(h).remove("of_town");
            com.mrgregles.bsp_region_guard.RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(false);
        }
    }

    @GameTest(template = EMPTY, batch = "owner-flags")
    public void owner_choices_and_neighbours_still_count(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer owner = TestPlayers.mockServerPlayer(h), member = TestPlayers.mockServerPlayer(h);
        Region claim = plot(h, "of_claim2", RegionType.CLAIM);
        Region nobuild = new Region("of_nobuild", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(3, 1, 3)), h.absolutePos(new BlockPos(4, 6, 4))), RegionType.PROTECTED);
        try {
            claim.addOwner(owner.getUUID());
            claim.addMember(member.getUUID());
            // the admin starts members with block-break allowed; the owner switches "members may build" off
            OwnerFlags.set(claim, OwnerFlags.MEMBERS, Flags.BLOCK_BREAK, State.ALLOW);
            store(h).touch(claim);
            h.assertTrue(Claims.INSTANCE.setMemberFlag(owner, "of_claim2", "build", "no").ok(), "yes/no work like allow/deny");
            h.assertFalse(may(h, member, Flags.BLOCK_BREAK), "the owner's build off beats the admin's block-break start");
            h.assertTrue(may(h, owner, Flags.BLOCK_BREAK), "the owner still builds");

            // a protected region at the plot's priority that denies building still stops the owner, as before
            nobuild.setPriority(1);
            nobuild.setFlag(Flags.BUILD, State.DENY);
            store(h).add(nobuild);
            h.assertFalse(may(h, owner, Flags.BLOCK_BREAK), "a same-priority deny stops owners");
            nobuild.setPriority(0);
            store(h).touch(nobuild);
            h.assertTrue(may(h, owner, Flags.BLOCK_BREAK), "a lower-priority one doesn't");
            h.succeed();
        } finally {
            store(h).remove("of_claim2");
            store(h).remove("of_nobuild");
        }
    }
}
