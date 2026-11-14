package dev.venatorvictor.equinox.client.textures;

import java.util.List;

/**
 * Names of the year's steps. Textures change by week: 14 a season, 56 a year (week 0 = the first days of spring),
 * named <season>_week_<1..14> (spring_week_1 .. winter_week_14). The older 24 names (2 per season part:
 * early_spring_start, early_spring_end, mid_spring_start ... late_winter_end) still work and cover 2 or 3 weeks each
 * (SeasonState.stepStartWeek / stepEndWeek). Seasonal texture packs in the format we read use their own 24 names; those
 * are read by position (their first name = early_spring_start).
 */
final class YearSteps {
    private static final List<String> NAMES = names();
    private static final List<String> FORMAT_NAMES = List.of(
            "beginning_of_spring", "rain_water", "insects_awakening", "spring_equinox", "fresh_green", "grain_rain",
            "beginning_of_summer", "lesser_fullness", "grain_in_ear", "summer_solstice", "lesser_heat", "greater_heat",
            "beginning_of_autumn", "end_of_heat", "white_dew", "autumnal_equinox", "cold_dew", "first_frost",
            "beginning_of_winter", "light_snow", "heavy_snow", "winter_solstice", "lesser_cold", "greater_cold");

    private static List<String> names() {
        String[] parts = {"early", "mid", "late"}, seasons = {"spring", "summer", "autumn", "winter"};
        String[] out = new String[24];
        for (int i = 0; i < 24; i++) out[i] = parts[i / 2 % 3] + "_" + seasons[i / 6] + (i % 2 == 0 ? "_start" : "_end");
        return List.of(out);
    }

    private static final List<String> SEASONS = List.of("spring", "summer", "autumn", "winter");

    /** The first week a name covers, -1 for an unknown name. */
    static int startWeek(String name) {
        int week = week(name);
        if (week >= 0) return week;
        int step = step(name);
        return step < 0 ? -1 : dev.venatorvictor.equinox.api.SeasonState.stepStartWeek(step);
    }

    /** The last week a name covers, -1 for an unknown name. */
    static int endWeek(String name) {
        int week = week(name);
        if (week >= 0) return week;
        int step = step(name);
        return step < 0 ? -1 : dev.venatorvictor.equinox.api.SeasonState.stepEndWeek(step);
    }

    /** <season>_week_<1..14> -> 0..55, else -1. */
    private static int week(String name) {
        String lower = name.toLowerCase();
        int at = lower.indexOf("_week_");
        if (at < 0) return -1;
        int season = SEASONS.indexOf(lower.substring(0, at).equals("fall") ? "autumn" : lower.substring(0, at));
        try {
            int n = Integer.parseInt(lower.substring(at + 6));
            int per = dev.venatorvictor.equinox.api.SeasonState.WEEKS_PER_SEASON;
            return season < 0 || n < 1 || n > per ? -1 : season * per + n - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** One of the 24 older names -> 0..23, else -1. */
    private static int step(String name) {
        String lower = name.toLowerCase();
        int i = NAMES.indexOf(lower);
        return i >= 0 ? i : FORMAT_NAMES.indexOf(lower);
    }

    private YearSteps() {}
}
