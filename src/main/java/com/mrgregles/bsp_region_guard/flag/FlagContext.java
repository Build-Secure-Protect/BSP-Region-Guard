package com.mrgregles.bsp_region_guard.flag;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** What a flag value is parsed against: the world the region is in and, when typed by a player, that player. */
public record FlagContext(ServerLevel level, @Nullable ServerPlayer player) {
}
