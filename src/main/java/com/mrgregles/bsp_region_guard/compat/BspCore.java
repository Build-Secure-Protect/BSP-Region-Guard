package com.mrgregles.bsp_region_guard.compat;

import com.mrgregles.bsp_core.BSPConfig;
import com.mrgregles.bsp_core.api.BspBank;
import com.mrgregles.bsp_core.api.BspCoins;
import com.mrgregles.bsp_core.api.BspPlayers;
import com.mrgregles.bsp_core.api.BspZones;
import com.mrgregles.bsp_core.data.TotemLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The only class that touches BSP-Core.
 *
 * <p>Every call the mod makes at run time goes through BSP-Core's {@code api} package (since RG-C5, 2026-10-15). Only
 * the test helpers below still reach BSP-Core's own classes (recording a placed totem, the totem block, the admin
 * list, Anti Totem zones). A rename there breaks this file and nothing else.
 */
public final class BspCore {
    private BspCore() {
    }

    /** BSP-Core's admin level (config list; every operator when the list is empty). */
    public static boolean isAdmin(Player player) {
        return BspPlayers.isAdmin(player);
    }

    public static boolean isModerator(Player player) {
        return BspPlayers.isModerator(player);
    }

    // ------------------------------------------------------------------ money: the bank (owner decision D31)

    /** Who the bank's log names as the other side of claim payments. */
    private static final String BANK_PARTY = "Region Guard";

    /** The player's bank balance in Shatter Pieces (on a network: the last value this server read). */
    public static long balance(ServerPlayer player) {
        return BspBank.balance(player.server, player.getUUID());
    }

    /**
     * Takes {@code pieces} from the player's bank. {@code then} runs on the server thread with true when the money
     * was taken; at once on a single server, once the database answered on a network. Nothing to pay is a success.
     */
    public static void charge(ServerPlayer player, long pieces, String reason, Consumer<Boolean> then) {
        if (pieces <= 0) {
            then.accept(true);
            return;
        }
        List<Runnable> held = heldAnswers;
        if (held != null) {
            BspBank.charge(player.server, player.getUUID(), pieces, BANK_PARTY, reason, paid -> held.add(() -> then.accept(paid)));
            return;
        }
        BspBank.charge(player.server, player.getUUID(), pieces, BANK_PARTY, reason, then);
    }

    /** For tests: while not null, the answers to charges wait here, as they would for a database round trip on a network. */
    @Nullable
    private static List<Runnable> heldAnswers;

    /** For tests: starts or stops holding back the answers to charges (stopping does not deliver the held ones). */
    public static void holdChargeAnswers(boolean on) {
        heldAnswers = on ? new ArrayList<>() : null;
    }

    /** For tests: delivers the answers held so far. */
    public static void releaseChargeAnswers() {
        List<Runnable> held = heldAnswers;
        if (held != null) {
            List<Runnable> now = new ArrayList<>(held);
            held.clear();
            now.forEach(Runnable::run);
        }
    }

    /** Pays {@code pieces} into a player's bank (refunds); works while they are offline. */
    public static void credit(MinecraftServer server, UUID player, long pieces, String reason, Consumer<Boolean> then) {
        if (pieces <= 0) {
            then.accept(true);
            return;
        }
        BspBank.credit(server, player, pieces, BANK_PARTY, reason, then);
    }

    /** Opens BSP Core's Land Office on the map with this ground outlined (y does not matter). */
    public static void openLandOffice(ServerPlayer player, com.mrgregles.bsp_region_guard.api.Cuboid ground) {
        com.mrgregles.bsp_core.api.BspLandOffice.open(player, ground.minX(), ground.minZ(), ground.maxX(), ground.maxZ());
    }

    /** Opens BSP Core's Land Office with no ground outlined. */
    public static void openLandOffice(ServerPlayer player) {
        com.mrgregles.bsp_core.api.BspLandOffice.open(player);
    }

    /** An amount as players read it, for example {@code 12,500 pieces}. */
    public static String money(long pieces) {
        return BspBank.format(pieces);
    }

    // ------------------------------------------------------------------ money: Shatter Coins (D95 to D98, RG-C9)

    /** BSP Core's tiers never change while the game runs, so they are read once. */
    @Nullable
    private static List<String> tiers;

    /** The coin tiers by key, cheapest first (copper, gold, diamond, netherite, illyrium). */
    public static List<String> coinTiers() {
        List<String> t = tiers;
        if (t == null) {
            tiers = t = List.copyOf(BspCoins.tiers());
        }
        return t;
    }

    /** One coin of this tier, in copper-coin units (BSP Core's config); 0 for an unknown tier. */
    public static long coinValue(String tier) {
        return BspCoins.value(tier);
    }

    /** The coins the player carries, in copper-coin units. */
    public static long coinWorth(ServerPlayer player) {
        return BspCoins.worth(player);
    }

    /** Takes coins worth {@code value} copper from the inventory, smallest first, with change; false and nothing taken when short. */
    public static boolean payCoins(ServerPlayer player, long value) {
        if (value <= 0) {
            return true;
        }
        return value <= Integer.MAX_VALUE && BspCoins.pay(player, (int) value);
    }

