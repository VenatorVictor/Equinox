package dev.venatorvictor.equinox.compat.blockinfo;

import net.minecraft.world.level.block.Block;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Block info tooltips (optional, found through the annotation): crop states in its tooltip. */
@WailaPlugin
public final class BlockInfoPlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CropStateProvider.INSTANCE, Block.class);
    }
}
