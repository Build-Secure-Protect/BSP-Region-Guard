package com.mrgregles.bsp_region_guard.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the connection's {@code resetPosition}, which records where the player stands as the tick's starting point.
 *
 * <p>Bug B25: Minecraft runs the player's tick inside the connection's tick and, right after it, puts the server's copy
 * of the player back where they stood when the tick began ({@code absMoveTo(firstGoodX, ...)}). A player Region Guard
 * moves during their tick (pushed back from the border or a region) was therefore put straight back outside on the
 * server until their game answered the teleport; with a ping above one tick the push-back fired again every tick and
 * never settled. Calling this right after such a move makes the new spot the tick's starting point, so it stays.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {
    @Invoker("resetPosition")
    void bsp_region_guard$resetPosition();
}
