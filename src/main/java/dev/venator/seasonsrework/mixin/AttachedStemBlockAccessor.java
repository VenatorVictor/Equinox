package dev.venator.seasonsrework.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The fruit an attached stem holds (pumpkin, melon), which frost and heat kill with it. */
@Mixin(AttachedStemBlock.class)
public interface AttachedStemBlockAccessor {
    @Accessor("fruit")
    ResourceKey<Block> seasonsrework$fruit();
}
