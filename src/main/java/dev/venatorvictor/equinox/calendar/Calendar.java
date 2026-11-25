package dev.venatorvictor.equinox.calendar;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The calendar: one item that hangs on a wall as a block (used on a wall's side) and opens the calendar pages (used
 * anywhere else, or by using the hanging one). Held, it shows the date at the top left of the screen.
 */
public final class Calendar {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("equinox");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("equinox");
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "equinox");

    public static final DeferredBlock<CalendarBlock> BLOCK = BLOCKS.register("calendar", () -> new CalendarBlock(
            BlockBehaviour.Properties.of().strength(0.5F).sound(SoundType.WOOD).noCollission().noOcclusion()
                    .pushReaction(PushReaction.DESTROY)));
    public static final DeferredItem<CalendarItem> ITEM = ITEMS.register("calendar",
            () -> new CalendarItem(BLOCK.get(), new Item.Properties().stacksTo(16)));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CalendarBlockEntity>> BLOCK_ENTITY = BLOCK_ENTITIES.register("calendar",
            () -> BlockEntityType.Builder.of(CalendarBlockEntity::new, BLOCK.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(ITEM.get());
        });
    }

    private Calendar() {}
}
