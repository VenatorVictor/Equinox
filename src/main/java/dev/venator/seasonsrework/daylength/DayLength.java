package dev.venator.seasonsrework.daylength;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.api.SeasonState;
import dev.venator.seasonsrework.api.Seasons;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;

/**
 * Seasonal day length. The clock stays uniform (24000 ticks a day, noon at 6000, midnight at 18000); what changes is
 * where the sun is at each tick: in summer it rises earlier and sets later, in winter the other way round. Vanilla
 * reads everything from that sun position (sky darkness, mob spawning, beds, undead burning, daylight sensors, sky
 * and sunrise colors, stars, the clock item), on both sides, so all of it follows.
 *
 * How long the sun is up comes from the latitude and the date, as on Earth (longest day at mid summer, equinoxes at
 * mid spring and mid autumn), scaled so an equinox day is vanilla's. Latitude 0: vanilla days all year.
 */
public final class DayLength {
    /** Vanilla's sun is up for 2 * SUN_UP of the day (about 13.6 hours): where its curve crosses the horizon. */
    private static final double SUN_UP = solve(d -> curve(d) - 0.25, 0, 0.5);
    private static final double TILT = Math.toRadians(23.44);
    // Beyond these the warp would run backwards (and polar days/nights are no fun in a game).
    private static final double MIN_HALF = 0.17, MAX_HALF = 0.37;

    /** Level.getTimeOfDay for a dimension with seasons (LevelMixin). */
    public static float timeOfDay(long dayTime, int daysPerSeason) {
        double half = halfDay(dayTime, daysPerSeason);
        double fromNoon = Mth.frac(dayTime / 24000.0 - 0.25);
        return (float) curve(warp(fromNoon, half));
    }

    /** Hours of daylight (sun up) on a given day time, for /season. */
    public static double daylightHours(long dayTime, int daysPerSeason) {
        return halfDay(dayTime, daysPerSeason) * 2 * 24;
    }

    /** Waking up: vanilla wakes at day time 0 (sun just up); we wake when the sun is just as high. */
    public static void onSleepFinished(SleepFinishedTimeEvent event) {
        Level level = (Level) event.getLevel();
        if (level.dimensionType().fixedTime().isPresent() || Seasons.get(level) == null) return;
        long morning = event.getNewTime();
        int days = Config.daysPerSeason();
        double half = halfDay(morning, days);
        double vanillaWake = 0.75; // day time 0, as time from noon
        double wake = solve(s -> warp(s, half) - vanillaWake, 0.5, 1); // same sun height on our curve
        long offset = Math.round((wake - vanillaWake) * 24000); // negative in summer: earlier
        event.setTimeAddition(morning + offset);
    }

    /** Half the fraction of the day the sun is up. */
    private static double halfDay(long dayTime, int daysPerSeason) {
        double latitude = Math.toRadians(Config.latitude());
        if (latitude == 0) return SUN_UP;
        double year = new SeasonState(dayTime, daysPerSeason).yearProgress();
        double declination = TILT * Math.sin(2 * Math.PI * (year - 0.125)); // highest at mid summer (0.375)
        double cosHour = Mth.clamp(-Math.tan(latitude) * Math.tan(declination), -1, 1);
        double earthDay = Math.acos(cosHour) / Math.PI; // 0.5 at the equinoxes
        return Mth.clamp(SUN_UP + (earthDay - 0.5) / 2, MIN_HALF, MAX_HALF);
    }

    /**
     * Moves the time from noon (0 = noon, 0.5 = midnight) so the sun reaches the horizon at +-half instead of
     * +-SUN_UP. A smooth curve (no sudden change of sun speed), symmetric around noon and midnight.
     */
    private static double warp(double fromNoon, double half) {
        double k = (half - SUN_UP) * 2 * Math.PI / Math.sin(2 * Math.PI * half);
        return fromNoon - k * Math.sin(2 * Math.PI * fromNoon) / (2 * Math.PI);
    }

    /** Vanilla's DimensionType.timeOfDay curve, from the time from noon. */
    private static double curve(double fromNoon) {
        return (fromNoon * 2 + (0.5 - Math.cos(fromNoon * Math.PI) / 2)) / 3;
    }

    /** Bisection for an increasing function's zero. */
    private static double solve(java.util.function.DoubleUnaryOperator f, double low, double high) {
        for (int i = 0; i < 50; i++) {
            double mid = (low + high) / 2;
            if (f.applyAsDouble(mid) < 0) low = mid;
            else high = mid;
        }
        return (low + high) / 2;
    }

    private DayLength() {}
}
