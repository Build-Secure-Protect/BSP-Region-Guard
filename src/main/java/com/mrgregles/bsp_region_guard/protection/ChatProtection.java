package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.region.RegionQuery;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Chat and command flags. */
public final class ChatProtection {
    private ChatProtection() {
    }

    public static void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer player) || RegionQuery.bypasses(player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        String command = rootCommand(event.getParseResults().getReader().getString());
        Set<String> blocked = RegionQuery.value(level, player.blockPosition(), Flags.BLOCKED_CMDS);
        Set<String> allowed = RegionQuery.value(level, player.blockPosition(), Flags.ALLOWED_CMDS);
        boolean deny = blocked != null && contains(blocked, command)
                || allowed != null && !allowed.isEmpty() && !contains(allowed, command);
        if (deny) {
            event.setCanceled(true);
            Protection.tell(player, level, player.blockPosition(), "You can't use /" + command + " here.");
        }
    }

    /** "/rg flag x y" and "rg flag x y" both give "rg". */
    static String rootCommand(String input) {
        String s = input.startsWith("/") ? input.substring(1) : input;
        int space = s.indexOf(' ');
        return (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static boolean contains(Set<String> set, String command) {
        return set.contains(command) || set.contains("/" + command);
    }

    public static void onChat(ServerChatEvent event) {
        ServerPlayer sender = event.getPlayer();
        ServerLevel level = sender.serverLevel();
        if (!Protection.mayWorld(level, sender.blockPosition(), Flags.SEND_CHAT) && !RegionQuery.bypasses(sender)) {
            event.setCanceled(true);
            Protection.tell(sender, level, sender.blockPosition(), "You can't chat here.");
            return;
        }
        // receive-chat: only when somebody on the server stands where chat is not received.
        List<ServerPlayer> deaf = new ArrayList<>();
        for (ServerPlayer p : sender.server.getPlayerList().getPlayers()) {
            if (p != sender && !Protection.mayWorld(p.serverLevel(), p.blockPosition(), Flags.RECEIVE_CHAT)) {
                deaf.add(p);
            }
        }
        if (deaf.isEmpty()) {
            return;
        }
        event.setCanceled(true);
        Component line = Component.translatable("chat.type.text", sender.getDisplayName(), event.getMessage());
        for (ServerPlayer p : sender.server.getPlayerList().getPlayers()) {
            if (!deaf.contains(p)) {
                p.sendSystemMessage(line);
            }
        }
        sender.server.sendSystemMessage(line);
    }
}
