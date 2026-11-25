package dev.venatorvictor.equinox.api;

/**
 * Where a dimension is in the year, worked out from its day time (the day count): day 0 is the first day of spring,
 * the spring equinox; the seasons start at the equinoxes and solstices.
 * Each season has 16 weeks of whole days; progress values allow finer steps where needed (colors).
 */
public record SeasonState(long dayTime, int daysPerSeason) {
    public static final int DAY = 24000;
    /** Texture steps: 16 a season ("weeks", about 5.7 calendar days), 64 a year, each a whole number of days. */
    public static final int WEEKS_PER_SEASON = 16;
    public static final int WEEKS = WEEKS_PER_SEASON * Season.COUNT;

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

    /** 0 .. 15: the week of the season. */
    public int weekOfSeason() {
        return week() % WEEKS_PER_SEASON;
    }

    /** Counts up through the years: the week of the year plus 64 per year. Changes when the week changes. */
    public long weeksSinceStart() {
        return year() * WEEKS + week();
    }

    /**
     * 0 .. 63: the week of the year (0 = the first days of spring). Whole days: with days_per_season a multiple of 16
     * every week has the same number of days (48: 3 days); otherwise a few weeks are a day longer.
     */
    public int week() {
        long day = ticksInYear() / DAY;
        return (int) Math.min(day * WEEKS_PER_SEASON / daysPerSeason, WEEKS - 1);
    }

    /** The week of the year (0 .. 63) of a season's week 1 .. 16. */
    public static int week(Season season, int week) {
        return season.ordinal() * WEEKS_PER_SEASON + week - 1;
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
