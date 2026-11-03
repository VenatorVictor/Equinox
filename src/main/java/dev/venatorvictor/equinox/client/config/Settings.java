package dev.venatorvictor.equinox.client.config;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.client.ClientConfig;
import dev.venatorvictor.equinox.client.config.Setting.Cost;
import dev.venatorvictor.equinox.client.config.Setting.Side;
import dev.venatorvictor.equinox.client.config.Setting.Values;
import dev.venatorvictor.equinox.daylength.DayLength;
import dev.venatorvictor.equinox.snow.SnowConfig;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Every setting the config screen shows, in tab order. Names, descriptions and costs live here. */
final class Settings {
    static final String SEASONS = "seasons", CLIMATE = "climate", CROPS = "crops", SNOW = "snow", LOOKS = "looks",
            DH = "distant_horizons", PERFORMANCE = "performance";
    static final List<String> TABS = List.of(SEASONS, CLIMATE, CROPS, SNOW, LOOKS, DH, PERFORMANCE);
    /** One unit of the Minecraft temperature scale in °C (the climate's own conversion). */
    private static final double CELSIUS = 30;
    private static final double VANILLA_COLUMNS = 0.0625;

    static final List<Setting> ALL = build();

    private static Setting server(ModConfigSpec.ConfigValue<?> value, String name) {
        return new Setting(value, Side.SERVER, name);
    }

    private static Setting common(ModConfigSpec.ConfigValue<?> value, String name) {
        return new Setting(value, Side.COMMON, name);
    }

    private static Setting client(ModConfigSpec.ConfigValue<?> value, String name) {
        return new Setting(value, Side.CLIENT, name);
    }

    /** A unit text from the language file: equinox.settings.unit.<key> (format arguments). */
    static String u(String key, Object... args) {
        return I18n.get("equinox.settings.unit." + key, args);
    }

    private static String days(double days) {
        return u(days == 1 ? "day" : "days", Setting.trim(days));
    }

    private static String off(double v, String label) {
        return v == 0 ? u("off") : label;
    }

    private static String celsius(double c) {
        return c == 0 ? u("off") : u("plus_minus_celsius", String.format(Locale.ROOT, "%.1f", c));
    }

    private static String band(double map) {
        String name = map < -0.45 ? "frozen" : map < -0.15 ? "cold" : map < 0.2 ? "temperate" : map < 0.55 ? "warm" : "hot";
        return String.format(Locale.ROOT, "%.2f", map) + " (" + u("band." + name) + ")";
    }

