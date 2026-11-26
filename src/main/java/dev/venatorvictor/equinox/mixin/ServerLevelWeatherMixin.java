package dev.venatorvictor.equinox.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.venatorvictor.equinox.weather.ServerWeather;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-region weather (Weather): the world's own rain switch stays off, so only storms rain, each where it is.
 * Lightning strikes only under a storm's thunder core, rain and snow fall (snow layers, cauldrons) only under a storm,
 * and /weather sets the storm override instead of the world's switch.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {
    @Shadow
    @Final
    private ServerLevelData serverLevelData;

    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"))
    private void equinox$noWorldRain(CallbackInfo ci) {
        if (!Weather.on((ServerLevel) (Object) this)) return;
        serverLevelData.setRaining(false);
        serverLevelData.setThundering(false);
    }

    // The world's weather timers stand still (the rain level still fades out and players are told).
    @WrapOperation(method = "advanceWeatherCycle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean equinox$noWeatherCycle(GameRules rules, GameRules.Key<GameRules.BooleanValue> key, Operation<Boolean> original) {
        if (Weather.on((ServerLevel) (Object) this)) return false;
        return original.call(rules, key);
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
    private boolean equinox$stormOverChunk(ServerLevel level, Operation<Boolean> original, @Local(argsOnly = true) LevelChunk chunk) {
        if (!Weather.on(level)) return original.call(level);
        ChunkPos pos = chunk.getPos();
        return Weather.thunderAt(level, pos.getMiddleBlockX(), pos.getMiddleBlockZ());
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isThundering()Z"))
    private boolean equinox$thunderOverChunk(ServerLevel level, Operation<Boolean> original, @Local(argsOnly = true) LevelChunk chunk) {
        if (!Weather.on(level)) return original.call(level);
        ChunkPos pos = chunk.getPos();
        return Weather.thunderAt(level, pos.getMiddleBlockX(), pos.getMiddleBlockZ());
    }

    @WrapOperation(method = "tickPrecipitation", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
    private boolean equinox$stormOverColumn(ServerLevel level, Operation<Boolean> original, @Local(argsOnly = true) BlockPos pos) {
        if (!Weather.on(level)) return original.call(level);
        return Weather.fallsAt(level, pos.getX(), pos.getZ());
    }

    @Inject(method = "setWeatherParameters", at = @At("HEAD"), cancellable = true)
    private void equinox$weatherCommand(int clearTime, int weatherTime, boolean raining, boolean thundering, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!Weather.on(level)) return;
        ServerWeather.command(level, clearTime, weatherTime, raining, thundering);
        ci.cancel();
    }
}
