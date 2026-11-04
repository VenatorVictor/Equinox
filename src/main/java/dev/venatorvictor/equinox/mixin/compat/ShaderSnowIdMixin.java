package dev.venatorvictor.equinox.mixin.compat;

import com.llamalad7.mixinextras.sugar.Local;
import dev.venatorvictor.equinox.client.ShaderIds;
import dev.venatorvictor.equinox.snow.SnowyPlants;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With shaders, every vertex carries a block id the shader pack uses to wave and light it. The chunk renderer asks a
 * model for its quads once per cull side, then once with none, and the shader loader sets the id before each ask.
 * A plant with snow in it: the quads with a cull side are all its snow (SnowyPlantModel), so they get the snow
 * layer's id (still, lit as snow); the ones with none keep the plant's (the plant and the snow on it wave). Runs after
 * the shader loader's own hook at the same spot (priority). The shader loader's classes are found by name; without
 * them, or without a shader pack, nothing happens.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.frapi.render.AbstractBlockRenderContext", remap = false, priority = 1500)
public abstract class ShaderSnowIdMixin {
    @Inject(method = "bufferDefaultModel", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/services/PlatformModelAccess;getQuads(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;Lnet/minecraft/client/renderer/RenderType;Lnet/caffeinemc/mods/sodium/client/services/SodiumModelData;)Ljava/util/List;"))
    private void equinox$snowId(BakedModel model, BlockState state, CallbackInfo ci, @Local Direction cullFace) {
        int layers = SnowyPlants.stateLayers(state);
        if (layers == 0 || !ShaderIds.ready(this)) return;
        ShaderIds.set(this, cullFace == null ? null : Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
    }
}
