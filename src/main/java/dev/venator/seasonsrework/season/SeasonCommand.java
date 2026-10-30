package dev.venator.seasonsrework.season;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.venator.seasonsrework.api.Season;
import dev.venator.seasonsrework.api.SeasonState;
import dev.venator.seasonsrework.api.Seasons;
import dev.venator.seasonsrework.climate.Climate;
import dev.venator.seasonsrework.climate.ClimateBiome;
import dev.venator.seasonsrework.climate.Zone;
import dev.venator.seasonsrework.daylength.DayLength;
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
 * /season, /season get: show the season. /season set <season> [early|mid|late]: jump to the start of it this year.
 * /season add <days>. Setting moves the day count (the season is the day count), keeping the time of day.
 */
public final class SeasonCommand {
    private static final List<String> PARTS = List.of("early", "mid", "late");

    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set").requires(source -> source.hasPermission(2));
        set.then(Commands.argument("season", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(Season.VALUES).map(Season::id), builder))
                .executes(context -> set(context.getSource(), StringArgumentType.getString(context, "season"), "early"))
                .then(Commands.argument("part", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(PARTS, builder))
                        .executes(context -> set(context.getSource(), StringArgumentType.getString(context, "season"),
                                StringArgumentType.getString(context, "part")))));
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
        source.sendSuccess(() -> Component.translatable("commands.seasonsrework.season.show",
                Component.translatable("seasonsrework.part." + PARTS.get(state.part())),
                Component.translatable("seasonsrework.season." + state.season().id()),
                state.dayOfSeason() + 1, state.daysPerSeason(), state.year() + 1), false);
        // For testing: the biome's climate zone and seasonal change (with this season's variation), and the biome's temperature here (snow and ice below 0.15).
        BlockPos pos = BlockPos.containing(source.getPosition());
        Biome biome = source.getLevel().getBiome(pos).value();
        Zone zone = Climate.zoneOf(biome);
        String climate = zone.id + (Climate.wet(zone) ? "_wet" : Climate.dry(zone) ? "_dry" : "");
        source.sendSuccess(() -> Component.translatable("commands.seasonsrework.season.climate",
                Component.translatable("seasonsrework.climate." + climate), String.format("%+.2f", Climate.change(biome)),
                String.format("%+.2f", Climate.variation()), String.format("%.2f", ((ClimateBiome) (Object) biome).seasonsrework$temperature(pos))), false);
        source.sendSuccess(() -> Component.translatable("commands.seasonsrework.season.daylight",
                String.format("%.1f", DayLength.daylightHours(state.dayTime(), state.daysPerSeason()))), false);
        return state.season().ordinal() * SeasonState.PARTS + state.part() + 1;
    }

    private static int set(CommandSourceStack source, String seasonId, String partId) {
        SeasonState state = Seasons.get(source.getLevel());
        if (state == null) return noSeasons(source);
        Season season = Season.byId(seasonId);
        int part = PARTS.indexOf(partId);
        if (season == null || part < 0) {
            source.sendFailure(Component.translatable("commands.seasonsrework.season.unknown", seasonId + " " + partId));
            return 0;
        }
        SeasonClock.setDayTime(source.getServer(), SeasonClock.startOf(state, season.ordinal(), part));
        return show(source);
    }

    private static int add(CommandSourceStack source, int days) {
        SeasonState state = Seasons.get(source.getLevel());
        if (state == null) return noSeasons(source);
        SeasonClock.setDayTime(source.getServer(), state.dayTime() + (long) days * SeasonState.DAY);
        return show(source);
    }

    private static int noSeasons(CommandSourceStack source) {
        source.sendFailure(Component.translatable("commands.seasonsrework.season.none"));
        return 0;
    }

    private SeasonCommand() {}
}
