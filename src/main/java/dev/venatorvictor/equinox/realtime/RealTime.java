package dev.venatorvictor.equinox.realtime;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.api.Season;
import dev.venatorvictor.equinox.api.SeasonState;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Real time mode (config real_time): the world follows the real clock and date.
 *
 * The rest of Equinox reads the season from the world's day time with 96-day seasons. Here that day time is made
 * from the real date: each real season (from one equinox or solstice to the next, 89 to 94 days, uneven as on Earth)
 * is spread over the 96 days of a game season, so the season changes on the day of the real equinox or solstice. The
 * time of day is the real clock (day time 0 = 6:00, as in vanilla); Equinox's day turns at midnight.
 *
 * The equinoxes and solstices come from the standard astronomical formula (Meeus, Astronomical Algorithms, ch. 27,
 * good to a few minutes); the moon from the mean lunar month from a known new moon.
 */
public final class RealTime {
    private static final int SEASON = Config.REAL_DAYS_PER_SEASON;
    /** Years before this are not used (the day time counts from it). */
    private static final int BASE_YEAR = 2000;

    public static boolean on() {
        return Config.realTime();
    }

    public static ZonedDateTime now() {
        return ZonedDateTime.now(Config.timeZone());
    }

    /** Today's date on the real calendar (turns at midnight). */
    public static LocalDate today() {
        return now().toLocalDate();
    }

    // ---------- equinoxes and solstices

    private static final double[][] MARK_TERMS = {
            {2451623.80984, 365242.37404, 0.05169, -0.00411, -0.00057},   // March equinox
            {2451716.56767, 365241.62603, 0.00325, 0.00888, -0.00030},    // June solstice
            {2451810.21715, 365242.01767, -0.11575, 0.00337, 0.00078},    // September equinox
            {2451900.05952, 365242.74049, -0.06223, -0.00823, 0.00032}};  // December solstice
    private static final double[][] PERIODIC = {
            {485, 324.96, 1934.136}, {203, 337.23, 32964.467}, {199, 342.08, 20.186}, {182, 27.85, 445267.112},
            {156, 73.14, 45036.886}, {136, 171.52, 22518.443}, {77, 222.54, 65928.934}, {74, 296.72, 3034.906},
            {70, 243.58, 9037.513}, {58, 119.81, 33718.147}, {52, 297.17, 150.678}, {50, 21.02, 2281.226},
            {45, 247.54, 29929.562}, {44, 325.15, 31555.956}, {29, 60.93, 4443.417}, {18, 155.12, 67555.328},
            {17, 288.79, 4562.452}, {16, 198.04, 62894.029}, {14, 199.76, 31436.921}, {12, 95.39, 14577.848},
            {12, 287.11, 31931.756}, {12, 320.81, 34777.259}, {9, 227.73, 1222.114}, {8, 15.45, 16859.074}};
    private static final double J2000 = 2451545.0;
    private static final Instant J2000_INSTANT = LocalDateTime.of(2000, 1, 1, 12, 0).toInstant(ZoneOffset.UTC);

    /** The moment of a year's March equinox (0), June solstice (1), September equinox (2) or December solstice (3). */
    public static Instant markInstant(int year, int k) {
        double y = (year - 2000) / 1000.0;
        double[] a = MARK_TERMS[k];
        double jde0 = a[0] + y * (a[1] + y * (a[2] + y * (a[3] + y * a[4])));
        double t = (jde0 - J2000) / 36525;
        double w = Math.toRadians(35999.373 * t - 2.47);
        double dl = 1 + 0.0334 * Math.cos(w) + 0.0007 * Math.cos(2 * w);
        double sum = 0;
        for (double[] term : PERIODIC) sum += term[0] * Math.cos(Math.toRadians(term[1] + term[2] * t));
        double days = jde0 + 0.00001 * sum / dl - J2000 - 69 / 86400.0;     // terrestrial time to UTC (about 69 s)
        return J2000_INSTANT.plusMillis(Math.round(days * 86_400_000));
    }

