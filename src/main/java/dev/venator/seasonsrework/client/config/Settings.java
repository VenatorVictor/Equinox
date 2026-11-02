package dev.venator.seasonsrework.client.config;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.client.ClientConfig;
import dev.venator.seasonsrework.client.config.Setting.Cost;
import dev.venator.seasonsrework.client.config.Setting.Side;
import dev.venator.seasonsrework.client.config.Setting.Values;
import dev.venator.seasonsrework.daylength.DayLength;
import dev.venator.seasonsrework.snow.SnowConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Every setting the config screen shows, in tab order. Names, descriptions and costs live here. */
final class Settings {
    static final String SEASONS = "Seasons", CLIMATE = "Climate", CROPS = "Crops", SNOW = "Snow", LOOKS = "Looks",
            DH = "Distant Horizons", PERFORMANCE = "Performance";
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

    private static String days(double days) {
        return Setting.trim(days) + (days == 1 ? " day" : " days");
    }

    private static String celsius(double c) {
        return c == 0 ? "Off" : String.format(Locale.ROOT, "±%.1f °C", c);
    }

    private static String band(double map) {
        String name = map < -0.45 ? "frozen" : map < -0.15 ? "cold" : map < 0.2 ? "temperate" : map < 0.55 ? "warm" : "hot";
        return String.format(Locale.ROOT, "%.2f (%s)", map, name);
    }

