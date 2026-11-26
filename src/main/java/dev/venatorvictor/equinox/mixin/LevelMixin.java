package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.daylength.DayLength;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seasonal day length: Level gets its own getTimeOfDay (vanilla only has LevelTimeAccess's default). The moon follows
 * the month: getMoonPhase (the sky) and getMoonBrightness (slimes, cats, local difficulty; vanilla reads the day count
 * there directly) come from the season's moon phase. With per-region weather (Weather), isRainingAt asks the storm
 * over the spot instead of the world's rain switch.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Shadow
    public abstract DimensionType dimensionType();

    @Shadow
    public abstract long getDayTime();

    @Shadow
    public abstract ResourceKey<Level> dimension();

    public float getTimeOfDay(float partialTick) {
        DimensionType type = dimensionType();
        if (type.fixedTime().isPresent() || !Config.hasSeasons(dimension())) return type.timeOfDay(getDayTime());
        return DayLength.timeOfDay(getDayTime(), Config.daysPerSeason());
    }

    public int getMoonPhase() {
        DimensionType type = dimensionType();
        if (!Config.hasSeasons(dimension())) return type.moonPhase(getDayTime());
        if (Config.realTime()) return dev.venatorvictor.equinox.realtime.RealTime.moonPhase();   // the real moon
        return new dev.venatorvictor.equinox.api.SeasonState(getDayTime(), Config.daysPerSeason(), Config.daysPerWeek()).moonPhase();
    }

    public float getMoonBrightness() {
        return DimensionType.MOON_BRIGHTNESS_PER_PHASE[getMoonPhase()];
    }

    /** Rain here: open sky, nothing above, a storm over the column and the biome rains (not snows) here. */
    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void equinox$stormHere(net.minecraft.core.BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (!dev.venatorvictor.equinox.weather.Weather.on(level)) return;
        cir.setReturnValue(dev.venatorvictor.equinox.weather.Weather.fallsAt(level, pos.getX(), pos.getZ()) && level.canSeeSky(pos)
                && level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY()
                && level.getBiome(pos).value().getPrecipitationAt(pos) == net.minecraft.world.level.biome.Biome.Precipitation.RAIN);
    }
}
