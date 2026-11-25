package dev.venatorvictor.equinox.api;

/**
 * Where a dimension is in the year, worked out from its day time (the day count): day 0 is the first day of spring,
 * the spring equinox; the seasons start at the equinoxes and solstices.
 * Weeks are days_per_week long, from the choices that fit the month (a third of a season): 24-day seasons 2 or 4,
 * 48: 4 or 8, 72: 4 or 6, 96: 8 (fitWeek).
 * Seasonal texture files and color tables use their own fixed grid of 12 "file weeks" a season (exact at 48 days);
 * fileWeek() places today on it by date.
 */
public record SeasonState(long dayTime, int daysPerSeason, int daysPerWeek) {
    public static final int DAY = 24000;
    /** The week length asked for when none is given. */
    public static final int DEFAULT_DAYS_PER_WEEK = 4;

    /** With the default week length (fitted to the season length). */
    public SeasonState(long dayTime, int daysPerSeason) {
        this(dayTime, daysPerSeason, fitWeek(daysPerSeason, DEFAULT_DAYS_PER_WEEK));
    }

    /** The week lengths a season length offers (each divides its month). */
    public static int[] weekChoices(int daysPerSeason) {
        return switch (daysPerSeason) {
            case 24 -> new int[]{2, 4};
            case 48 -> new int[]{4, 8};
            case 72 -> new int[]{4, 6};
            case 96 -> new int[]{8};
            default -> new int[]{4, 8};   // longer seasons typed in the file: months are a multiple of 8 days
        };
    }

    /** The wanted week length if the season length offers it, else the nearest offered one (the shorter on a tie). */
    public static int fitWeek(int daysPerSeason, int wanted) {
        int best = -1;
        for (int choice : weekChoices(daysPerSeason))
            if (best < 0 || Math.abs(choice - wanted) < Math.abs(best - wanted)) best = choice;
        return best;
    }
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

    /** Weeks in a season: days_per_season / days_per_week (12 with the defaults). */
    public int weeksPerSeason() {
        return Math.max(1, daysPerSeason / Math.max(1, daysPerWeek));
    }

    /** Weeks in a year. */
    public int weeks() {
        return weeksPerSeason() * Season.COUNT;
    }

    /** 0 .. weeksPerSeason - 1: the week of the season. */
    public int weekOfSeason() {
        return Math.min(dayOfSeason() / Math.max(1, daysPerWeek), weeksPerSeason() - 1);
    }

    /** Counts up through the years: the week of the year plus weeks() per year. Changes when the week changes. */
    public long weeksSinceStart() {
        return year() * weeks() + week();
    }

    /** 0 .. weeks() - 1: the week of the year (0 = the first week of spring). */
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

    /** Days in a month: a third of a season (8, 16, 24 or 32). The calendar's dates are in GameYear / CalendarDates. */
    public int monthDays() {
        return Math.max(1, daysPerSeason / 3);
    }

    /** Real mean lunar month and calendar month, in days: the moon's cycle keeps their ratio. */
    public static final double REAL_MOON = 29.530589, REAL_MONTH = 365.2425 / 12;

    /** Days in the moon's cycle: a little shorter than a month, with decimals (15.52 with 16-day months). */
    public double moonCycle() {
        return monthDays() * REAL_MOON / REAL_MONTH;
    }

    /**
     * The moon's day: it turns at noon (day time 6000), when the moon isn't up, so the phase never changes in the sky.
     * A night belongs to the day it starts on.
     */
    public long moonDay() {
        return Math.floorDiv(dayTime - 6000, DAY);
    }

    /**
     * 0 new moon, 1 waxing crescent .. 4 full .. 7 waning crescent. The world starts at a new moon.
     * REAL: the cycle a little shorter than a month (moonCycle), so the new moon moves through the months.
     * MONTHLY: one cycle a month, the new moon on the 1st, each phase a month / 8 days (the last one takes what's left).
     */
    public int moonStep() {
        long day = moonDay();
        if (dev.venatorvictor.equinox.Config.moonCycle() == dev.venatorvictor.equinox.Config.MoonCycle.MONTHLY) {
            int ofMonth = (int) Math.floorMod(GameYear.calendarDay(day, daysPerSeason), (long) monthDays());
            return Math.min(ofMonth / Math.max(1, monthDays() / 8), 7);
        }
        double into = (day + 0.5) / moonCycle();
        return Math.min((int) Math.floor((into - Math.floor(into)) * 8), 7);
    }

    /** Vanilla's moon phase index (0 full, 4 new). */
    public int moonPhase() {
        return (moonStep() + 4) % 8;
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
        return seasons == 0 ? this : new SeasonState(dayTime - Math.round(seasons * seasonTicks()), daysPerSeason, daysPerWeek);
    }

    /** 0 for the first year (years of the seasons, starting at the spring equinox). */
    public long year() {
        return Math.floorDiv(dayTime, seasonTicks() * Season.COUNT);
    }
}
