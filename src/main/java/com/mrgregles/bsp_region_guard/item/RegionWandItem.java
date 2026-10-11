package com.mrgregles.bsp_region_guard.item;

import com.mrgregles.bsp_region_guard.region.Cuboid;
import com.mrgregles.bsp_region_guard.selection.Selection;
import com.mrgregles.bsp_region_guard.selection.Selections;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The admin's region wand: left-click a block for the first corner, right-click for the second. The selection is
 * drawn with particles while the wand is held ({@link com.mrgregles.bsp_region_guard.event.WandHandler}).
 */
public final class RegionWandItem extends Item {
    public RegionWandItem() {
        super(new Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer sp) {
                com.mrgregles.bsp_region_guard.network.RegionSync.open(sp);
            }
            return net.minecraft.world.InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
        }
        return super.use(level, player, hand);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            if (context.getPlayer() instanceof ServerPlayer sp) {
                com.mrgregles.bsp_region_guard.network.RegionSync.open(sp);
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }
        if (context.getPlayer() instanceof ServerPlayer player) {
            Selection sel = Selections.of(player);
            sel.setPos2(player.serverLevel().dimension(), context.getClickedPos());
            tell(player, sel, "Second corner", context.getClickedPos());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    /** Shared by both clicks: reports the corner and, once both are set, the size. */
    public static void tell(ServerPlayer player, Selection sel, String which, BlockPos pos) {
        String text = which + " set to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        Cuboid box = sel.cuboid();
        if (box != null) {
            text += " (" + box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ() + ", " + box.volume() + " blocks)";
        }
        player.displayClientMessage(Component.literal(text), true);
    }
}
