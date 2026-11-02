package dev.venatorvictor.equinox.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPosMutable;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import dev.venatorvictor.equinox.client.dh.DhSnow;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Distant Horizons 3.3.3: builds the LOD color column from saved block data. Hooks two spots in it. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer", remap = false)
public abstract class FullDataToRenderDataTransformerMixin {
    private static final String GET_BLOCK_COLOR = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;getBlockColor(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos;Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IBiomeWrapper;Lcom/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2;Lcom/seibel/distanthorizons/core/wrapperInterfaces/block/IBlockStateWrapper;)I";

    // The block read from the saved data: ice on cold water, saved snow/ice removed when it's warm.
    @ModifyExpressionValue(method = "setRenderColumnView", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/dataObjects/fullData/FullDataPointIdMap;getBlockStateWrapper(I)Lcom/seibel/distanthorizons/core/wrapperInterfaces/block/IBlockStateWrapper;"))
    private static IBlockStateWrapper equinox$seasonalBlock(IBlockStateWrapper block,
                                                                 @Local(argsOnly = true) IClientLevelWrapper levelWrapper,
                                                                 @Local(argsOnly = true) LongArrayList fullColumnData,
                                                                 @Local(argsOnly = true) DhBlockPosMutable mutableBlockPos,
                                                                 @Local FullDataPointIdMap fullDataMapping,
                                                                 @Local(name = "biome") IBiomeWrapper biome,
                                                                 @Local(name = "fullDataIndex") int fullDataIndex,
                                                                 @Local(name = "topY") int topY) {
        return DhSnow.replaceBlock(block, levelWrapper, biome, fullColumnData, fullDataMapping, fullDataIndex,
                mutableBlockPos.getX(), topY + levelWrapper.getMinHeight(), mutableBlockPos.getZ());
    }

    // ordinal 2: plants and other non-colliding blocks; ordinal 3: every other block.
    @ModifyExpressionValue(method = "setRenderColumnView", require = 0, at = {
            @At(value = "INVOKE", target = GET_BLOCK_COLOR, ordinal = 2),
            @At(value = "INVOKE", target = GET_BLOCK_COLOR, ordinal = 3)})
    private static int equinox$snowColor(int color,
                                              @Local(argsOnly = true) IClientLevelWrapper levelWrapper,
                                              @Local(argsOnly = true) FullDataSourceV2 fullDataSource,
                                              @Local(argsOnly = true) LongArrayList fullColumnData,
                                              @Local(argsOnly = true) DhBlockPosMutable mutableBlockPos,
                                              @Local FullDataPointIdMap fullDataMapping,
                                              @Local(name = "biome") IBiomeWrapper biome,
                                              @Local(name = "block") IBlockStateWrapper block,
                                              @Local(name = "fullDataIndex") int fullDataIndex,
                                              @Local(name = "topY") int topY,
                                              @Local(name = "skyLight") int skyLight,
                                              @Local(name = "blockLight") int blockLight) {
        return DhSnow.color(color, levelWrapper, biome, fullDataSource, block, mutableBlockPos, fullColumnData, fullDataMapping,
                fullDataIndex, topY, skyLight, blockLight);
    }
}
