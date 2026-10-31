package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla snowfall only snows on air; this lets it cover grass, ferns and flowers too (SnowyPlants). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSnowfallMixin {
    @Inject(method = "tickPrecipitation", at = @At("TAIL"))
    private void seasonsrework$snowOnPlants(BlockPos pos, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        SnowyPlants.onSnowfall(level, level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos));
    }
}
