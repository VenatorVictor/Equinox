package dev.venatorvictor.equinox.compat.blockinfo;

import dev.venatorvictor.equinox.crops.CropBlocks;
import dev.venatorvictor.equinox.crops.DeadCropBlock;
import dev.venatorvictor.equinox.crops.DeadFruitBlock;
import net.minecraft.world.level.block.Block;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Block info tooltips (optional, found through the annotation): dead crops and crop states in its tooltip. */
@WailaPlugin
public final class BlockInfoPlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockIcon(DeadCropProvider.INSTANCE, DeadCropBlock.class);
        registration.registerBlockIcon(DeadCropProvider.INSTANCE, DeadFruitBlock.class);
        registration.registerBlockComponent(DeadCropProvider.INSTANCE, DeadCropBlock.class);
        registration.registerBlockComponent(DeadCropProvider.INSTANCE, DeadFruitBlock.class);
        registration.registerBlockComponent(CropStateProvider.INSTANCE, Block.class);
    }
}
