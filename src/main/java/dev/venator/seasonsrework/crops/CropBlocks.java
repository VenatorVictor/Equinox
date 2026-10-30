package dev.venator.seasonsrework.crops;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The dead crop block (no item: it only appears when a crop dies). */
public final class CropBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("seasonsrework");
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "seasonsrework");

    public static final DeferredBlock<DeadCropBlock> DEAD_CROP = BLOCKS.register("dead_crop", () -> new DeadCropBlock(
            BlockBehaviour.Properties.of().noCollission().instabreak().sound(SoundType.CROP).pushReaction(PushReaction.DESTROY)
                    .replaceable().noLootTable().randomTicks()));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DeadCropBlockEntity>> DEAD_CROP_ENTITY = BLOCK_ENTITIES.register("dead_crop",
            () -> BlockEntityType.Builder.of(DeadCropBlockEntity::new, DEAD_CROP.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    private CropBlocks() {}
}
