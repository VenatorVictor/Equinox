package dev.venatorvictor.equinox.season;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.venatorvictor.equinox.api.Season;
import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.api.Seasons;
import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.climate.ClimateBiome;
import dev.venatorvictor.equinox.snow.SnowRules;
import dev.venatorvictor.equinox.snow.Snowpack;
import dev.venatorvictor.equinox.climate.Zone;
import dev.venatorvictor.equinox.api.Temperatures;
import dev.venatorvictor.equinox.daylength.DayLength;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Arrays;
import java.util.List;

/**
 * /season, /season get: show the season. /season set <season> [week 1-24] (4-day weeks; up to the season's own count): jump to the start of it this year.
 * /season add <days>. Setting moves the day count (the season is the day count), keeping the time of day.
 */
public final class SeasonCommand {
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set").requires(source -> source.hasPermission(2));
        set.then(Commands.argument("season", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(Season.VALUES).map(Season::id), builder))
                .executes(context -> set(context.getSource(), StringArgumentType.getString(context, "season"), 1))
                .then(Commands.argument("week", IntegerArgumentType.integer(1, 24))
                        .executes(context -> set(context.getSource(), StringArgumentType.getString(context, "season"),
                                IntegerArgumentType.getInteger(context, "week")))));
        event.getDispatcher().register(Commands.literal("season")
                .executes(context -> show(context.getSource()))
                .then(Commands.literal("get").executes(context -> show(context.getSource())))
                .then(set)
                .then(Commands.literal("add").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("days", IntegerArgumentType.integer())
                                .executes(context -> add(context.getSource(), IntegerArgumentType.getInteger(context, "days"))))));
    }

    private static int show(CommandSourceStack source) {
        SeasonState state = Seasons.get(source.getLevel());
        if (state == null) return noSeasons(source);
        // Easy to read: the season in its colour, then one line per topic, a coloured label, grey text, white numbers;
        // the testing lines (climate, surroundings, zone) last and dimmer.
        ChatFormatting colour = switch (state.season()) {
            case SPRING -> ChatFormatting.GREEN;
            case SUMMER -> ChatFormatting.YELLOW;
            case AUTUMN -> ChatFormatting.GOLD;
            case WINTER -> ChatFormatting.AQUA;
        };
        Component title = Component.translatable("commands.equinox.season.title",
                Component.translatable("equinox.season." + state.season().id()), state.weekOfSeason() + 1).withStyle(colour, ChatFormatting.BOLD);
        send(source, Component.translatable("commands.equinox.season.show", title, value(state.dayOfSeason() + 1),
                value(state.daysPerSeason()), value(state.year() + 1)).withStyle(ChatFormatting.GRAY));
        send(source, line("date", ChatFormatting.LIGHT_PURPLE, false,
                Component.translatable("equinox.month." + state.month()).withStyle(ChatFormatting.WHITE), value(state.dayOfMonth() + 1),
                value(state.monthDays()), Component.translatable("equinox.moon." + state.moonPhase()).withStyle(ChatFormatting.WHITE)));
        BlockPos pos = BlockPos.containing(source.getPosition());
        Temperatures.DayRange today = Temperatures.airToday(source.getLevel(), pos);
        send(source, line("weather", ChatFormatting.GOLD, false,
                value(String.format("%.1f", Temperatures.air(source.getLevel(), pos))), value(String.format("%.1f", today.low())),
                value(String.format("%.1f", today.high())), value(String.format("%.1f", Temperatures.water(source.getLevel(), pos))),
                value(String.format("%.1f", DayLength.daylightHours(state.dayTime(), state.daysPerSeason())))));
        // Snow: whether it lies here, the biome's snow days a year, its warmest since the last snowfall (snow lies below 0.15).
        var holder = source.getLevel().getBiome(pos);
        var snowDays = holder.getData(dev.venatorvictor.equinox.climate.BiomeSnow.TYPE);
        Float warmest = holder.unwrapKey().map(Snowpack.warmest()::get).orElse(null);
        send(source, line("snow", ChatFormatting.AQUA, false,
                Component.translatable(SnowRules.lies(holder, pos, Snowpack.warmest()) ? "gui.yes" : "gui.no").withStyle(ChatFormatting.WHITE),
                value(snowDays != null ? String.format("%.0f", snowDays.days()) : "-"),
                value(warmest != null ? String.format("%.2f", warmest) : "-")));
        // For testing: the biome's climate zone and seasonal change (with the season's and the day's variation, before
        // the zone's factor), and the biome's temperature here (snow and ice below 0.15).
        Biome biome = source.getLevel().getBiome(pos).value();
        Zone zone = Climate.zoneOf(biome);
        String climate = zone.id + (Climate.wet(zone) ? "_wet" : Climate.dry(zone) ? "_dry" : "");
        send(source, line("climate", ChatFormatting.DARK_AQUA, true,
                Component.translatable("equinox.climate." + climate), String.format("%+.2f", Climate.change(biome)),
                String.format("%+.2f", Climate.seasonVariation()), String.format("%+.2f", Climate.dailyVariation()),
                String.format("%.2f", ((ClimateBiome) (Object) biome).equinox$temperature(pos))));
        // Climate blending: the biomes around (largest weights first) and how much warmer (+) or colder they make it here.
        float blend = dev.venatorvictor.equinox.climate.ClimateBlend.delta(biome, pos, ((ClimateBiome) (Object) biome).equinox$blendKind());
        var mix = dev.venatorvictor.equinox.climate.ClimateBlend.mix(pos.getX() >> 4, pos.getZ() >> 4);
        StringBuilder around = new StringBuilder();
        if (mix != null) {
            var registry = source.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            Integer[] order = new Integer[mix.weights().length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Float.compare(mix.weights()[b], mix.weights()[a]));
            for (int i : order) {
                if (!around.isEmpty()) around.append(", ");
                var key = registry.getKey(mix.biomes()[i]);
                around.append(key != null ? key.getPath() : "?").append(' ').append(Math.round(mix.weights()[i] * 100)).append('%');
            }
        }
        send(source, line("blend", ChatFormatting.DARK_AQUA, true, String.format("%+.2f", blend), around.isEmpty() ? "-" : around.toString()));
        // Climate zone: how much of the winter's cold applies here (100% = full winter, 0% = none, near the warm bands).
        float cooling = dev.venatorvictor.equinox.climate.ClimateBlend.coolingAt(false, pos);
        send(source, line("zone", ChatFormatting.DARK_AQUA, true, Float.isNaN(cooling) ? "-" : Math.round(cooling * 100) + "%"));
        return state.week() + 1;
    }

    private static void send(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }

    private static Component value(Object value) {
        return Component.literal(String.valueOf(value)).withStyle(ChatFormatting.WHITE);
    }

    /** "Label  text": the label in its colour, the text grey (testing lines: dark grey, values grey). */
    private static Component line(String key, ChatFormatting labelColour, boolean testing, Object... args) {
        if (testing) for (int i = 0; i < args.length; i++)
            if (!(args[i] instanceof Component)) args[i] = Component.literal(String.valueOf(args[i])).withStyle(ChatFormatting.GRAY);
        return Component.empty()
                .append(Component.translatable("commands.equinox.season.label." + key).withStyle(labelColour))
                .append(Component.literal("  "))
                .append(Component.translatable("commands.equinox.season." + key, args)
                        .withStyle(testing ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY));
    }

    private static int set(CommandSourceStack source, String seasonId, int week) {
        SeasonState state = Seasons.get(source.getLevel());
        if (state == null) return noSeasons(source);
        Season season = Season.byId(seasonId);
        if (season == null) {
            source.sendFailure(Component.translatable("commands.equinox.season.unknown", seasonId));
            return 0;
        }
        SeasonClock.setDayTime(source.getServer(), SeasonClock.startOf(state, season.ordinal(), week - 1));
        return show(source);
    }

    private static int add(CommandSourceStack source, int days) {
        SeasonState state = Seasons.get(source.getLevel());
        if (state == null) return noSeasons(source);
        SeasonClock.setDayTime(source.getServer(), state.dayTime() + (long) days * SeasonState.DAY);
        return show(source);
    }

    private static int noSeasons(CommandSourceStack source) {
        source.sendFailure(Component.translatable("commands.equinox.season.none"));
        return 0;
    }

    private SeasonCommand() {}
}