    /** Gives coins worth {@code value} copper into the inventory, largest first (what does not fit drops at the player's feet). */
    public static boolean giveCoins(ServerPlayer player, long value) {
        for (long left = value; left > 0; left -= Integer.MAX_VALUE) {
            BspCoins.give(player, (int) Math.min(left, Integer.MAX_VALUE));
        }
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    /** Replaces BSP Core's admin name list for the rest of the server run; returns the previous list. For tests. */
    public static List<? extends String> setAdminNames(List<String> names) {
        List<? extends String> previous = BSPConfig.ADMINS.get();
        BSPConfig.ADMINS.set(names);
        return previous;
    }

    // ------------------------------------------------------------------ Anti Totem zones (owner decision D32)

    /** BSP-Core's rule keys for its Anti Totem zones. */
    public static final String ZONE_TOTEM_PLACE = BspZones.TOTEM_PLACE, ZONE_TOTEM_DROP = BspZones.TOTEM_DROP,
            ZONE_TELEPORT_IN = BspZones.TELEPORT_IN, ZONE_TELEPORT_OUT = BspZones.TELEPORT_OUT, ZONE_SET_HOME = BspZones.SET_HOME;

    /** True when one of BSP-Core's Anti Totem zones covering {@code pos} blocks the rule {@code key}. */
    public static boolean zoneBlocks(ServerLevel level, BlockPos pos, String key) {
        try {
            return BspZones.isBlocked(level, pos, key);
        } catch (RuntimeException e) {
            return false; // no overworld yet (very early or very late in the server's life)
        }
    }

    /** For tests: puts an Anti Totem zone reaching {@code reach} blocks in every direction around {@code centre}. */
    public static void putZone(ServerLevel level, BlockPos centre, int reach, Set<String> blocked) {
        com.mrgregles.bsp_core.zone.ZoneLedger.get(level.getServer()).put(GlobalPos.of(level.dimension(), centre),
                new int[]{reach, reach, reach, reach, reach, reach}, blocked);
    }

    public static void removeZone(ServerLevel level, BlockPos centre) {
        com.mrgregles.bsp_core.zone.ZoneLedger.get(level.getServer()).remove(GlobalPos.of(level.dimension(), centre));
    }

    // ------------------------------------------------------------------ totems

    /** A world where Shatter Totems may be placed: the raiding (PvP) worlds. */
    public static boolean isTotemWorld(ResourceKey<Level> dimension) {
        try {
            return BspPlayers.isDimensionAllowed(dimension.location());
        } catch (RuntimeException e) {
            return false; // config not loaded yet
        }
    }

    /** How close a thief must stand to steal, in blocks. */
    public static int stealRadius() {
        try {
            return com.mrgregles.bsp_core.api.BspTotems.stealRadius();
        } catch (RuntimeException e) {
            return 5;
        }
    }

    /** A placed totem: who owns it and where it is. */
    public record PlacedTotem(UUID owner, BlockPos pos) {
    }

    /** Placed totems in this world within {@code radius} blocks (box distance) of a position, from BSP Core's ledger. */
    public static List<PlacedTotem> placedTotemsNear(ServerLevel level, BlockPos pos, int radius) {
        // BspTotems.placedNear measures a sphere; the totem rule has always used a box, so filter allPlaced here
        List<PlacedTotem> out = new ArrayList<>();
        for (com.mrgregles.bsp_core.api.BspTotems.Placed t : com.mrgregles.bsp_core.api.BspTotems.allPlaced(level.getServer())) {
            GlobalPos gp = t.where();
            if (gp.dimension().equals(level.dimension())) {
                BlockPos p = gp.pos();
                if (Math.abs(p.getX() - pos.getX()) <= radius && Math.abs(p.getY() - pos.getY()) <= radius && Math.abs(p.getZ() - pos.getZ()) <= radius) {
                    out.add(new PlacedTotem(t.owner(), p));
                }
            }
        }
        return out;
    }

    /** Whether a block state is a placed Shatter Totem. */
    public static boolean isTotemBlock(net.minecraft.world.level.block.state.BlockState state) {
        return com.mrgregles.bsp_core.api.BspTotems.isTotem(state);
    }

    /** For tests: a totem block to place (no api equivalent; test-only). */
    public static net.minecraft.world.level.block.state.BlockState totemBlockState() {
        return com.mrgregles.bsp_core.registry.ModBlocks.SHATTER_TOTEM.get().defaultBlockState();
    }

    /** Whether a steal is running at a placed totem (the chunk must be loaded). */
    public static Optional<UUID> thiefAt(ServerLevel level, BlockPos pos) {
        return com.mrgregles.bsp_core.api.BspTotems.thiefAt(level, pos).map(com.mrgregles.bsp_core.api.BspTotems.Steal::thief);
    }

    /** For tests: tells BSP Core's ledger about a placed totem (test-only internal call). */
    public static void recordPlacedTotem(ServerLevel level, UUID owner, BlockPos pos, int tier) {
        TotemLedger.get(level.getServer()).recordPlaced(owner, GlobalPos.of(level.dimension(), pos), tier);
    }

    public static void forgetPlacedTotem(ServerLevel level, BlockPos pos) {
        TotemLedger.get(level.getServer()).recordRemoved(GlobalPos.of(level.dimension(), pos));
    }
}
