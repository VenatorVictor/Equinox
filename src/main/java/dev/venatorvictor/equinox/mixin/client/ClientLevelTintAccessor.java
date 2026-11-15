package dev.venatorvictor.equinox.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level's color caches, to forget one chunk's colors when its climate blend arrives (ChunkRefresher.blendArrived). */
@Mixin(ClientLevel.class)
public interface ClientLevelTintAccessor {
    @Accessor("tintCaches")
    Object2ObjectArrayMap<ColorResolver, BlockTintCache> equinox$tintCaches();
}
