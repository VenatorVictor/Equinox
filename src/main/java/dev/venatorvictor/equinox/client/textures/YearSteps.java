package dev.venatorvictor.equinox.client.textures;

import java.util.List;

/**
 * Names of the 24 year steps, in year order (2 per season part, step 0 = the spring equinox): early_spring_start,
 * early_spring_end, mid_spring_start ... late_winter_end. Seasonal texture packs in the format we read use their own
 * 24 names; those are read by position (their first name = our first step).
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

    /** -1 for an unknown name. */
    static int index(String name) {
        String lower = name.toLowerCase();
        int i = NAMES.indexOf(lower);
        return i >= 0 ? i : FORMAT_NAMES.indexOf(lower);
    }

    private YearSteps() {}
}
