package dev.venatorvictor.equinox.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.venatorvictor.equinox.client.CloudRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Weather per region: our clouds (CloudRenderer, drawn from the storms) instead of vanilla's. */
@Mixin(LevelRenderer.class)
public abstract class CloudsMixin {
    @Shadow
    private ClientLevel level;

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void equinox$stormClouds(PoseStack poseStack, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
                                     double camX, double camY, double camZ, CallbackInfo ci) {
        if (!CloudRenderer.active(level)) return;
        CloudRenderer.render(level, poseStack, frustumMatrix, projectionMatrix, partialTick, camX, camY, camZ);
        ci.cancel();
    }
}