    private static List<Setting> build() {
        List<Setting> all = new ArrayList<>();

        // ---- Seasons
        all.add(server(Config.DAYS_PER_SEASON, "days_per_season").tab(SEASONS, "calendar")
                .cost(Cost.NONE).range(3, 120, 1)
                .label((v, x) -> u("season_days", days(v), (int) v * 4)));
        all.add(server(Config.TEMPERATURE_LAG, "temperature_lag").tab(SEASONS, null)
                .cost(Cost.NONE)
                .convert((share, x) -> share * x.daysPerSeason(), (d, x) -> d / x.daysPerSeason())
                .range(0, 42, 1).max(Values::daysPerSeason).label((d, x) -> off(d, days(d))));
        all.add(server(Config.LATITUDE, "latitude").tab(SEASONS, "day_length")
                .cost(Cost.NONE).range(0, 60, 1)
                .label((v, x) -> v == 0 ? u("off_vanilla") : u("latitude", (int) v,
                        String.format(Locale.ROOT, "%.1f", DayLength.daylightHours(v, 0.375)), String.format(Locale.ROOT, "%.1f", DayLength.daylightHours(v, 0.875)))));
        all.add(server(Config.SEASON_VARIATION, "season_variation").tab(SEASONS, "random_weather")
                .cost(Cost.NONE).scale(CELSIUS).range(0, 15, 0.5).label((v, x) -> celsius(v)));
        all.add(server(Config.DAILY_VARIATION, "daily_variation").tab(SEASONS, null)
                .cost(Cost.NONE).scale(CELSIUS).range(0, 15, 0.5).label((v, x) -> celsius(v)));
        all.add(server(Config.VARIATION_SEED, "variation_seed").tab(SEASONS, null)
                .cost(Cost.NONE));

        // ---- Climate
        all.add(server(Config.BLEND_RADIUS, "blend_radius").tab(CLIMATE, "blending")
                .cost(Cost.MEDIUM)
                .range(0, 512, 16).label((v, x) -> off(v, u("blocks", (int) v))));
        all.add(server(Config.BLEND_RADIUS_LARGE, "blend_radius_large").tab(CLIMATE, null)
                .cost(Cost.MEDIUM)
                .range(0, 1024, 16).label((v, x) -> off(v, u("blocks", (int) v))));
        all.add(server(Config.BLEND_STRENGTH, "blend_strength").tab(CLIMATE, null)
                .cost(Cost.NONE).scale(100).range(0, 100, 5).label((v, x) -> (int) v + "%"));
        all.add(server(Config.CLIMATE_ZONES, "climate_zones").tab(CLIMATE, "climate_zones")
                .cost(Cost.LOW));
        all.add(server(Config.ZONE_WARM_START, "zone_warm_start").tab(CLIMATE, null)
                .cost(Cost.NONE).range(-1, 1, 0.05).label((v, x) -> band(v)));
        all.add(server(Config.ZONE_WARM_END, "zone_warm_end").tab(CLIMATE, null)
                .cost(Cost.NONE).range(-1, 1, 0.05).label((v, x) -> band(v)));
        all.add(server(Config.CELSIUS_CHANGES, "celsius_changes").tab(CLIMATE, "tables")
                .cost(Cost.NONE));
        all.add(server(Config.BIOME_TEMPERATURES, "biome_temperatures").tab(CLIMATE, null)
                .cost(Cost.NONE));

        // ---- Crops
        all.add(server(Config.SEASONAL_CROPS, "seasonal_crops").tab(CROPS, "growth")
                .cost(Cost.LOW));
        all.add(server(Config.SLOW_GROWTH, "slow_growth").tab(CROPS, null)
                .cost(Cost.NONE).range(0, 30, 1)
                .label((v, x) -> v == 0 ? u("never_grows") : v == 1 ? u("normal_speed") : u("one_in", (int) v, Math.round(100 / v))));
        all.add(server(Config.IDEAL_BONUS, "ideal_bonus").tab(CROPS, null)
                .cost(Cost.NONE).scale(100).range(0, 100, 5).label((v, x) -> (int) v + "%"));
        all.add(server(Config.FARMLAND_HEAT, "farmland_heat").tab(CROPS, null)
                .cost(Cost.NONE).range(-20, 60, 1).label((v, x) -> (int) v + " °C"));
        all.add(server(Config.CROP_HEATERS, "crop_heaters").tab(CROPS, "heaters_and_coolers")
                .cost(Cost.LOW)
                );
        all.add(server(Config.CROP_COOLERS, "crop_coolers").tab(CROPS, null)
                .cost(Cost.LOW)
                );
        all.add(client(ClientConfig.CROP_LOOKS, "crop_looks").tab(CROPS, "looks")
                .cost(Cost.LOW));
        all.add(client(ClientConfig.CROP_COLORS, "crop_colors").tab(CROPS, null)
                .choice(ClientConfig.CropColors.DEFAULT, "default").choice(ClientConfig.CropColors.BEIGE_ORANGE, "beige_and_orange")
                .cost(Cost.NONE).applies("textures"));

        // ---- Snow
        all.add(common(SnowConfig.FORCE_SNOWFALL, "force_snowfall").tab(SNOW, "snowfall")
                .cost(Cost.NONE));
        all.add(common(SnowConfig.SNOWFALL_TICKS, "snowfall_ticks").tab(SNOW, null)
                .cost(Cost.NONE).scale(0.001).range(0.25, 24, 0.25)
                .label((h, x) -> u("game_hours", Setting.trim(h), Setting.trim(Math.round(h * 50 / 6) / 10.0))));
        all.add(common(SnowConfig.SNOW_LOCKS, "snow_locks").tab(SNOW, null)
                .cost(Cost.NONE));
        all.add(common(SnowConfig.SNOW_COVER, "snow_cover").tab(SNOW, "snow_cover")
                .cost(Cost.MEDIUM));
        all.add(common(SnowConfig.SNOW_DEPTH, "snow_depth").tab(SNOW, null)
                .cost(Cost.NONE));
        all.add(common(SnowConfig.SNOWY_PLANTS, "snowy_plants").tab(SNOW, "snowy_plants")
                .cost(Cost.LOW));
        all.add(common(SnowConfig.STORAGE, "storage").tab(SNOW, null)
                .choice(SnowConfig.Storage.BLOCK_STATE, "block_state").choice(SnowConfig.Storage.BLOCK_ENTITY, "block_entity")
                .cost(Cost.NONE));
        all.add(client(ClientConfig.SNOW_ANIMATION, "snow_animation").tab(SNOW, "animation_and_looks")
                .cost(Cost.LOW));
        all.add(client(ClientConfig.SNOW_ANIMATION_MS, "snow_animation_ms").tab(SNOW, null)
                .cost(Cost.NONE).scale(0.001).range(0.05, 5, 0.05).label((s, x) -> String.format(Locale.ROOT, "%.2f s", s)));
        all.add(client(ClientConfig.SNOW_LOOK, "snow_look").tab(SNOW, null)
                .choice(ClientConfig.SnowLook.GROUND, "ground").choice(ClientConfig.SnowLook.CALENDAR, "calendar")
                .cost(Cost.NONE).applies("redraw"));
        all.add(client(ClientConfig.SNOW_MODELS, "snow_models").tab(SNOW, null)
                .choice(ClientConfig.SnowModels.NEW, "new").choice(ClientConfig.SnowModels.VANILLA, "vanilla")
                .cost(Cost.LOW).applies("redraw"));

        // ---- Looks
        all.add(client(ClientConfig.GRASS, "grass").tab(LOOKS, "colors")
                .cost(Cost.LOW).applies("redraw"));
        all.add(client(ClientConfig.FOLIAGE, "foliage").tab(LOOKS, null)
                .cost(Cost.LOW).applies("redraw"));
        all.add(client(ClientConfig.SMOOTH, "smooth").tab(LOOKS, null)
                .cost(Cost.MEDIUM));
        all.add(client(ClientConfig.SPRUCE, "spruce").tab(LOOKS, "tree_colors").cost(Cost.NONE).applies("redraw"));
        all.add(client(ClientConfig.BIRCH, "birch").tab(LOOKS, null).cost(Cost.NONE).applies("redraw"));
        all.add(client(ClientConfig.BIRCH_PATCHES, "birch_patches").tab(LOOKS, null)
                .cost(Cost.NONE).applies("redraw"));
        all.add(client(ClientConfig.MANGROVE, "mangrove").tab(LOOKS, null).cost(Cost.NONE).applies("redraw"));
        all.add(client(ClientConfig.SEASONAL_TEXTURES, "seasonal_textures").tab(LOOKS, "textures")
                .cost(Cost.MEDIUM)
                .applies("redraw"));

        // ---- Distant Horizons
        all.add(client(ClientConfig.DH_REFRESH, "dh_refresh").tab(DH, "this_computer")
                .cost(Cost.MEDIUM));
        all.add(client(ClientConfig.DH_SNOW, "dh_snow").tab(DH, null)
                .cost(Cost.MEDIUM));
        all.add(server(Config.DH_BLEND_DISTANCE, "dh_blend_distance").tab(DH, "server")
                .cost(Cost.HIGH)
                .range(0, 16384, 256).label((v, x) -> off(v, u("blocks_chunks", (int) v, (int) v / 16))));

        // ---- Performance
        all.add(common(SnowConfig.COLUMNS_PER_TICK, "columns_per_tick").tab(PERFORMANCE, "server_snow_cover")
                .cost(Cost.MEDIUM)
                .scale(1 / VANILLA_COLUMNS).range(0, 16, 0.25).label((v, x) -> off(v, u("x_vanilla", Setting.trim(v)))));
        all.add(common(SnowConfig.BUDGET_MICROS, "budget_micros").tab(PERFORMANCE, null)
                .cost(Cost.HIGH)
                .scale(0.001).range(0.1, 20, 0.1).label((ms, x) -> u("ms_per_tick", String.format(Locale.ROOT, "%.1f", ms))));
        all.add(common(SnowConfig.SPARE_MICROS, "spare_micros").tab(PERFORMANCE, null)
                .cost(Cost.LOW)
                .scale(0.001).range(0, 40, 1).label((ms, x) -> off(ms, (int) ms + " ms")));
        all.add(common(SnowConfig.BIOME_CELL, "biome_cell").tab(PERFORMANCE, null)
                .choice(1, "exact").choice(2, "2x2_blocks").choice(4, "4x4_blocks")
                .cost(Cost.SAVES));
        all.add(client(ClientConfig.UPDATES_PER_STEP, "updates_per_step").tab(PERFORMANCE, "this_computer_redraws")
                .cost(Cost.MEDIUM)
                .range(1, 48, 1).label((v, x) -> {
                    double hours = x.daysPerSeason() / 6.0 / v * 24;
                    return u("every", (int) v, hours >= 24 ? days(Math.round(hours / 2.4) / 10.0) : Math.round(hours) + " h");
                }));
        all.add(client(ClientConfig.REDRAW_SECONDS, "redraw_seconds").tab(PERFORMANCE, null)
                .cost(Cost.SAVES)
                .range(0, 60, 1).label((s, x) -> s == 0 ? u("all_at_once") : (int) s + " s"));
        all.add(client(ClientConfig.SNOW_ANIMATION_DISTANCE, "snow_animation_distance").tab(PERFORMANCE, null)
                .cost(Cost.LOW)
                .range(4, 256, 4).label((v, x) -> u("blocks", (int) v)));
        return List.copyOf(all);
    }

    private Settings() {}
}
