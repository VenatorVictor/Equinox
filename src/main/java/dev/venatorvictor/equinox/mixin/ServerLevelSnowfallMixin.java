package dev.venatorvictor.equinox.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.snow.SnowyPlants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla snowfall only snows on air; this lets it cover grass, ferns and flowers too (SnowyPlants). And water only
 * freezes while it snows (vanilla freezes it whenever it's cold), like the snow cover.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSnowfallMixin {
    @Inject(method = "tickPrecipitation", at = @At("TAIL"))
    private void equinox$snowOnPlants(BlockPos pos, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        SnowyPlants.onSnowfall(level, level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos));
    }

    @WrapOperation(method = "tickPrecipitation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean equinox$freezeWhileSnowing(Biome biome, LevelReader reader, BlockPos pos, Operation<Boolean> original) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (Config.hasSeasons(level.dimension()) && !(level.isRaining() && biome.hasPrecipitation())) return false;
        return original.call(biome, reader, pos);
    }
}
