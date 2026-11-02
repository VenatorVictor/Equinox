package dev.venatorvictor.equinox.crops;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The dead crop and dead fruit blocks (no items: they only appear when a crop dies). */
public final class CropBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("equinox");
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "equinox");

    public static final DeferredBlock<DeadCropBlock> DEAD_CROP = BLOCKS.register("dead_crop", () -> new DeadCropBlock(
            BlockBehaviour.Properties.of().noCollission().instabreak().sound(SoundType.CROP).pushReaction(PushReaction.DESTROY)
                    .replaceable().noLootTable().randomTicks()));
    public static final DeferredBlock<DeadFruitBlock> DEAD_FRUIT = BLOCKS.register("dead_fruit", () -> new DeadFruitBlock(
            BlockBehaviour.Properties.of().strength(1.0F).sound(SoundType.WOOD).pushReaction(PushReaction.DESTROY).noLootTable()));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DeadCropBlockEntity>> DEAD_CROP_ENTITY = BLOCK_ENTITIES.register("dead_crop",
            () -> BlockEntityType.Builder.of(DeadCropBlockEntity::new, DEAD_CROP.get(), DEAD_FRUIT.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    private CropBlocks() {}
}
