package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.daylength.DayLength;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Seasonal day length: Level gets its own getTimeOfDay (vanilla only has LevelTimeAccess's default). The moon follows
 * the month: getMoonPhase (the sky) and getMoonBrightness (slimes, cats, local difficulty; vanilla reads the day count
 * there directly) come from the season's moon phase.
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
        return new dev.venatorvictor.equinox.api.SeasonState(getDayTime(), Config.daysPerSeason()).moonPhase();
    }

    public float getMoonBrightness() {
        return DimensionType.MOON_BRIGHTNESS_PER_PHASE[getMoonPhase()];
    }
}
