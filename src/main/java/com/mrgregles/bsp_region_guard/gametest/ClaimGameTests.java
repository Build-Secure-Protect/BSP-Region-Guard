package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.ClaimResult;
import com.mrgregles.bsp_region_guard.claim.ClaimData;
import com.mrgregles.bsp_region_guard.claim.ClaimWipe;
import com.mrgregles.bsp_region_guard.claim.Claims;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The claim rules. The test world is a PvP world, so each test switches {@code claims.allowInPvpWorlds} on and off
 * again. Money comes from BSP Core's bank (D31): the player is funded with {@code fund}; on a single server the bank
 * answers at once, and {@code BspCore.holdChargeAnswers} plays the network's delay.
 */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class ClaimGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    private static Cuboid ground(GameTestHelper h, int x1, int z1, int x2, int z2) {
        BlockPos a = h.absolutePos(new BlockPos(x1, 0, z1)), b = h.absolutePos(new BlockPos(x2, 0, z2));
        return new Cuboid(a.getX(), 0, a.getZ(), b.getX(), 0, b.getZ());
    }

    private static void fund(ServerPlayer player, long pieces) {
        BspCore.credit(player.server, player.getUUID(), pieces, "test", ok -> {
        });
    }

    /** Runs a purchase and returns its answer, which on a single server comes at once. */
    private static ClaimResult answer(GameTestHelper h, Consumer<Consumer<ClaimResult>> call) {
        AtomicReference<ClaimResult> out = new AtomicReference<>();
        call.accept(out::set);
        h.assertTrue(out.get() != null, "the bank answers at once on a single server");
        return out.get();
    }

    private static void allowClaims(boolean on) {
        RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(on);
    }

    @GameTest(template = EMPTY)
    public void a_plot_is_bought_and_given_back(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer buyer = TestPlayers.mockServerPlayer(h);
        allowClaims(true);
        Region plot = new Region("cl_plot", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(8, 8, 8))), RegionType.PLOT_FOR_SALE);
        ClaimData.set(plot, ClaimData.PRICE, 100);
        store(h).add(plot);
        try {
            ClaimResult poor = Claims.INSTANCE.quotePlot(buyer, "cl_plot");
            h.assertTrue(!poor.ok() && poor.price() == 100, "a quote without money fails but names the price");
            h.assertFalse(answer(h, then -> Claims.INSTANCE.purchasePlot(buyer, "cl_plot", then)).ok(), "cannot buy without money");
            fund(buyer, 150);
            h.assertTrue(BspCore.balance(buyer) == 150, "funded with 150 pieces");
            ClaimResult bought = answer(h, then -> Claims.INSTANCE.purchasePlot(buyer, "cl_plot", then));
            h.assertTrue(bought.ok() && "cl_plot".equals(bought.claimId()), "bought: " + bought.message());
            h.assertTrue(BspCore.balance(buyer) == 50, "100 pieces were taken from the bank");
            h.assertTrue(plot.type() == RegionType.CLAIM && plot.isOwner(buyer.getUUID()) && ClaimData.boughtFor(plot) == 100, "the plot is the buyer's claim");
            h.assertTrue(Claims.INSTANCE.claimsOf(level, buyer.getUUID()).contains("cl_plot"), "listed among the buyer's claims");
            h.assertFalse(Claims.INSTANCE.quotePlot(buyer, "cl_plot").ok(), "an owned claim is not for sale");

            ClaimResult gone = Claims.INSTANCE.abandon(buyer, "cl_plot");
            h.assertTrue(gone.ok() && gone.price() == 50, "abandoned with half back: " + gone.message());
            h.assertTrue(BspCore.balance(buyer) == 100, "refund paid into the bank");
            h.assertTrue(plot.type() == RegionType.PLOT_FOR_SALE && ClaimData.price(plot) == 100 && plot.owners().isEmpty(), "an admin plot goes back on sale at its original price");
        } finally {
            allowClaims(false);
            store(h).remove("cl_plot");
            buyer.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void open_area_rules_size_overlap_limit_enlarge(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer buyer = TestPlayers.mockServerPlayer(h);
        allowClaims(true);
        int oldLimit = RegionGuardConfig.CLAIM_MAX_PER_PLAYER.get();
        RegionGuardConfig.CLAIM_MAX_PER_PLAYER.set(1);
        Region open = new Region("cl_open", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(0, 1, 0)), h.absolutePos(new BlockPos(23, 8, 23))), RegionType.OPEN_FOR_BUYING);
        ClaimData.set(open, ClaimData.COST_PER_BLOCK, 2);
        store(h).add(open);
        Region taken = new Region("cl_taken", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(15, 1, 15)), h.absolutePos(new BlockPos(20, 8, 20))), RegionType.CLAIM);
        store(h).add(taken);
        String claimId = null;
        try {
            fund(buyer, 1000);
            h.assertFalse(Claims.INSTANCE.quoteOpen(buyer, "cl_open", ground(h, 2, 2, 4, 4)).ok(), "3 x 3 is too small (min 5)");
            h.assertFalse(Claims.INSTANCE.quoteOpen(buyer, "cl_open", ground(h, -3, 2, 6, 8)).ok(), "sticking out of the area is refused");
            h.assertFalse(Claims.INSTANCE.quoteOpen(buyer, "cl_open", ground(h, 12, 12, 17, 17)).ok(), "overlapping another claim is refused");
            ClaimResult quote = Claims.INSTANCE.quoteOpen(buyer, "cl_open", ground(h, 2, 2, 7, 7));
            h.assertTrue(quote.ok() && quote.price() == 36 * 2, "6 x 6 at 2 per block costs 72: " + quote.message());
            ClaimResult bought = answer(h, then -> Claims.INSTANCE.purchaseOpen(buyer, "cl_open", ground(h, 2, 2, 7, 7), then));
            h.assertTrue(bought.ok() && bought.claimId() != null, "bought: " + bought.message());
            claimId = bought.claimId();
            Region claim = store(h).get(claimId);
            h.assertTrue(claim != null && claim.type() == RegionType.CLAIM && "cl_open".equals(claim.parentId()) && claim.isOwner(buyer.getUUID()), "a child claim of the open area");
            h.assertTrue(claim.bounds().minY() == level.getMinBuildHeight() && claim.bounds().maxY() == level.getMaxBuildHeight() - 1, "full world height");
            h.assertTrue(BspCore.balance(buyer) == 1000 - 72, "72 pieces taken");
            h.assertFalse(Claims.INSTANCE.quoteOpen(buyer, "cl_open", ground(h, 9, 2, 13, 6)).ok(), "a second plot is over the limit of 1");

            String id = claimId;
            h.assertFalse(answer(h, then -> Claims.INSTANCE.enlarge(buyer, id, ground(h, 3, 3, 9, 9), then)).ok(), "an outline that does not contain the plot is refused");
            ClaimResult grown = answer(h, then -> Claims.INSTANCE.enlarge(buyer, id, ground(h, 2, 2, 9, 9), then));
            h.assertTrue(grown.ok() && grown.price() == (64 - 36) * 2, "8 x 8 adds 28 blocks for 56 pieces: " + grown.message());
            h.assertTrue(claim.bounds().sizeX() == 8 && ClaimData.boughtFor(claim) == 72 + 56, "bounds grew and the price was added");

            ClaimResult gone = Claims.INSTANCE.abandon(buyer, claimId);
            h.assertTrue(gone.ok() && gone.price() == (72 + 56) / 2, "half of everything paid comes back");
            h.assertTrue(BspCore.balance(buyer) == 1000 - 72 - 56 + (72 + 56) / 2, "into the bank");
            h.assertTrue(store(h).get(claimId) == null, "an outlined plot disappears when abandoned");
        } finally {
            allowClaims(false);
            RegionGuardConfig.CLAIM_MAX_PER_PLAYER.set(oldLimit);
            if (claimId != null) {
                store(h).remove(claimId);
            }
            store(h).remove("cl_taken");
            store(h).remove("cl_open");
            buyer.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void wiping_claims_needs_the_code_and_leaves_a_backup(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        // The game-test server gives operators level 0, so name the mock player in BSP Core's admin list instead.
        java.util.List<? extends String> previousAdmins = BspCore.setAdminNames(java.util.List.of(admin.getGameProfile().getName()));
        Region claim = new Region("cl_wipe_me", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(4, 4, 4))), RegionType.CLAIM);
        claim.addOwner(admin.getUUID());
        claim.typeData().putInt(ClaimData.BOUGHT_FOR, 30);
        store(h).add(claim);
        Region keep = new Region("cl_keep", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(6, 1, 6)), h.absolutePos(new BlockPos(9, 4, 9))), RegionType.PROTECTED);
        store(h).add(keep);
        try {
            h.assertTrue(BspCore.isAdmin(admin), "the mock player is a BSP Core admin by name");
            String wrong = ClaimWipe.confirm(admin, level, "0000");
            h.assertTrue(wrong.contains("No wipe is waiting") && store(h).has("cl_wipe_me"), "no code without a request");
            String request = ClaimWipe.request(admin, level);
            Matcher m = Pattern.compile("wipe (\\d{4})").matcher(request);
            h.assertTrue(m.find() && request.contains("1 claim"), "the request names the count and a code: " + request);
            String code = m.group(1);
            String bad = ClaimWipe.confirm(admin, level, code.equals("1234") ? "4321" : "1234");
            h.assertTrue(bad.contains("Wrong code") && store(h).has("cl_wipe_me"), "a wrong code wipes nothing");
            String done = ClaimWipe.confirm(admin, level, code);
            h.assertTrue(done.startsWith("Wiped 1 claims") && !store(h).has("cl_wipe_me") && store(h).has("cl_keep"), "the claim is gone, the protected region stays: " + done);
            Matcher f = Pattern.compile("Backup: (claims-[^ ]+\\.nbt)").matcher(done);
            h.assertTrue(f.find(), "a backup file is named");
            String file = f.group(1);
            h.assertTrue(ClaimWipe.backups(level.getServer()).contains(file), "the backup is listed");
            String restored = ClaimWipe.restore(admin, file);
            Region back = store(h).get("cl_wipe_me");
            h.assertTrue(restored.startsWith("Restored 1 claim") && back != null && back.isOwner(admin.getUUID()) && ClaimData.boughtFor(back) == 30, "restored with its owner and price: " + restored);
            h.assertTrue(ClaimWipe.restore(admin, file).contains("skipped 1"), "restoring again skips the existing claim");
        } finally {
            store(h).remove("cl_wipe_me");
            store(h).remove("cl_keep");
            BspCore.setAdminNames(new java.util.ArrayList<>(previousAdmins));
            admin.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_totem_cannot_stand_in_a_claim_that_hinders_a_steal(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        allowClaims(true);
        Region claim = new Region("cl_totem", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(8, 8, 8))), RegionType.CLAIM);
        claim.addOwner(owner.getUUID());
        claim.typeData().putString(ClaimData.ORIGIN, ClaimData.ORIGIN_OPEN);
        store(h).add(claim);
        BlockPos inside = h.absolutePos(new BlockPos(4, 2, 4));
        try {
            // an open claim: placing a totem is fine
            var place = new net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent(
                    net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(), level, inside), BspCore.totemBlockState(), owner);
            // the event's placed block is read from the level, so put the totem there first
            level.setBlock(inside, BspCore.totemBlockState(), 3);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(place);
            h.assertFalse(place.isCanceled(), "a totem may be placed in a claim without hindering rules");
            level.setBlock(inside, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);

            // entry deny on the claim: now the totem is refused
            ClaimResult set = Claims.INSTANCE.setPlayerFlag(owner, "cl_totem", "entry", "deny");
            h.assertTrue(set.ok(), "the owner may deny entry while no totem stands inside: " + set.message());
            level.setBlock(inside, BspCore.totemBlockState(), 3);
            var place2 = new net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent(
                    net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(), level, inside), BspCore.totemBlockState(), owner);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(place2);
            h.assertTrue(place2.isCanceled(), "a totem is refused in a claim that denies entry");
            level.setBlock(inside, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);

            // a totem inside (per BSP Core's ledger): hindering rules are refused, harmless ones are fine
            Claims.INSTANCE.setPlayerFlag(owner, "cl_totem", "entry", "");
            BspCore.recordPlacedTotem(level, java.util.UUID.randomUUID(), inside, 0);
            ClaimResult refused = Claims.INSTANCE.setPlayerFlag(owner, "cl_totem", "pvp", "deny");
            h.assertTrue(!refused.ok() && refused.message().contains("Totem"), "pvp deny refused while a totem stands inside: " + refused.message());
            ClaimResult fine = Claims.INSTANCE.setPlayerFlag(owner, "cl_totem", "greeting", "hi");
            h.assertTrue(fine.ok(), "a greeting is fine");
            ClaimResult notAllowed = Claims.INSTANCE.setPlayerFlag(owner, "cl_totem", "tnt", "deny");
            h.assertTrue(!notAllowed.ok() && notAllowed.message().contains("may set"), "a flag outside the allowed list is refused");
        } finally {
            BspCore.forgetPlacedTotem(level, inside);
            allowClaims(false);
            store(h).remove("cl_totem");
            owner.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void admin_plots_carry_their_own_allowed_flags(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer buyer = TestPlayers.mockServerPlayer(h);
        allowClaims(true);
        Region plot = new Region("cl_shop", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(6, 6, 6))), RegionType.PLOT_FOR_SALE);
        ClaimData.set(plot, ClaimData.PRICE, 10);
        var unknown = com.mrgregles.bsp_region_guard.claim.ClaimFlags.setAllowed(plot, "greeting, chest-access, notaflag");
        store(h).add(plot);
        try {
            h.assertTrue(unknown.equals(java.util.List.of("notaflag")), "unknown names are reported");
            h.assertTrue(com.mrgregles.bsp_region_guard.claim.ClaimFlags.allowedFor(store(h), plot).equals(java.util.List.of("greeting", "chest-access")), "the plot's own list");
            fund(buyer, 10);
            h.assertTrue(answer(h, then -> Claims.INSTANCE.purchasePlot(buyer, "cl_shop", then)).ok(), "bought");
            h.assertTrue(Claims.INSTANCE.setPlayerFlag(buyer, "cl_shop", "chest-access", "allow").ok(), "an allowed flag works");
            h.assertFalse(Claims.INSTANCE.setPlayerFlag(buyer, "cl_shop", "entry", "deny").ok(), "entry is not on this plot's list even though the config allows it");
            com.mrgregles.bsp_region_guard.claim.ClaimFlags.setAllowed(plot, "default");
            h.assertTrue(Claims.INSTANCE.setPlayerFlag(buyer, "cl_shop", "entry", "deny").ok(), "back to the config list, entry is allowed");

            // the claim screen's server side refuses strangers
            ServerPlayer stranger = TestPlayers.mockServerPlayer(h);
            String refused = com.mrgregles.bsp_region_guard.network.ClaimSync.apply(stranger, new com.mrgregles.bsp_region_guard.network.ClaimActionPacket(
                    com.mrgregles.bsp_region_guard.network.ClaimActionPacket.FLAG, "cl_shop", "pvp", "deny"));
            h.assertTrue(refused != null && refused.contains("not a claim of yours"), "a stranger cannot edit through the screen");
            String ok = com.mrgregles.bsp_region_guard.network.ClaimSync.apply(buyer, new com.mrgregles.bsp_region_guard.network.ClaimActionPacket(
                    com.mrgregles.bsp_region_guard.network.ClaimActionPacket.ADD_MEMBER, "cl_shop", stranger.getUUID().toString(), ""));
            h.assertTrue(plot.isMember(stranger.getUUID()), "the owner adds a member through the screen: " + ok);
            stranger.discard();
        } finally {
            allowClaims(false);
            store(h).remove("cl_shop");
            buyer.discard();
        }
        h.succeed();
    }

    /**
     * On a network the bank answers a moment later. Meanwhile the plot is held for the buyer, the buyer cannot start a
     * second purchase, and if the plot changed before the money arrived the money goes straight back.
     */
    @GameTest(template = EMPTY)
    public void a_charge_in_flight_holds_the_plot_and_pays_back_when_it_changed(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer first = TestPlayers.mockServerPlayer(h);
        ServerPlayer second = TestPlayers.mockServerPlayer(h);
        allowClaims(true);
        Region plot = new Region("cl_held", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(6, 6, 6))), RegionType.PLOT_FOR_SALE);
        ClaimData.set(plot, ClaimData.PRICE, 50);
        store(h).add(plot);
        BspCore.holdChargeAnswers(true);
        try {
            fund(first, 100);
            fund(second, 100);
            AtomicReference<ClaimResult> firstAnswer = new AtomicReference<>();
            Claims.INSTANCE.purchasePlot(first, "cl_held", firstAnswer::set);
            h.assertTrue(firstAnswer.get() == null, "no answer while the bank is busy");
            ClaimResult other = Claims.INSTANCE.quotePlot(second, "cl_held");
            h.assertTrue(!other.ok() && other.message().contains("Someone else is buying"), "the plot is held for the first buyer: " + other.message());
            ClaimResult again = answer(h, then -> Claims.INSTANCE.purchasePlot(first, "cl_held", then));
            h.assertTrue(!again.ok() && again.message().contains("still going through"), "one purchase at a time: " + again.message());

            ClaimData.set(plot, ClaimData.PRICE, 70); // an admin changes the price before the money arrives
            BspCore.releaseChargeAnswers();
            h.assertTrue(firstAnswer.get() != null && !firstAnswer.get().ok() && firstAnswer.get().message().contains("went back"), "refused and paid back: "
                    + (firstAnswer.get() == null ? "no answer" : firstAnswer.get().message()));
            h.assertTrue(BspCore.balance(first) == 100, "the money is back in the bank");
            h.assertTrue(plot.type() == RegionType.PLOT_FOR_SALE && plot.owners().isEmpty(), "the plot is still for sale");

            firstAnswer.set(null);
            Claims.INSTANCE.purchasePlot(first, "cl_held", firstAnswer::set);
            BspCore.releaseChargeAnswers();
            h.assertTrue(firstAnswer.get() != null && firstAnswer.get().ok(), "bought at the new price");
            h.assertTrue(BspCore.balance(first) == 30 && plot.isOwner(first.getUUID()), "70 taken, the plot is theirs");
            h.assertTrue(Claims.INSTANCE.quotePlot(second, "cl_held").message().contains("not for sale"), "and no longer held or for sale");
        } finally {
            BspCore.holdChargeAnswers(false);
            allowClaims(false);
            store(h).remove("cl_held");
            first.discard();
            second.discard();
        }
        h.succeed();
    }
}
