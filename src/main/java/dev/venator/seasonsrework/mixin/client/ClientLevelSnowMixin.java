package dev.venator.seasonsrework.mixin.client;

import dev.venator.seasonsrework.client.SnowAnimation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block changes from the server (not the player's own predicted ones): snow coming or going is animated; more snow is held back until it has risen. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSnowMixin {
    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$snowAnimation(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ClientLevel level = (ClientLevel) (Object) this;
        if (SnowAnimation.onServerChange(level, pos, level.getBlockState(pos), state)) ci.cancel(); // more snow: put in after the rise
    }
}
