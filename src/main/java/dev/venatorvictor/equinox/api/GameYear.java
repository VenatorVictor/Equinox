package dev.venatorvictor.equinox.api;

import dev.venatorvictor.equinox.Config;

/**
 * Game time's year, as on Earth: the four seasons have uneven lengths in the real proportions (Earth moves faster
 * near the Sun in January): from the March equinox 92.76 days, from the June solstice 93.65, from the September
 * equinox 89.84, from the December solstice 88.99. North: spring and summer are longer; south (negative latitude):
 * autumn and winter, and the months move with them (spring from September).
 *
 * The rest of Equinox reads equal seasons (SeasonState, days_per_season each). internal() stretches each calendar
 * season over them, so the season changes on the day the calendar shows the equinox or solstice. The world's day 0
 * is the spring equinox.
 */
public final class GameYear {
    /**
     * Equinox's days start at midnight (vanilla's day time 0 is 6:00): the date, the season, the weeks and colour steps
     * turn at midnight, in the dark. Day and season reading goes through day() and seasonTime().
     */
    public static final int MIDNIGHT = 6000;

    /** Equinox's day number for the world's day time (turns at midnight). */
    public static long day(long worldDayTime) {
        return Math.floorDiv(worldDayTime + MIDNIGHT, SeasonState.DAY);
    }

    /** The day time the season is read from (SeasonState): from midnight, the calendar's seasons stretched to equal ones. */
    public static long seasonTime(long worldDayTime, int daysPerSeason) {
        return internal(worldDayTime + MIDNIGHT, daysPerSeason);
    }

    /** The world's day time on Equinox's day, at the same time of day as now (keeping the clock, like sleeping). */
    public static long atDay(long day, long worldDayTimeNow) {
        return day * SeasonState.DAY - MIDNIGHT + Math.floorMod(worldDayTimeNow + MIDNIGHT, SeasonState.DAY);
    }

    /** Where the March equinox falls in the year (from January 1), and the real stretches from it, in days. */
    private static final double MARCH = 0.2146;
    private static final double[] STRETCHES = {92.76, 93.65, 89.84, 88.99};

    private record Marks(int daysPerSeason, int[] calendarDays) {}
    private static volatile Marks cache = new Marks(0, new int[4]);

    /** The calendar day (0 = January 1) of the March, June, September and December marks, in a year of 4 seasons. */
    public static int[] marks(int daysPerSeason) {
        Marks c = cache;
        if (c.daysPerSeason == daysPerSeason) return c.calendarDays;
        int year = 4 * daysPerSeason;
        double total = 0;
        for (double s : STRETCHES) total += s;
        int[] out = new int[4];
        double into = MARCH;
        for (int k = 0; k < 4; k++) {
            out[k] = (int) Math.floorMod(Math.round(year * into), (long) year);
            into += STRETCHES[k] / total;
        }
        cache = new Marks(daysPerSeason, out);
        return out;
    }

    /** The mark that starts spring: March in the north, September in the south. */
    private static int springMark() {
        return Config.south() ? 2 : 0;
    }

    /** Days from the spring equinox to a season's first day. */
    public static int start(int daysPerSeason, int season) {
        if (season == 0) return 0;
        int[] m = marks(daysPerSeason);
        int sp = springMark();
        return Math.floorMod(m[(sp + season) % 4] - m[sp], 4 * daysPerSeason);
    }

    public static int length(int daysPerSeason, int season) {
        int end = season == 3 ? 4 * daysPerSeason : start(daysPerSeason, season + 1);
        return end - start(daysPerSeason, season);
    }

    /** The season a world day is in, its day in it (0 = the equinox or solstice) and the season's length. */
    public record Place(Season season, int day, int length) {}

    public static Place place(long worldDay, int daysPerSeason) {
        int u = (int) Math.floorMod(worldDay, 4L * daysPerSeason);
        int s = 3;
        while (s > 0 && start(daysPerSeason, s) > u) s--;
        return new Place(Season.VALUES[s], u - start(daysPerSeason, s), length(daysPerSeason, s));
    }

    /** The calendar day (0 = January 1 of year 1) of a world day: day 0 is the spring equinox. */
    public static long calendarDay(long worldDay, int daysPerSeason) {
        return worldDay + marks(daysPerSeason)[springMark()];
    }

    /**
     * Equal seasons from a day time counted from midnight: each season stretched over days_per_season days, smoothly
     * (real time: each real season, RealTime.internal).
     */
    private static long internal(long dayTime, int daysPerSeason) {
        if (Config.realTime()) return dev.venatorvictor.equinox.realtime.RealTime.internal(dayTime);
        long year = 4L * daysPerSeason;
        long day = Math.floorDiv(dayTime, SeasonState.DAY);
        long yearIndex = Math.floorDiv(day, year);
        Place p = place(day, daysPerSeason);
        double into = (p.day() + Math.floorMod(dayTime, SeasonState.DAY) / (double) SeasonState.DAY) / p.length();
        return (yearIndex * year + (long) p.season().ordinal() * daysPerSeason) * SeasonState.DAY
                + Math.round(into * daysPerSeason * SeasonState.DAY);
    }

    /** The world day on which an internal day (counted like the world's) begins: the first at or after it. */
    public static long worldDay(long internalDay, int daysPerSeason) {
        if (Config.realTime()) return internalDay;
        long year = 4L * daysPerSeason;
        long yearIndex = Math.floorDiv(internalDay, year);
        int inYear = (int) Math.floorMod(internalDay, year), s = inYear / daysPerSeason, within = inYear - s * daysPerSeason;
        int len = length(daysPerSeason, s);
        return yearIndex * year + start(daysPerSeason, s) + (long) Math.ceil(within * (double) len / daysPerSeason);
    }

    private GameYear() {}
}
