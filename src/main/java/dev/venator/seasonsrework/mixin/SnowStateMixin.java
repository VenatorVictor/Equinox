package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A plant or fence with snow in it (SnowyPlants) acts as its snow too (and has no random offset): snow's outline and collision added to its own,
 * snow's sound, snow's mining speed (the snow breaks first, BlockStateSnowBreakMixin), and random ticks to melt.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class SnowStateMixin {
    @Shadow
    private boolean isRandomlyTicking;

    @Shadow
    protected abstract BlockState asState();

    /** Snow layers in this state, set once in initCache (0: none, or the block can't hold snow). */
    @Unique
    private int seasonsrework$snow;

    @Inject(method = "initCache", at = @At("TAIL"))
    private void seasonsrework$cacheSnow(CallbackInfo ci) {
        seasonsrework$snow = SnowyPlants.layers(asState());
        if (seasonsrework$snow > 0) isRandomlyTicking = true;
    }

    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private void seasonsrework$snowShape(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        // The plant's own shape where it's drawn (with its offset, which the snowy state itself doesn't have).
        if (seasonsrework$snow > 0)
            cir.setReturnValue(Shapes.or(SnowyPlants.unsnowed(asState()).getShape(level, pos, context), SnowyPlants.shape(seasonsrework$snow)));
    }

    /** No random offset with snow inside: the snow sits like a vanilla layer; SnowyPlantModel moves the plant itself. */
    @Inject(method = "getOffset", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$noOffset(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Vec3> cir) {
        if (seasonsrework$snow > 0) cir.setReturnValue(Vec3.ZERO);
    }

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private void seasonsrework$snowCollision(BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        seasonsrework$addCollision(cir);
    }

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"), cancellable = true)
    private void seasonsrework$snowCollision(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        seasonsrework$addCollision(cir);
    }

    @Unique
    private void seasonsrework$addCollision(CallbackInfoReturnable<VoxelShape> cir) {
        if (seasonsrework$snow < 2) return; // one layer has no collision (as vanilla snow)
        VoxelShape snow = SnowyPlants.collision(seasonsrework$snow), own = cir.getReturnValue();
        cir.setReturnValue(own.isEmpty() ? snow : Shapes.or(own, snow));
    }

    @Inject(method = "getSoundType", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$snowSound(CallbackInfoReturnable<SoundType> cir) {
        if (seasonsrework$snow > 0) cir.setReturnValue(SoundType.SNOW);
    }

    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$snowProgress(Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (seasonsrework$snow > 0)
            cir.setReturnValue(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, seasonsrework$snow).getDestroyProgress(player, level, pos));
    }

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$melt(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        // Melted: the plant's own tick would work on the old state, so it waits for the next one.
        if (seasonsrework$snow > 0 && SnowyPlants.randomTick(asState(), level, pos)) ci.cancel();
    }
}
