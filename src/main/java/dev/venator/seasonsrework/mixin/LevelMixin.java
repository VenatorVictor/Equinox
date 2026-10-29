package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.daylength.DayLength;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Seasonal day length: Level gets its own getTimeOfDay (vanilla only has LevelTimeAccess's default). */
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
}
