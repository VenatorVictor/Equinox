package dev.venatorvictor.equinox.calendar;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.api.Season;
import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.daylength.DayLength;
import dev.venatorvictor.equinox.realtime.RealTime;
import net.minecraft.world.level.Level;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * The calendar's dates, in game time or real time. A day is a number: the world's day count in game time, the
 * real date's epoch day in real time. Months are numbered as the language keys: 0 = April .. 11 = March.
 */
public interface CalendarDates {
    long today();

    /** 0 = April .. 11 = March. */
    int month(long day);

    /** 0 for the first. */
    int dayOfMonth(long day);

    int monthLength(long day);

    default long firstOfMonth(long day) {
        return day - dayOfMonth(day);
    }

    /** The first day of the month n months after the one starting on this first day. */
    long plusMonths(long first, int n);

    /** January 1 of the day's year. */
    long firstOfYear(long day);

    /** The year number shown (turns on January 1). */
    long year(long day);

    Season season(long day);

    int dayOfSeason(long day);

    int seasonLength(long day);

    default int weekOfSeason(long day) {
        return dayOfSeason(day) / weekLength();
    }

    /** An equinox or a solstice: a season's first day. */
    default boolean isMark(long day) {
        return dayOfSeason(day) == 0;
    }

    /** The first equinox or solstice on or after this day. */
    default long nextMark(long day) {
        return day + Math.floorMod(-dayOfSeason(day), seasonLength(day));
    }

    /** 0 new moon .. 4 full .. 7 waning crescent. */
    int moonStep(long day);

    /** Hours of sun. */
    double daylightHours(long day);

    int weekLength();

    /** The column of the month's first day (0 = the week's first). */
    int firstColumn(long first);

    /** Real weeks: Monday to Sunday, with the names on the page. */
    boolean weekdayNames();

    /** Days before this never were (the world's first). */
    long earliest();

    static CalendarDates of(Level level) {
        return current(level.getDayTime());
    }

    static CalendarDates current(long dayTime) {
        return RealTime.on() ? new Real() : new Game(new SeasonState(dayTime, Config.daysPerSeason(), Config.daysPerWeek()));
    }

    /**
     * Game time: 12 equal months; the seasons uneven as on Earth (GameYear), starting on the equinoxes and solstices
     * (16-day months: March 10, June 11, September 12, December 11). A day is Equinox's day count (GameYear.day: from
     * midnight); day * DAY + 6000 is its noon on the world's clock.
     */
    record Game(SeasonState now) implements CalendarDates {
        private int dps() { return now.daysPerSeason(); }
        private int md() { return now.monthDays(); }
        private long cal(long day) { return dev.venatorvictor.equinox.api.GameYear.calendarDay(day, dps()); }
        private dev.venatorvictor.equinox.api.GameYear.Place place(long day) { return dev.venatorvictor.equinox.api.GameYear.place(day, dps()); }

        @Override public long today() { return dev.venatorvictor.equinox.api.GameYear.day(now.dayTime()); }
        @Override public int month(long day) { return (int) ((Math.floorMod(Math.floorDiv(cal(day), md()), 12L) + 9) % 12); }
        @Override public int dayOfMonth(long day) { return (int) Math.floorMod(cal(day), (long) md()); }
        @Override public int monthLength(long day) { return md(); }
        @Override public long plusMonths(long first, int n) { return first + (long) n * md(); }
        @Override public long firstOfYear(long day) { return day - Math.floorMod(cal(day), 12L * md()); }
        @Override public long year(long day) { return Math.floorDiv(cal(day), 12L * md()) + 1; }
        @Override public Season season(long day) { return place(day).season(); }
        @Override public int dayOfSeason(long day) { return place(day).day(); }
        @Override public int seasonLength(long day) { return place(day).length(); }
        @Override public int moonStep(long day) {
            return new SeasonState(day * SeasonState.DAY + 6000, dps(), now.daysPerWeek()).moonStep();
        }
        @Override public double daylightHours(long day) { return DayLength.daylightHours(day * SeasonState.DAY + 6000, dps()); }
        @Override public int weekLength() { return Math.max(1, Math.min(now.daysPerWeek(), md())); }
        @Override public int firstColumn(long first) { return 0; }
        @Override public boolean weekdayNames() { return false; }
        @Override public long earliest() { return 0; }
    }

    /** Real time: the real calendar, the real equinoxes and solstices, the real moon. */
    record Real() implements CalendarDates {
        private static LocalDate date(long day) {
            return LocalDate.ofEpochDay(day);
        }

        @Override public long today() { return RealTime.today().toEpochDay(); }
        @Override public int month(long day) { return Math.floorMod(date(day).getMonthValue() - 4, 12); }
        @Override public int dayOfMonth(long day) { return date(day).getDayOfMonth() - 1; }
        @Override public int monthLength(long day) { return date(day).lengthOfMonth(); }
        @Override public long plusMonths(long first, int n) { return date(first).plusMonths(n).withDayOfMonth(1).toEpochDay(); }
        @Override public long firstOfYear(long day) { return date(day).withDayOfYear(1).toEpochDay(); }
        @Override public long year(long day) { return date(day).getYear(); }
        @Override public Season season(long day) { return RealTime.span(date(day)).season(); }
        @Override public int dayOfSeason(long day) { return RealTime.span(date(day)).dayOfSeason(date(day)); }
        @Override public int seasonLength(long day) { return RealTime.span(date(day)).length(); }
        @Override public boolean isMark(long day) { return RealTime.isMark(date(day)); }
        @Override public long nextMark(long day) { return RealTime.nextMark(date(day)).toEpochDay(); }
        @Override public int moonStep(long day) { return RealTime.moonStep(date(day)); }
        @Override public double daylightHours(long day) {
            return DayLength.daylightHours(RealTime.middayTime(date(day)), Config.REAL_DAYS_PER_SEASON);
        }
        @Override public int weekLength() { return 7; }
        @Override public int firstColumn(long first) { return date(first).getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue(); }
        @Override public boolean weekdayNames() { return true; }
        @Override public long earliest() { return Long.MIN_VALUE; }
    }
}
