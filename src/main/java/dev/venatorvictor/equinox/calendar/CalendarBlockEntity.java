package dev.venatorvictor.equinox.calendar;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** No data: it only lets the client draw the hanging calendar's live season strip and days. */
public final class CalendarBlockEntity extends BlockEntity {
    public CalendarBlockEntity(BlockPos pos, BlockState state) {
        super(Calendar.BLOCK_ENTITY.get(), pos, state);
    }
}
