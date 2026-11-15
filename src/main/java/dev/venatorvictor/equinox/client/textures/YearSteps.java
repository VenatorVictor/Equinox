package dev.venatorvictor.equinox.client.textures;

import dev.venatorvictor.equinox.api.SeasonState;

import java.util.List;

/**
 * Week names in seasonal texture files: <season>_week_<1..14> (spring_week_1 .. winter_week_14; "fall" = autumn),
 * 14 a season, 56 a year, week 0 = the first days of spring.
 */
final class YearSteps {
    private static final List<String> SEASONS = List.of("spring", "summer", "autumn", "winter");

    /** <season>_week_<1..14> -> 0..55, -1 for an unknown name. */
    static int week(String name) {
        String lower = name.toLowerCase();
        int at = lower.indexOf("_week_");
        if (at < 0) return -1;
        String seasonName = lower.substring(0, at);
        int season = SEASONS.indexOf(seasonName.equals("fall") ? "autumn" : seasonName);
        try {
            int n = Integer.parseInt(lower.substring(at + 6));
            return season < 0 || n < 1 || n > SeasonState.WEEKS_PER_SEASON ? -1 : season * SeasonState.WEEKS_PER_SEASON + n - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private YearSteps() {}
}
