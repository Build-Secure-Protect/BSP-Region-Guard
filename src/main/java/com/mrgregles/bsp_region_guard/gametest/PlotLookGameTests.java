package com.mrgregles.bsp_region_guard.gametest;

import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.RegionGuardConfig;
import com.mrgregles.bsp_region_guard.api.ClaimResult;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.claim.ClaimData;
import com.mrgregles.bsp_region_guard.claim.Claims;
import com.mrgregles.bsp_region_guard.claim.PlotLook;
import com.mrgregles.bsp_region_guard.command.RegionEdits;
import com.mrgregles.bsp_region_guard.compat.BspCore;
import com.mrgregles.bsp_region_guard.network.ClaimActionPacket;
import com.mrgregles.bsp_region_guard.network.ClaimSync;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import com.mrgregles.bsp_region_guard.network.RegionSync;
import com.mrgregles.bsp_region_guard.region.Region;
import com.mrgregles.bsp_region_guard.region.RegionStore;
import com.mrgregles.bsp_region_guard.region.RegionType;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** How plots for sale look in the world (D105 to D112) and the teleport offered after buying one (D113). */
@GameTestHolder(BSPRegionGuard.MODID)
@PrefixGameTestTemplate(false)
public class PlotLookGameTests {
    private static final String EMPTY = "empty";

    private static RegionStore store(GameTestHelper h) {
        return RegionStore.get(h.getLevel());
    }

    private static Region plot(GameTestHelper h, String id, int x, int z) {
        Region r = new Region(id, h.getLevel().dimension(), Cuboid.of(h.absolutePos(new BlockPos(x, 1, z)), h.absolutePos(new BlockPos(x + 5, 12, z + 5))),
                RegionType.PLOT_FOR_SALE);
        ClaimData.set(r, ClaimData.PRICE, 100);
        store(h).add(r);
        return r;
    }

