package com.mrgregles.bsp_region_guard.protection;

import com.mrgregles.bsp_region_guard.flag.Flags;
import com.mrgregles.bsp_region_guard.flag.StateFlag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.player.BonemealEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;

/**
 * Players changing or using blocks: break, place, interact, use, chest access, anvils, beds, lighters, vehicles,
 * tools that modify blocks, buckets, bone meal and trampling.
 */
public final class BlockProtection {
    private BlockProtection() {
    }

    public static void onBreak(BlockEvent.BreakEvent event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        if (!Protection.checkPlayer(level, event.getPos(), Flags.BLOCK_BREAK, event.getPlayer())) {
            event.setCanceled(true);
            return;
        }
        if (event.getExpToDrop() > 0 && !Protection.mayWorld(level, event.getPos(), Flags.EXP_DROPS)) {
            event.setExpToDrop(0);
        }
    }

    /** Also receives {@code EntityMultiPlaceEvent} (beds, doors): the bus delivers subclasses too. */
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null || !(event.getEntity() instanceof Player player)) {
            return; // mobs and falling blocks are handled by the griefing and mixin rules
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (var snapshot : multi.getReplacedBlockSnapshots()) {
                if (!Protection.checkPlayer(level, snapshot.getPos(), Flags.BLOCK_PLACE, player)) {
                    event.setCanceled(true);
                    return;
                }
            }
            return;
        }
        if (!Protection.checkPlayer(level, event.getPos(), Flags.BLOCK_PLACE, player)) {
            event.setCanceled(true);
        }
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        Player player = event.getEntity();
        BlockPos pos = event.getPos();
        ItemStack stack = event.getItemStack();
        BlockState state = level.getBlockState(pos);

        // The item in hand first: some items act on the block in front, not the one clicked.
        StateFlag itemFlag = itemFlag(stack);
        if (itemFlag != null) {
            BlockPos target = stack.getItem() instanceof BlockItem ? pos.relative(event.getFace()) : pos;
            if (!Protection.checkPlayer(level, target, itemFlag, player)) {
                deny(event);
            }
            return; // block items go on to EntityPlaceEvent, buckets to FillBucketEvent
        }

        StateFlag blockFlag = blockFlag(level, pos, state);
        if (blockFlag != null) {
            if (!Protection.checkPlayer(level, pos, blockFlag, player)) {
                deny(event);
            }
            return;
        }
        // A plain block clicked with some other item (bone meal, shears, dye, a spawn egg): an interaction.
        if (!stack.isEmpty() && !Protection.checkPlayer(level, pos, Flags.INTERACT, player)) {
            deny(event);
        }
    }

    private static void deny(PlayerInteractEvent.RightClickBlock event) {
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCanceled(true);
    }

    /** The flag for an item used on a block, or {@code null} when the block decides. */
    private static StateFlag itemFlag(ItemStack stack) {
        var item = stack.getItem();
        if (item instanceof FlintAndSteelItem || item instanceof FireChargeItem) {
            return Flags.LIGHTER;
        }
        if (item instanceof BoatItem || item instanceof MinecartItem) {
            return Flags.VEHICLE_PLACE;
        }
        if (item instanceof BlockItem) {
            return Flags.BLOCK_PLACE; // checked here for the message; EntityPlaceEvent enforces it
        }
        if (item instanceof BucketItem) {
            return null; // FillBucketEvent
        }
        return null;
    }

    /** The flag for using a block, or {@code null} when the block does nothing special when clicked. */
    static StateFlag blockFlag(ServerLevel level, BlockPos pos, BlockState state) {
        var block = state.getBlock();
        if (block instanceof BedBlock) {
            return Flags.SLEEP;
        }
        if (block instanceof RespawnAnchorBlock) {
            return Flags.RESPAWN_ANCHORS;
        }
        if (block instanceof AnvilBlock) {
            return Flags.USE_ANVIL;
        }
        if (block instanceof BigDripleafBlock) {
            return Flags.USE_DRIPLEAF;
        }
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.BUTTONS) || block instanceof LeverBlock || block instanceof NoteBlock
                || block instanceof RepeaterBlock || block instanceof ComparatorBlock || block instanceof DaylightDetectorBlock) {
            return Flags.USE;
        }
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container || state.getMenuProvider(level, pos) != null) {
            return Flags.CHEST_ACCESS;
        }
        if (state.hasBlockEntity()) {
            return Flags.INTERACT; // machines and other modded blocks with a screen or a state
        }
        return null;
    }

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        var item = event.getItemStack().getItem();
        if (item instanceof BoatItem || item instanceof MinecartItem) {
            // Boats are placed by ray trace on use, not on a clicked block.
            if (!Protection.checkPlayer(level, event.getEntity().blockPosition(), Flags.VEHICLE_PLACE, event.getEntity())) {
                event.setCanceled(true);
            }
        }
    }

    public static void onToolModification(BlockEvent.BlockToolModificationEvent event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null || event.isSimulated()) {
            return;
        }
        Player player = event.getPlayer();
        boolean ok = player == null ? Protection.mayWorld(level, event.getPos(), Flags.BUILD)
                : Protection.checkPlayer(level, event.getPos(), Flags.BUILD, player);
        if (!ok) {
            event.setCanceled(true);
        }
    }

    public static void onFillBucket(FillBucketEvent event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null || !(event.getTarget() instanceof BlockHitResult hit)) {
            return;
        }
        boolean emptying = !(event.getEmptyBucket().getItem() instanceof BucketItem b) || b != net.minecraft.world.item.Items.BUCKET;
        BlockPos pos = emptying ? hit.getBlockPos().relative(hit.getDirection()) : hit.getBlockPos();
        if (!Protection.checkPlayer(level, pos, emptying ? Flags.BLOCK_PLACE : Flags.BLOCK_BREAK, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    public static void onBonemeal(BonemealEvent event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        if (!Protection.checkPlayer(level, event.getPos(), Flags.INTERACT, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        ServerLevel level = Protection.server(event.getLevel());
        if (level == null) {
            return;
        }
        boolean ok = event.getEntity() instanceof Player p
                ? Protection.mayPlayer(level, event.getPos(), Flags.BLOCK_TRAMPLING, p)
                : Protection.mayWorld(level, event.getPos(), Flags.BLOCK_TRAMPLING);
        if (!ok) {
            event.setCanceled(true);
        }
    }
}
