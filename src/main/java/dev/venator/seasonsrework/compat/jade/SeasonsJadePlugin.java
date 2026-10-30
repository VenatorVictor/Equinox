package dev.venator.seasonsrework.compat.jade;

import dev.venator.seasonsrework.crops.CropBlocks;
import dev.venator.seasonsrework.crops.DeadCropBlock;
import dev.venator.seasonsrework.crops.DeadFruitBlock;
import net.minecraft.world.level.block.Block;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade (optional, found by Jade through the annotation): dead crops and crop states in its tooltip. */
@WailaPlugin
public final class SeasonsJadePlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockIcon(DeadCropProvider.INSTANCE, DeadCropBlock.class);
        registration.registerBlockIcon(DeadCropProvider.INSTANCE, DeadFruitBlock.class);
        registration.registerBlockComponent(DeadCropProvider.INSTANCE, DeadCropBlock.class);
        registration.registerBlockComponent(DeadCropProvider.INSTANCE, DeadFruitBlock.class);
        registration.registerBlockComponent(CropStateProvider.INSTANCE, Block.class);
    }
}
