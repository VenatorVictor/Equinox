package dev.venatorvictor.equinox.mixin.compat;

import dev.venatorvictor.equinox.client.textures.SeasonalModel;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import snownee.snow.client.RenderAPI;

/**
 * The snow mod draws the plant inside a snow layer with the snow block's model data, so seasonal textures would
 * be skipped there. While it draws that plant, tell SeasonalModel where it is and that it's inside snow.
 * Also: it colors the plant's tinted quads itself and then lets the renderer tint them again with the same color
 * (darker than the same plant outside the snow), so once it has colored a quad, mark it as needing no more tint.
 * Not applied when that mod isn't installed.
 */
@Pseudo
@Mixin(targets = "snownee.snow.client.FabricRendererRenderAPI", remap = false)
public abstract class SnowModRenderMixin {
    @Shadow @Final private BlockAndTintGetter level;
    @Shadow @Final private BlockPos pos;

    @Inject(method = "render", at = @At("HEAD"))
    private void equinox$enterSnow(BlockState state, BakedModel model, double yOffset, RenderAPI.ModelPart part, CallbackInfoReturnable<Boolean> cir) {
        if (part == RenderAPI.ModelPart.CAMO) SeasonalModel.IN_SNOW.set(new SeasonalModel.InSnow(level, pos));
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void equinox$leaveSnow(BlockState state, BakedModel model, double yOffset, RenderAPI.ModelPart part, CallbackInfoReturnable<Boolean> cir) {
        SeasonalModel.IN_SNOW.remove();
    }

    // The quad transform lambda in render(): moves and colors each quad.
    @Inject(method = "lambda$render$0", at = @At("RETURN"))
    private void equinox$tintOnce(RenderAPI.ModelPart part, double yOffset, BlockState state, boolean expand, Vec3 offset,
                                       MutableQuadView quad, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && quad.colorIndex() != -1) quad.colorIndex(-1);
    }
}
