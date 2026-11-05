package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared helpers for the protection handlers: the checks, the "you can't do that here" message, admin bypass. */
public final class Protection {
    /** How often one player is told "no" at most, in milliseconds. */
    private static final long MESSAGE_GAP_MS = 1500;
    private static final Map<UUID, Long> LAST_MESSAGE = new HashMap<>();

    private Protection() {
    }

    @Nullable
    public static ServerLevel server(LevelAccessor level) {
        return level instanceof ServerLevel s ? s : null;
    }

    /** A player's own action: true when the flag allows it, or the player is bypassing. */
    public static boolean mayPlayer(ServerLevel level, BlockPos pos, StateFlag flag, Player player) {
        if (RegionQuery.bypasses(player)) {
            return true;
        }
        return RegionQuery.allows(level, pos, flag, player) && !((flag == Flags.BUILD || flag.fallback() == Flags.BUILD)
                && MovementTracker.isKeptOut(level, pos, player)); // a yes/no: the rank message is not built
    }

    /**
     * Building, breaking, chests, doors and the rest of the build family (bugs B21, B22): a spot the region rules keep
     * the player out of is a spot they may not act on from outside either. The reason, or null when they may.
     */
    @Nullable
    private static String keptOut(ServerLevel level, BlockPos pos, StateFlag flag, Player player) {
        return flag == Flags.BUILD || flag.fallback() == Flags.BUILD ? MovementTracker.keptOut(level, pos, player) : null;
    }

    /** Something that is not a player (a mob, the world): true when the flag allows it. */
    public static boolean mayWorld(ServerLevel level, BlockPos pos, StateFlag flag) {
        return RegionQuery.allows(level, pos, flag, null);
    }

    /** Checks a player's action and, when refused, tells them. Returns true when the action may go ahead. */
    public static boolean checkPlayer(ServerLevel level, BlockPos pos, StateFlag flag, Player player) {
        if (RegionQuery.bypasses(player)) {
            return true;
        }
        if (!RegionQuery.allows(level, pos, flag, player)) {
            tell(player, level, pos);
            return false;
        }
        String kept = keptOut(level, pos, flag, player);
        if (kept != null) {
            tellTitle(player, kept); // D155, D157: the same message as walking in, in the middle of the screen
            return false;
        }
        return true;
    }

    /** Shows the region's deny message on the action bar, at most once every 1.5 seconds per player. */
    public static void tell(Player player, ServerLevel level, BlockPos pos) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_MESSAGE.get(sp.getUUID());
        if (last != null && now - last < MESSAGE_GAP_MS) {
            return;
        }
        LAST_MESSAGE.put(sp.getUUID(), now);
        sp.displayClientMessage(Component.literal(RegionQuery.denyMessage(level, pos)), true);
    }

    /** A message on the action bar, at most once every 1.5 seconds per player (for refusals that repeat every tick). */
    public static void tellOnce(Player player, String message) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_MESSAGE.get(sp.getUUID());
        if (last != null && now - last < MESSAGE_GAP_MS) {
            return;
        }
        LAST_MESSAGE.put(sp.getUUID(), now);
        sp.displayClientMessage(Component.literal(message), true);
    }

    public static void tell(Player player, ServerLevel level, BlockPos pos, String message) {
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.literal(message), true);
        }
    }

    public static void forget(UUID player) {
        LAST_MESSAGE.remove(player);
        LAST_TITLE.remove(player);
        LAST_TITLE_TEXT.remove(player);
    }

    private static final long TITLE_GAP_MS = 2500;
    private static final Map<UUID, Long> LAST_TITLE = new HashMap<>();
    private static final Map<UUID, String> LAST_TITLE_TEXT = new HashMap<>();

    /**
     * Shows an entry refusal in the middle of the screen, as {@code /title} does (owner decision D157): the text up to a
     * newline as the title, the rest as the subtitle. The same text is shown again at most every 2.5 seconds per player,
     * so a player pushed back every tick sees it once rather than flickering.
     */
    public static void tellTitle(Player player, String message) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_TITLE.get(sp.getUUID());
        if (last != null && now - last < TITLE_GAP_MS && message.equals(LAST_TITLE_TEXT.get(sp.getUUID()))) {
            return;
        }
        LAST_TITLE.put(sp.getUUID(), now);
        LAST_TITLE_TEXT.put(sp.getUUID(), message);
        int nl = message.indexOf('\n');
        String title = nl < 0 ? message : message.substring(0, nl), subtitle = nl < 0 ? "" : message.substring(nl + 1);
        sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(5, 50, 15));
        sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title)));
    }

    /** The player behind an entity: the entity itself, or the owner of a projectile or pet; else {@code null}. */
    @Nullable
    public static Player playerBehind(@Nullable Entity entity) {
        if (entity instanceof Player p) {
            return p;
        }
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile proj && proj.getOwner() instanceof Player p) {
            return p;
        }
        if (entity instanceof net.minecraft.world.entity.TamableAnimal pet && pet.getOwner() instanceof Player p) {
            return p;
        }
        return null;
    }

    public static boolean isServer(Level level) {
        return !level.isClientSide();
    }
}
