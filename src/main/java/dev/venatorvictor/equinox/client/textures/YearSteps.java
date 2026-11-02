package dev.venatorvictor.equinox.client.textures;

import java.util.List;

/** Solar term names, in year order: the 24 year steps, as seasonal texture packs name them. */
final class YearSteps {
    private static final List<String> NAMES = List.of(
            "beginning_of_spring", "rain_water", "insects_awakening", "spring_equinox", "fresh_green", "grain_rain",
            "beginning_of_summer", "lesser_fullness", "grain_in_ear", "summer_solstice", "lesser_heat", "greater_heat",
            "beginning_of_autumn", "end_of_heat", "white_dew", "autumnal_equinox", "cold_dew", "first_frost",
            "beginning_of_winter", "light_snow", "heavy_snow", "winter_solstice", "lesser_cold", "greater_cold");

    /** -1 for an unknown name. */
    static int index(String name) {
        return NAMES.indexOf(name.toLowerCase());
    }

    private YearSteps() {}
}
