package dev.venatorvictor.equinox.calendar;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.loading.FMLEnvironment;

/** Used on the side of a wall: hangs there. Used anywhere else: opens the calendar pages. */
public final class CalendarItem extends BlockItem {
    public CalendarItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getClickedFace().getAxis().isVertical()) return InteractionResult.PASS;   // falls through to use()
        InteractionResult result = super.useOn(context);
        return result == InteractionResult.FAIL ? InteractionResult.PASS : result;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide && FMLEnvironment.dist.isClient())
            dev.venatorvictor.equinox.client.calendar.CalendarScreen.open();
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