    private record MarkKey(int year, int k, java.time.ZoneId zone) {}
    private static final java.util.Map<MarkKey, LocalDate> MARKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The local date of a year's mark (in the clock's time zone). */
    public static LocalDate markDate(int year, int k) {
        java.time.ZoneId zone = Config.timeZone();
        if (MARKS.size() > 512) MARKS.clear();
        return MARKS.computeIfAbsent(new MarkKey(year, k, zone), key -> markInstant(year, k).atZone(zone).toLocalDate());
    }

    /** The season a mark starts: March = spring in the north, autumn in the south. */
    public static Season seasonOfMark(int k) {
        return Season.VALUES[Config.south() ? (k + 2) % 4 : k];
    }

    /** The real season a date is in: from its mark's date to the next one's. */
    public record Span(LocalDate start, LocalDate next, Season season, int markYear, int mark) {
        public int length() {
            return (int) ChronoUnit.DAYS.between(start, next);
        }

        public int dayOfSeason(LocalDate date) {
            return (int) ChronoUnit.DAYS.between(start, date);
        }

        /** The year of seasons this span belongs to (it starts at spring's mark), counted from BASE_YEAR. */
        long seasonYear() {
            int springMark = Config.south() ? 2 : 0;
            int y = mark >= springMark ? markYear : markYear - 1;
            return y - BASE_YEAR;
        }
    }

    public static Span span(LocalDate date) {
        int year = date.getYear();
        for (int y = year; y >= year - 1; y--)
            for (int k = 3; k >= 0; k--) {
                LocalDate start = markDate(y, k);
                if (!start.isAfter(date)) {
                    LocalDate next = k == 3 ? markDate(y + 1, 0) : markDate(y, k + 1);
                    return new Span(start, next, seasonOfMark(k), y, k);
                }
            }
        throw new IllegalStateException("no season before " + date);
    }

    public static boolean isMark(LocalDate date) {
        for (int k = 0; k < 4; k++) if (markDate(date.getYear(), k).equals(date)) return true;
        return false;
    }

    /** The first equinox or solstice on or after a date. */
    public static LocalDate nextMark(LocalDate date) {
        for (int y = date.getYear(); ; y++)
            for (int k = 0; k < 4; k++) {
                LocalDate d = markDate(y, k);
                if (!d.isBefore(date)) return d;
            }
    }

    // ---------- the world's day time

    /** The game day a date is (96 a season: the real season stretched over them). */
    public static long gameDay(LocalDate date) {
        Span span = span(date);
        long into = (long) Math.floor(span.dayOfSeason(date) * (double) SEASON / span.length());
        return span.seasonYear() * 4L * SEASON + (long) span.season().ordinal() * SEASON + into;
    }

    /**
     * The world's day time for a moment. Equinox's day starts at midnight (GameYear.day), as the real date does, so
     * the season changes at midnight on the day of the equinox or solstice. When a game day is skipped the clock jumps
     * by whole days, so the time of day never moves.
     */
    public static long dayTime(ZonedDateTime time) {
        long ticks = Math.round(time.toLocalTime().toNanoOfDay() / 1e9 * SeasonState.DAY / 86400);
        return Math.max(0, gameDay(time.toLocalDate()) * SeasonState.DAY - dev.venatorvictor.equinox.api.GameYear.MIDNIGHT
                + Math.min(ticks, SeasonState.DAY - 1));
    }

    /** A date's midday as the world's day time (day length, season of a calendar day). */
    public static long middayTime(LocalDate date) {
        return Math.max(0, gameDay(date) * SeasonState.DAY + 6000);
    }

    // ---------- the moon

    /** Mean lunar month in days, and a new moon (2000-01-06 18:14 UTC). */
    private static final double SYNODIC = 29.530588853;
    private static final Instant NEW_MOON = LocalDateTime.of(2000, 1, 6, 18, 14).toInstant(ZoneOffset.UTC);

    /** 0 new moon .. 4 full .. 7 waning crescent: the nearest of the 8 phases. */
    public static int moonStep(Instant at) {
        double age = (at.toEpochMilli() - NEW_MOON.toEpochMilli()) / 86_400_000.0 / SYNODIC;
        return (int) Math.floorMod(Math.round((age - Math.floor(age)) * 8), 8L);
    }

    /** The phase at midday of a date. */
    public static int moonStep(LocalDate date) {
        return moonStep(date.atTime(12, 0).atZone(Config.timeZone()).toInstant());
    }

    /**
     * Vanilla's moon phase index (0 full, 4 new) now: the phase at noon of the day whose noon last passed, so it
     * changes at noon, when the moon isn't up, never during the night.
     */
    public static int moonPhase() {
        return (moonStep(now().minusHours(12).toLocalDate()) + 4) % 8;
    }

    private RealTime() {}
}
