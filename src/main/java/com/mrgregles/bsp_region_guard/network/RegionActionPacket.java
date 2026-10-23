package com.mrgregles.bsp_region_guard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server: one edit or request from the screen. The server checks that the sender is an admin, applies it,
 * and answers with a fresh list and detail. Arguments are plain text, parsed by the same code the commands use.
 */
public record RegionActionPacket(String action, String regionId, String a, String b) {
    public static final String LIST = "list", SELECT = "select", FLAG = "flag", ADD_OWNER = "add-owner", REMOVE_OWNER = "remove-owner",
            ADD_MEMBER = "add-member", REMOVE_MEMBER = "remove-member", ADD_RANK = "add-rank", REMOVE_RANK = "remove-rank",
            PRIORITY = "priority", PARENT = "parent", TYPE = "type", RENAME = "rename", DELETE = "delete", CREATE = "create",
            REDEFINE = "redefine", TELEPORT = "teleport", WAND = "wand", SELECT_BOUNDS = "select-bounds", SET_DATA = "set-data",
            MINE_CREATE = "mine-create", MINE_ADD = "mine-add", MINE_INTERVAL = "mine-interval", MINE_SPAWN = "mine-spawn", MINE_SHELL = "mine-shell", MINE_RESET = "mine-reset",
            TRIGGER_ADD = "trigger-add", TRIGGER_REMOVE = "trigger-remove", LIST_REMOTE = "list-remote",
            BORDER_SET = "border-set", BORDER_OFF = "border-off", EDGE_LINK = "edge-link", EDGE_UNLINK = "edge-unlink", EDGE_REFRESH = "edge-refresh",
            VIEW_SERVER = "view-server", WIPE_REQUEST = "wipe-request", WIPE_CONFIRM = "wipe-confirm", WIPE_RESTORE = "wipe-restore",
            RESIZE = "resize", VFILL = "vfill", CORNERS = "corners", PLOTS_PVP = "plots-pvp";

    public static RegionActionPacket of(String action, String regionId) {
        return new RegionActionPacket(action, regionId, "", "");
    }

    public static RegionActionPacket of(String action, String regionId, String a) {
        return new RegionActionPacket(action, regionId, a, "");
    }

    public static void encode(RegionActionPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.action, 32);
        buf.writeUtf(msg.regionId, 64);
        buf.writeUtf(msg.a, 256);
        buf.writeUtf(msg.b, 1024);
    }

    public static RegionActionPacket decode(FriendlyByteBuf buf) {
        return new RegionActionPacket(buf.readUtf(32), buf.readUtf(64), buf.readUtf(256), buf.readUtf(1024));
    }

    public static void handle(RegionActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            RegionSync.handle(player, msg);
        }
        ctx.get().setPacketHandled(true);
    }
}