    private static List<Setting> build() {
        List<Setting> all = new ArrayList<>();

        // ---- Seasons
        all.add(server(Config.DAYS_PER_SEASON, "Season length").tab(SEASONS, "Calendar")
                .desc("Days in one season. A year is 4 seasons, each with an early, mid and late part.",
                        "The season is counted from the world's day, so changing this moves today's date.")
                .cost(Cost.NONE, null).range(3, 120, 1)
                .label((v, x) -> days(v) + " (year " + (int) v * 4 + ")"));
        all.add(server(Config.TEMPERATURE_LAG, "Temperature lag").tab(SEASONS, null)
                .desc("How far the weather runs behind the calendar: temperature, snow, crop seasons and the grass,",
                        "leaf and texture colors. In real life the warmest days come about a month after the longest day.",
                        "Day length, tropical wet and dry seasons and the season shown stay on the calendar.",
                        "Kept in the file as a share of a season, so it grows and shrinks with the season length.")
                .cost(Cost.NONE, null)
                .convert((share, x) -> share * x.daysPerSeason(), (d, x) -> d / x.daysPerSeason())
                .range(0, 42, 1).max(Values::daysPerSeason).label((d, x) -> d == 0 ? "Off" : days(d)));
        all.add(server(Config.LATITUDE, "Latitude").tab(SEASONS, "Day length")
                .desc("The sun is up longer in summer and shorter in winter, as at this latitude on Earth. Equinox days",
                        "stay vanilla's (13.6 hours). Mobs, beds and daylight sensors follow the sun.",
                        "The slider shows the hours of sun at mid summer / mid winter. 0: vanilla days all year.")
                .cost(Cost.NONE, null).range(0, 60, 1)
                .label((v, x) -> v == 0 ? "Off (vanilla)" : String.format(Locale.ROOT, "%d° (%.1fh / %.1fh)", (int) v,
                        DayLength.daylightHours(v, 0.375), DayLength.daylightHours(v, 0.875))));
        all.add(server(Config.SEASON_VARIATION, "Season variation").tab(SEASONS, "Random weather")
                .desc("Each season is warmer or colder than usual by up to this, so no two years are the same. Small",
                        "changes are more likely than big ones, and one season blends into the next.",
                        "Scaled per climate: tropics x0.3, temperate x1, deserts x0.8, the cold north and mountains x1.5.",
                        "Grass and leaf colors don't follow it.")
                .cost(Cost.NONE, null).scale(CELSIUS).range(0, 15, 0.5).label((v, x) -> celsius(v)));
        all.add(server(Config.DAILY_VARIATION, "Daily variation").tab(SEASONS, null)
                .desc("Each day is warmer or colder than usual by up to this, on top of the season's. Blended from one",
                        "day's noon to the next, so warm and cold spells last a few days. Same climate scaling as above.",
                        "Not lagged. Grass and leaf colors don't follow it.")
                .cost(Cost.NONE, null).scale(CELSIUS).range(0, 15, 0.5).label((v, x) -> celsius(v)));
        all.add(server(Config.VARIATION_SEED, "Variation seed").tab(SEASONS, null)
                .desc("Seed for the random season and daily variation. 0: taken from the world seed when the world loads.")
                .cost(Cost.NONE, null));

        // ---- Climate
        all.add(server(Config.BLEND_RADIUS, "Blend radius").tab(CLIMATE, "Blending")
                .desc("A place's temperature mixes its biome with the biomes around it, up to this far (nearer ones",
                        "count more). A river in the jungle is tropical, one in the taiga cold, and a forest next to a",
                        "desert is warmer than one next to snowy plains. World generation doesn't change.")
                .cost(Cost.MEDIUM, "Bigger = more biome samples when chunks load (worked out once per chunk, then kept).")
                .range(0, 512, 16).label((v, x) -> v == 0 ? "Off" : (int) v + " blocks"));
        all.add(server(Config.BLEND_RADIUS_LARGE, "Blend radius (Large Biomes)").tab(CLIMATE, null)
                .desc("The same for Large Biomes worlds, where biomes are 4 times wider.")
                .cost(Cost.MEDIUM, "Bigger = more biome samples when chunks load (worked out once per chunk, then kept).")
                .range(0, 1024, 16).label((v, x) -> v == 0 ? "Off" : (int) v + " blocks"));
        all.add(server(Config.BLEND_STRENGTH, "Blend strength").tab(CLIMATE, null)
                .desc("How much a biome takes from its surroundings: 0% = its own temperature, 100% = only theirs.",
                        "Rivers and beaches always take 100% (they have no climate of their own).")
                .cost(Cost.NONE, null).scale(100).range(0, 100, 5).label((v, x) -> (int) v + "%"));
        all.add(server(Config.CLIMATE_ZONES, "Climate zones").tab(CLIMATE, "Climate zones")
                .desc("Minecraft places biomes by a temperature map (frozen, cold, temperate, warm, hot). Near the warm",
                        "bands (jungle, savanna, desert) the winter cold fades out, so there's no snow next to jungles.",
                        "Only the cold half of the year fades; summers stay. Mountains keep their whole winter.",
                        "Read from the world's own map: changes nothing in world generation.")
                .cost(Cost.LOW, "One extra noise sample per chunk when it loads."));
        all.add(server(Config.ZONE_WARM_START, "Winter fades from").tab(CLIMATE, null)
                .desc("Where on the temperature map the winter starts fading (full winter below).",
                        "Bands: frozen below -0.45, cold to -0.15, temperate to 0.2, warm to 0.55, hot above.")
                .cost(Cost.NONE, null).range(-1, 1, 0.05).label((v, x) -> band(v)));
        all.add(server(Config.ZONE_WARM_END, "Winter gone at").tab(CLIMATE, null)
                .desc("Where the winter is gone (no seasonal cold above). 0.2 is where jungles and savannas start.")
                .cost(Cost.NONE, null).range(-1, 1, 0.05).label((v, x) -> band(v)));
        all.add(server(Config.CLIMATE_CHANGES, "Seasonal change per zone").tab(CLIMATE, "Tables")
                .desc("Temperature change of each climate zone at the middle of spring, summer, autumn and winter, on",
                        "Minecraft's temperature scale (snow falls and water freezes below 0.15; plains 0.8, taiga 0.25).",
                        "Blended in between. Zones left out keep their default.")
                .cost(Cost.NONE, null).format("zone=spring,summer,autumn,winter   e.g. temperate=0,0.2,0,-0.8"));
        all.add(server(Config.CELSIUS_CHANGES, "Seasonal change per zone (°C)").tab(CLIMATE, null)
                .desc("°C climate (crops and other mods asking for the temperature): change of each zone at the middle",
                        "of spring, summer, autumn and winter, added to the biome's spring/autumn °C. One value = same",
                        "day and night; \"a..b\" = midnight..noon, so the day-night gap can change with the season.")
                .cost(Cost.NONE, null).format("zone=spring,summer,autumn,winter   e.g. temperate=0..1,9..11,0..-1,-12..-14"));
        all.add(server(Config.BIOME_TEMPERATURES, "Biome temperatures").tab(CLIMATE, null)
                .desc("Temperature a biome has for the climate (snow, rain, freezing, crops). Grass and leaf colors keep",
                        "the biome's own. The defaults put back vanilla's values for biome packs that change temperatures",
                        "only to tint grass; without such a pack they change nothing.")
                .cost(Cost.NONE, null).format("biome=temperature   e.g. minecraft:plains=0.8"));

        // ---- Crops
        all.add(server(Config.SEASONAL_CROPS, "Seasonal crops").tab(CROPS, "Growth")
                .desc("Crops follow the temperature in °C. Each crop has a range: ideal grows faster, cool or warm grows",
                        "slowly, near the limits stops, past them (tonight's low, today's high) farmland crops die.",
                        "Heaters and coolers nearby change the temperature. Unlisted plants grow as in vanilla.")
                .cost(Cost.LOW, "A temperature check on each crop growth tick."));
        all.add(server(Config.SLOW_GROWTH, "Slow growth").tab(CROPS, null)
                .desc("Cool or warm (outside the ideal range): a crop grows on 1 in this many growth chances,",
                        "bone meal too.")
                .cost(Cost.NONE, null).range(0, 30, 1)
                .label((v, x) -> v == 0 ? "Never grows" : v == 1 ? "Normal speed" : String.format(Locale.ROOT, "1 in %d (%d%%)", (int) v, Math.round(100 / v))));
        all.add(server(Config.IDEAL_BONUS, "Ideal growth bonus").tab(CROPS, null)
                .desc("In the ideal range, this share of growth chances grows for sure (15%: about 1.4x vanilla speed).")
                .cost(Cost.NONE, null).scale(100).range(0, 100, 5).label((v, x) -> (int) v + "%"));
        all.add(server(Config.FARMLAND_HEAT, "Hot farmland").tab(CROPS, null)
                .desc("When today's high is at least this, farmland only stays wet with water within 2 blocks, not 4.")
                .cost(Cost.NONE, null).range(-20, 60, 1).label((v, x) -> (int) v + " °C"));
        all.add(server(Config.CROP_HEATERS, "Heaters").tab(CROPS, "Heaters and coolers")
                .desc("Blocks that keep crops near them at least this warm. Distance counts like light (steps along x, y",
                        "and z). The defaults are the blocks that melt snow, with the same reach, so crops are safe from",
                        "frost where snow can't lie. Light sources count only while lit. Flames warm enough to grow crops;",
                        "cold lights only keep frost off (2 °C).")
                .cost(Cost.LOW, "Looked up around growing crops, one chunk section at a time, then kept.")
                .format("block=°C,distance   e.g. minecraft:campfire=16,3"));
        all.add(server(Config.CROP_COOLERS, "Coolers").tab(CROPS, null)
                .desc("Blocks that keep crops near them at most this warm (same distance as heaters).")
                .cost(Cost.LOW, "Looked up around growing crops, one chunk section at a time, then kept.")
                .format("block=°C,distance   e.g. minecraft:ice=20,3"));
        all.add(client(ClientConfig.CROP_LOOKS, "Crop looks").tab(CROPS, "Looks")
                .desc("Crops that don't grow show it in 4 steps: a hint while they grow slowly near a limit, then",
                        "stronger the further past it (dormant from cold, wilted from heat).")
                .cost(Cost.LOW, "A small check per crop while chunks are drawn."));
        all.add(client(ClientConfig.CROP_COLORS, "Crop colors").tab(CROPS, null)
                .desc("Colors of crops that don't grow.", "Default: greyish blue-green (cold), dark yellow (heat).",
                        "Beige and orange: winter beige (cold), dry orange (heat).")
                .choice(ClientConfig.CropColors.DEFAULT, "Default").choice(ClientConfig.CropColors.ECLIPTIC, "Beige and orange")
                .cost(Cost.NONE, null).applies("Reloads textures when you press Done."));

        // ---- Snow
        all.add(common(SnowConfig.FORCE_SNOWFALL, "Snowfall when cold").tab(SNOW, "Snowfall")
                .desc("Where a player is, it's cold enough to snow and no snow lies, a snowfall comes within a day (at",
                        "most one a day). Weather is global, so it rains where it's warm.")
                .cost(Cost.NONE, null));
        all.add(common(SnowConfig.SNOWFALL_TICKS, "Snowfall length").tab(SNOW, null)
                .desc("How long that snowfall lasts, in in-game hours (1 hour = 50 real seconds).")
                .cost(Cost.NONE, null).scale(0.001).range(0.25, 24, 0.25)
                .label((h, x) -> String.format(Locale.ROOT, "%s h (%s min real)", Setting.trim(h), Setting.trim(Math.round(h * 50 / 6) / 10.0))));
        all.add(common(SnowConfig.SNOW_LOCKS, "Snow locks (testing)").tab(SNOW, null)
                .desc("TEMPORARY. On: biomes in the tag seasonsrework:always_snow have snow all year, biomes with no snow",
                        "days or warm without seasons never get any, and blending leaves both alone.",
                        "Off: every biome follows its snow days and blending changes all of them.")
                .cost(Cost.NONE, null));
        all.add(common(SnowConfig.SNOW_COVER, "Snow cover").tab(SNOW, "Snow cover")
                .desc("Puts snow and ice on the ground following the snowfall, and melts them. Near players it changes",
                        "bit by bit: snow builds while it snows and melts a layer at a time after. A chunk that loads",
                        "catches up at once. Speed and time budget: Performance tab.")
                .cost(Cost.MEDIUM, "Server work each tick near players, capped by the time budget (Performance tab)."));
        all.add(common(SnowConfig.SNOW_DEPTH, "Depth by climate").tab(SNOW, null)
                .desc("Deeper snow the colder the last snowfall was, up to a limit per biome (more snowy days a year,",
                        "deeper: 1 to 6 layers). Off: always 1 layer, like vanilla.")
                .cost(Cost.NONE, null));
        all.add(common(SnowConfig.SNOWY_PLANTS, "Snowy plants").tab(SNOW, "Snowy plants")
                .desc("Snow lies in grass, ferns, flowers and other plants (the plant stays itself), and around fences and",
                        "walls. Breaking takes the snow first. Removing the mod just takes the snow away.")
                .cost(Cost.LOW, "Snowy plants draw a few more faces."));
        all.add(common(SnowConfig.STORAGE, "Snow storage (testing)").tab(SNOW, null)
                .desc("TEMPORARY. How new snow in plants is kept: a state of the plant itself, or a snow block with the",
                        "plant kept inside. Snow of the other kind already in the world keeps working.")
                .choice(SnowConfig.Storage.BLOCK_STATE, "Block state").choice(SnowConfig.Storage.BLOCK_ENTITY, "Block entity")
                .cost(Cost.NONE, null));
        all.add(client(ClientConfig.SNOW_ANIMATION, "Snow animation").tab(SNOW, "Animation and looks")
                .desc("Snow that falls or melts near you slides up out of the ground or sinks into it instead of popping.")
                .cost(Cost.LOW, "Moving copies of the snow are drawn while it slides."));
        all.add(client(ClientConfig.SNOW_ANIMATION_MS, "Animation time").tab(SNOW, null)
                .desc("How long the slide takes.")
                .cost(Cost.NONE, null).scale(0.001).range(0.05, 5, 0.05).label((s, x) -> String.format(Locale.ROOT, "%.2f s", s)));
        all.add(client(ClientConfig.SNOW_LOOK, "Snowy textures follow").tab(SNOW, null)
                .desc("Snowy textures and far-terrain snow fade in over 4 steps, the snowy texture drawn over the normal one",
                        "from the top down.",
                        "Ground: the snow that's there (plants the snow in them, leaves the snow on them, crops their own",
                        "snow, the rest where snow lies; deeper and colder = more steps).",
                        "Calendar: the temperature now (season, day, height), like the colors; not under a roof or near a heater.")
                .choice(ClientConfig.SnowLook.GROUND, "Ground").choice(ClientConfig.SnowLook.CALENDAR, "Calendar")
                .cost(Cost.NONE, null).applies("Redraws chunks when you press Done."));
        all.add(client(ClientConfig.SNOW_MODELS, "Snowy leaves").tab(SNOW, null)
                .desc("New: a thin snow layer on top of the leaves. Vanilla: snow drawn on the leaves' own faces.")
                .choice(ClientConfig.SnowModels.NEW, "New").choice(ClientConfig.SnowModels.VANILLA, "Vanilla")
                .cost(Cost.LOW, "New draws a few more faces on snowy leaves.").applies("Redraws chunks when you press Done."));

        // ---- Looks
        all.add(client(ClientConfig.GRASS, "Grass colors").tab(LOOKS, "Colors")
                .desc("Seasonal grass colors. The year has 24 color steps, 2 per season part.")
                .cost(Cost.LOW, "A color lookup per grass block while chunks are drawn.").applies("Redraws chunks when you press Done."));
        all.add(client(ClientConfig.FOLIAGE, "Leaf colors").tab(LOOKS, null)
                .desc("Seasonal colors of leaves that take the biome's color (oak, jungle, acacia, dark oak...).")
                .cost(Cost.LOW, "A color lookup per leaf block while chunks are drawn.").applies("Redraws chunks when you press Done."));
        all.add(client(ClientConfig.SMOOTH, "Smooth color change").tab(LOOKS, null)
                .desc("Blend each color step into the next over its days. Off: colors jump once per step.")
                .cost(Cost.MEDIUM, "Each blend update redraws the chunks around you (how often: Performance tab)."));
        all.add(client(ClientConfig.SPRUCE, "Spruce").tab(LOOKS, "Tree colors")
                .desc("Seasonal spruce leaf colors.").cost(Cost.NONE, null).applies("Redraws chunks when you press Done."));
        all.add(client(ClientConfig.BIRCH, "Birch").tab(LOOKS, null)
                .desc("Seasonal birch leaf colors.").cost(Cost.NONE, null).applies("Redraws chunks when you press Done."));
        all.add(client(ClientConfig.BIRCH_PATCHES, "Birch autumn patches").tab(LOOKS, null)
                .desc("Autumn birches vary from yellow to orange depending on where they stand.")
                .cost(Cost.NONE, null).applies("Redraws chunks when you press Done."));
        all.add(client(ClientConfig.MANGROVE, "Mangrove").tab(LOOKS, null)
                .desc("Seasonal mangrove leaf colors.").cost(Cost.NONE, null).applies("Redraws chunks when you press Done."));
        all.add(client(ClientConfig.SEASONAL_TEXTURES, "Seasonal textures").tab(LOOKS, "Textures")
                .desc("Seasonal block textures from resource packs and the built-in seasonal plants (flowers that bud,",
                        "bloom and go to seed; snowy textures).")
                .cost(Cost.MEDIUM, "Extra work for each seasonal block while chunks are drawn.")
                .applies("Redraws chunks when you press Done."));

        // ---- Distant Horizons
        all.add(client(ClientConfig.DH_REFRESH, "Recolor LODs").tab(DH, "This computer")
                .desc("Recolor Distant Horizons LODs when the season colors change, nearest first.")
                .cost(Cost.MEDIUM, "LODs are rebuilt after each color step, spread over time."));
        all.add(client(ClientConfig.DH_SNOW, "Snow and ice on LODs").tab(DH, null)
                .desc("Seasonal snow and ice on LODs: snow where real snow would lie, frozen open water, and snow or ice",
                        "saved while it was cold removed once it's warm. Asks the server for the climate blending that far.")
                .cost(Cost.MEDIUM, "LODs are rebuilt as the snow changes; the server works out far blending (below)."));
        all.add(server(Config.DH_BLEND_DISTANCE, "Far blending distance").tab(DH, "Server")
                .desc("Players with Distant Horizons get the climate blending out to their LOD distance, up to this far,",
                        "so far snow and ice match the blending too. Worked out a little each tick, nearest first.")
                .cost(Cost.HIGH, "Server work for every far chunk (spread over ticks); more distance, more chunks.")
                .range(0, 16384, 256).label((v, x) -> v == 0 ? "Off" : (int) v + " blocks (" + (int) v / 16 + " chunks)"));

        // ---- Performance
        all.add(common(SnowConfig.COLUMNS_PER_TICK, "Snow speed").tab(PERFORMANCE, "Server: snow cover")
                .desc("How fast snow builds while it snows and melts after, near players, as a multiple of vanilla",
                        "snowfall (which adds on top). 1x: a chunk is about 45% covered after 1 minute of snowfall,",
                        "70% after 2, 95% after 5.")
                .cost(Cost.MEDIUM, "Higher = more blocks changed each tick (within the time budget below).")
                .scale(1 / VANILLA_COLUMNS).range(0, 16, 0.25).label((v, x) -> v == 0 ? "Off" : Setting.trim(v) + "x vanilla"));
        all.add(common(SnowConfig.BUDGET_MICROS, "Time budget").tab(PERFORMANCE, null)
                .desc("Most time per server tick the snow cover may use. A tick is 50 ms. Lower = less lag; the snow",
                        "just changes more slowly.")
                .cost(Cost.HIGH, "This is the cap itself: higher lets snow use more of every tick.")
                .scale(0.001).range(0.1, 20, 0.1).label((ms, x) -> String.format(Locale.ROOT, "%.1f ms per tick", ms)));
        all.add(common(SnowConfig.SPARE_MICROS, "Spare time").tab(PERFORMANCE, null)
                .desc("When the server is ahead of schedule, snow may use up to this much more of the time until the",
                        "next tick (always leaving 10 ms). Snow fills in faster when the server isn't busy, without lag",
                        "when it is.")
                .cost(Cost.LOW, "Only time the server would spend waiting.")
                .scale(0.001).range(0, 40, 1).label((ms, x) -> ms == 0 ? "Off" : (int) ms + " ms"));
        all.add(common(SnowConfig.BIOME_CELL, "Biome edge detail").tab(PERFORMANCE, null)
                .desc("Biome edges of snow and ice. Exact: vanilla's irregular edges, checked per block (slowest).",
                        "2x2 or 4x4: one check per 2x2 or 4x4 blocks (faster, edges show small squares).")
                .choice(1, "Exact").choice(2, "2x2 blocks").choice(4, "4x4 blocks")
                .cost(Cost.SAVES, "2x2 checks 4 times fewer biomes, 4x4 16 times fewer."));
        all.add(client(ClientConfig.UPDATES_PER_STEP, "Color updates").tab(PERFORMANCE, "This computer: redraws")
                .desc("How many times per color step (1/24 of the year) the smooth color blend is updated. Each update",
                        "redraws the chunks you see (and LODs).")
                .cost(Cost.MEDIUM, "Each update redraws all visible chunks.")
                .range(1, 48, 1).label((v, x) -> {
                    double hours = x.daysPerSeason() / 6.0 / v * 24;
                    return (int) v + " (every " + (hours >= 24 ? days(Math.round(hours / 2.4) / 10.0) : Math.round(hours) + " h") + ")";
                }));
        all.add(client(ClientConfig.REDRAW_SECONDS, "Spread redraws").tab(PERFORMANCE, null)
                .desc("When snowy textures or colors change, nearby chunks are redrawn nearest first over this long",
                        "instead of all at once.")
                .cost(Cost.SAVES, "Longer = smaller stutters, the change just reaches far chunks later.")
                .range(0, 60, 1).label((s, x) -> s == 0 ? "All at once" : (int) s + " s"));
        all.add(client(ClientConfig.SNOW_ANIMATION_DISTANCE, "Animation distance").tab(PERFORMANCE, null)
                .desc("Only snow this close to you is animated; further away it just changes.")
                .cost(Cost.LOW, "Farther = more sliding snow drawn at once.")
                .range(4, 256, 4).label((v, x) -> (int) v + " blocks"));
        return List.copyOf(all);
    }

    private Settings() {}
}
