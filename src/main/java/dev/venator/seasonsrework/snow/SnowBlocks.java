package dev.venator.seasonsrework.snow;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The block-entity snow (SnowyBlock), used with the snow storage option "block_entity". */
public final class SnowBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("seasonsrework");
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "seasonsrework");

    public static final DeferredBlock<SnowyBlock> SNOWY = BLOCKS.register("snowy_block", () -> new SnowyBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.SNOW).strength(0.1F).sound(SoundType.SNOW).noOcclusion().dynamicShape().randomTicks()
            .pushReaction(PushReaction.DESTROY).isViewBlocking((state, level, pos) -> false)));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SnowyBlockEntity>> SNOWY_ENTITY = BLOCK_ENTITIES.register("snowy_block",
            () -> BlockEntityType.Builder.of(SnowyBlockEntity::new, SNOWY.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    private SnowBlocks() {}
}
