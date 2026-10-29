package dev.venator.seasonsrework.api;

import dev.venator.seasonsrework.Config;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Entry point for other code: the season of a level, on either side. */
public final class Seasons {
    /** The level's season, or null where there are no seasons (dimensions not in the config). */
    public static @Nullable SeasonState get(Level level) {
        if (!Config.hasSeasons(level.dimension())) return null;
        return new SeasonState(level.getDayTime(), Config.daysPerSeason());
    }

    private Seasons() {}
}
