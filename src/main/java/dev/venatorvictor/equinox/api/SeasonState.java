package dev.venatorvictor.equinox.api;

/**
 * Where a dimension is in the year, worked out from its day time (the day count): day 0 is the first day of spring,
 * the spring equinox; the seasons start at the equinoxes and solstices.
 * Weeks are 4 days: 2 a month at 24-day seasons, 4 at 48, 6 at 72, 8 at 96 (a month is a third of a season).
 * Seasonal texture files and color tables use their own fixed grid of 12 "file weeks" a season (exact at 48 days);
 * fileWeek() places today on it by date.
 */
public record SeasonState(long dayTime, int daysPerSeason) {
    public static final int DAY = 24000;
    /** Days in a week, at every season length. */
    public static final int DAYS_PER_WEEK = 4;
    /** The grid of the season files and color tables: 12 weeks a season, 48 a year, by date. */
    public static final int FILE_WEEKS_PER_SEASON = 12;
    public static final int FILE_WEEKS = FILE_WEEKS_PER_SEASON * Season.COUNT;

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

    /** Weeks in a season: days_per_season / 4 (6, 12, 18 or 24). */
    public int weeksPerSeason() {
        return weeksPerSeason(daysPerSeason);
    }

    public static int weeksPerSeason(int daysPerSeason) {
        return Math.max(1, daysPerSeason / DAYS_PER_WEEK);
    }

    /** Weeks in a year. */
    public int weeks() {
        return weeksPerSeason() * Season.COUNT;
    }

    /** 0 .. weeksPerSeason - 1: the week of the season. */
    public int weekOfSeason() {
        return Math.min(dayOfSeason() / DAYS_PER_WEEK, weeksPerSeason() - 1);
    }

    /** Counts up through the years: the week of the year plus weeks() per year. Changes when the week changes. */
    public long weeksSinceStart() {
        return year() * weeks() + week();
    }

    /** 0 .. weeks() - 1: the week of the year (0 = the first 4 days of spring). */
    public int week() {
        return season().ordinal() * weeksPerSeason() + weekOfSeason();
    }

    /** The file week (0 .. 47, 12 a season) holding the middle of the current week, by date. */
    public int fileWeek() {
        return fileWeekOf(week(), weeksPerSeason());
    }

    /** The file week (0 .. 47) holding the middle of a world week, with that many weeks a season. */
    public static int fileWeekOf(int week, int weeksPerSeason) {
        int season = week / weeksPerSeason, inSeason = week % weeksPerSeason;
        return season * FILE_WEEKS_PER_SEASON
                + Math.min((int) ((inSeason + 0.5) * FILE_WEEKS_PER_SEASON / weeksPerSeason), FILE_WEEKS_PER_SEASON - 1);
    }

    /** The file week of the year (0 .. 47) of a season's file week 1 .. 12. */
    public static int fileWeek(Season season, int week) {
        return season.ordinal() * FILE_WEEKS_PER_SEASON + week - 1;
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
