package dev.venator.seasonsrework.api;

/**
 * Where a dimension is in the year, worked out from its day time (the day count): day 0 is the first day of spring.
 * Each season has three parts, early, mid and late; progress values allow finer steps where needed (colors).
 */
public record SeasonState(long dayTime, int daysPerSeason) {
    public static final int DAY = 24000;
    public static final int PARTS = 3;

    public long seasonTicks() {
        return (long) daysPerSeason * DAY;
    }

    /** Ticks into the current year. */
    public long ticksInYear() {
        return Math.floorMod(dayTime, seasonTicks() * Season.COUNT);
    }

    /** 0 .. 1 through the year (0: first day of spring). */
    public double yearProgress() {
        return (double) ticksInYear() / (seasonTicks() * Season.COUNT);
    }

    public Season season() {
        return Season.VALUES[(int) (ticksInYear() / seasonTicks())];
    }

    /** 0 .. 1 through the current season. */
    public float seasonProgress() {
        return (float) (ticksInYear() % seasonTicks()) / seasonTicks();
    }

    /** 0 = early, 1 = mid, 2 = late. */
    public int part() {
        return Math.min((int) (seasonProgress() * PARTS), PARTS - 1);
    }

    /** Counts up through the years: season * PARTS + part, plus PARTS * 4 per year. Changes when the part changes. */
    public long partsSinceStart() {
        return Math.floorDiv(dayTime, seasonTicks() * Season.COUNT) * Season.COUNT * PARTS + season().ordinal() * PARTS + part();
    }

    /** 0 for the first day of the season. */
    public int dayOfSeason() {
        return (int) (ticksInYear() % seasonTicks() / DAY);
    }

    /**
     * The state a share of a season earlier (1/3: a third of a season): the temperature, crops and colors run behind
     * the calendar (Config temperature_lag). A share, not days, so it scales with the season length.
     */
    public SeasonState lagged(double seasons) {
        return seasons == 0 ? this : new SeasonState(dayTime - Math.round(seasons * seasonTicks()), daysPerSeason);
    }

    /** 0 for the first year. */
    public long year() {
        return Math.floorDiv(dayTime, seasonTicks() * Season.COUNT);
    }
}
