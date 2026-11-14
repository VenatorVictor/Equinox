package dev.venatorvictor.equinox.api;

/**
 * Where a dimension is in the year, worked out from its day time (the day count): day 0 is the first day of spring,
 * the spring equinox; the seasons start at the equinoxes and solstices.
 * Each season has three parts, early, mid and late; progress values allow finer steps where needed (colors).
 */
public record SeasonState(long dayTime, int daysPerSeason) {
    public static final int DAY = 24000;
    public static final int PARTS = 3;
    /** Texture steps: 14 a season ("weeks", about 6.5 calendar days), 56 a year, each a whole number of days. */
    public static final int WEEKS_PER_SEASON = 14;
    public static final int WEEKS = WEEKS_PER_SEASON * Season.COUNT;
    /** First week of each of the 6 old steps of a season (early/mid/late x start/end, 7 days each in a 42-day season). */
    private static final int[] OLD_STEP_WEEK = {0, 2, 5, 7, 9, 12, 14};

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

    /**
     * 0 .. 55: the week of the year (0 = the first days of spring). Whole days: with days_per_season a multiple of 14
     * every week has the same number of days (42: 3 days); otherwise a few weeks are a day longer.
     */
    public int week() {
        long day = ticksInYear() / DAY;
        return (int) Math.min(day * WEEKS_PER_SEASON / daysPerSeason, WEEKS - 1);
    }

    /** The first week of an old year step (0 .. 23: early_spring_start .. late_winter_end). */
    public static int stepStartWeek(int step) {
        return step / 6 * WEEKS_PER_SEASON + OLD_STEP_WEEK[step % 6];
    }

    /** The last week of an old year step. */
    public static int stepEndWeek(int step) {
        return step / 6 * WEEKS_PER_SEASON + OLD_STEP_WEEK[step % 6 + 1] - 1;
    }

    /** 0 for the first day of the season. */
    public int dayOfSeason() {
        return (int) (ticksInYear() % seasonTicks() / DAY);
    }

    /**
     * The state a share of a season earlier (1/3: a third of a season), e.g. the sea, which warms and cools later than
     * the land. A share, not days, so it scales with the season length.
     */
    public SeasonState lagged(double seasons) {
        return seasons == 0 ? this : new SeasonState(dayTime - Math.round(seasons * seasonTicks()), daysPerSeason);
    }

    /** 0 for the first year. */
    public long year() {
        return Math.floorDiv(dayTime, seasonTicks() * Season.COUNT);
    }
}