    @GameTest(template = EMPTY)
    public void a_plot_look_is_checked_and_kept(GameTestHelper h) {
        Region p = plot(h, "look_a", 1, 1);
        try {
            h.assertTrue(PlotLook.of(p).equals(PlotLook.DEFAULT) && PlotLook.DEFAULT.marker().equals("arrow") && PlotLook.DEFAULT.colour() == 0xFFD23A,
                    "a new plot shows the gold arrow");
            h.assertTrue(RegionEdits.plotLook(store(h), p, "sign;#5aa9ff;1.5;2.0;5").ok(), "the window's line is saved");
            h.assertTrue(PlotLook.of(p).equals(new PlotLook("sign", 0x5AA9FF, 1.5f, 2f, 5)), "and read back the same");
            h.assertFalse(RegionEdits.plotLook(store(h), p, "rocket;#5aa9ff;1;1;5").ok(), "an unknown marker is refused");
            h.assertFalse(RegionEdits.plotLook(store(h), p, "sign;blue;1;1;5").ok(), "so is a colour that is not hex");
            h.assertTrue(RegionEdits.plotLookPart(store(h), p, "size", "9").ok() && PlotLook.of(p).size() == PlotLook.MAX_SIZE, "sizes stop at 3x");
            h.assertTrue(RegionEdits.plotLookPart(store(h), p, "namesize", "0.1").ok() && PlotLook.of(p).text() == PlotLook.MIN_SIZE, "and at 0.5x");
            h.assertTrue(RegionEdits.plotLookPart(store(h), p, "height", "500").ok() && PlotLook.of(p).height() == PlotLook.MAX_HEIGHT, "heights stop at 64");
            h.assertTrue(RegionEdits.plotLookPart(store(h), p, "marker", "sphere").ok() && PlotLook.of(p).marker().equals("sphere"), "a marker by command");
            h.assertTrue(RegionEdits.plotLookPart(store(h), p, "colour", "FF6B5C").ok() && PlotLook.of(p).colour() == 0xFF6B5C, "a colour by command");
            h.assertFalse(RegionEdits.plotLookPart(store(h), p, "marker", "rocket").ok(), "a wrong marker by command is refused");
            h.assertFalse(RegionEdits.plotLook(store(h), store(h).global(), PlotLook.DEFAULT.line()).ok(), "the world default has no look");
        } finally {
            store(h).remove("look_a");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void players_get_the_look_of_plots_for_sale_only(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        Region sale = plot(h, "look_sale", 1, 1);
        new PlotLook("beacon", 0x7BE36B, 2f, 1f, 4).save(sale);
        Region owned = plot(h, "look_owned", 8, 1);
        owned.setType(RegionType.CLAIM);
        owned.addOwner(player.getUUID());
        Region guarded = new Region("look_prot", level.dimension(), Cuboid.of(h.absolutePos(new BlockPos(1, 1, 8)), h.absolutePos(new BlockPos(4, 4, 11))), RegionType.PROTECTED);
        store(h).add(guarded);
        try {
            List<RegionListPacket.Summary> list = RegionSync.listFor(player);
            RegionListPacket.Summary s = list.stream().filter(x -> x.id().equals("look_sale")).findFirst().orElse(null);
            h.assertTrue(s != null && new PlotLook("beacon", 0x7BE36B, 2f, 1f, 4).equals(s.look()), "a plot for sale comes with its look");
            h.assertTrue(s.price().contains("100"), "and its price for the sign: " + s.price());
            RegionListPacket.Summary mine = list.stream().filter(x -> x.id().equals("look_owned")).findFirst().orElse(null);
            h.assertTrue(mine != null && mine.look() == null, "a bought plot has no look, so it is not drawn for everyone (D105)");
            h.assertTrue(list.stream().noneMatch(x -> x.id().equals("look_prot")), "players do not get other regions");

            RegionListPacket packet = new RegionListPacket(level.dimension().location(), list, null, false, "", List.of(), false);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            RegionListPacket.encode(packet, buf);
            RegionListPacket back = RegionListPacket.decode(buf);
            h.assertTrue(back.equals(packet) && !back.admin(), "the look, the price and the admin mark survive the packet");
        } finally {
            store(h).remove("look_sale");
            store(h).remove("look_owned");
            store(h).remove("look_prot");
            player.discard();
        }
        h.succeed();
    }

    /** D113: after buying, TELEPORT (the claim screen's own action) takes the owner into the plot; nobody else. */
    @GameTest(template = EMPTY)
    public void the_buyer_can_teleport_to_a_new_plot(GameTestHelper h) {
        ServerPlayer buyer = TestPlayers.mockServerPlayer(h), other = TestPlayers.mockServerPlayer(h);
        boolean claimsWere = RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.get();
        RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(true); // the test world counts as a PvP world
        Region p = plot(h, "look_buy", 1, 1);
        try {
            BspCore.credit(buyer.server, buyer.getUUID(), 500, "test", ok -> {
            });
            AtomicReference<ClaimResult> out = new AtomicReference<>();
            Claims.INSTANCE.purchasePlot(buyer, "look_buy", out::set);
            h.assertTrue(out.get() != null && out.get().ok(), "bought: " + (out.get() == null ? "no answer" : out.get().message()));
            buyer.teleportTo(h.absolutePos(new BlockPos(20, 2, 20)).getX(), h.absolutePos(new BlockPos(20, 2, 20)).getY(), h.absolutePos(new BlockPos(20, 2, 20)).getZ());
            ClaimSync.apply(buyer, ClaimActionPacket.of(ClaimActionPacket.TELEPORT, "look_buy"));
            Cuboid b = p.bounds();
            h.assertTrue(buyer.getX() >= b.minX() && buyer.getX() <= b.maxX() + 1 && buyer.getZ() >= b.minZ() && buyer.getZ() <= b.maxZ() + 1,
                    "the owner lands in the plot: " + buyer.blockPosition());
            String refused = ClaimSync.apply(other, ClaimActionPacket.of(ClaimActionPacket.TELEPORT, "look_buy"));
            h.assertTrue(refused != null && refused.contains("not a claim of yours"), "another player may not: " + refused);
        } finally {
            RegionGuardConfig.CLAIMS_IN_PVP_WORLDS.set(claimsWere);
            store(h).remove("look_buy");
            buyer.discard();
            other.discard();
        }
        h.succeed();
    }
}
