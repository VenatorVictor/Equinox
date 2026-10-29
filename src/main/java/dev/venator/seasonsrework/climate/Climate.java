package dev.venator.seasonsrework.climate;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.api.Season;
import dev.venator.seasonsrework.api.SeasonState;
import dev.venator.seasonsrework.api.Seasons;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.util.List;

/**
 * The seasonal climate. One temperature change for the whole world (global, like the weather), blended between the
 * config's values for each season part so the snow line moves a little every tick instead of jumping. Seasonal
 * biomes get it added to their temperature; tropical biomes keep their temperature and get a wet and a dry season.
 * The config can also give a biome another temperature for the climate only (grass colors read the biome's own value),
 * for biome mods that change temperatures to tint grass (WWOO: beach 2.0, old growth pine taiga 2.0...).
 */
public final class Climate {
    public static final TagKey<Biome> TROPICAL = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("seasonsrework", "tropical"));
    public static final TagKey<Biome> NO_SEASONS = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("seasonsrework", "no_seasons"));
    /** Hotter biomes than this (deserts, badlands...) have no seasons, as in Serene Seasons. */
    private static final float MAX_SEASONAL_TEMPERATURE = 0.8F;

    // Read by the Biome hooks on any thread; written once per tick.
    private static volatile float offset;
    private static volatile boolean wet, dry;
    // The biomes of the last tag load on each side, to sort again when the config changes.
    private static volatile List<Holder<Biome>> serverBiomes = List.of(), clientBiomes = List.of();

    public static float offset() {
        return offset;
    }

    /** Tropical wet season (summer): tropical biomes always get rain. */
    public static boolean wet() {
        return wet;
    }

    /** Tropical dry season (winter): no rain in tropical biomes. Spring and autumn keep the biome's own setting. */
    public static boolean dry() {
        return dry;
    }

    /** Follows the season of a seasonal level (server and client tick, level load). */
    public static void update(Level level) {
        SeasonState state = Seasons.get(level);
        if (state == null) return;
        offset = offsetAt(state);
        wet = state.season() == Season.SUMMER;
        dry = state.season() == Season.WINTER;
    }

    /** The config gives the change at the middle of each part; in between it is blended (wrapping winter to spring). */
    static float offsetAt(SeasonState state) {
        float[] values = Config.temperatureOffsets();
        int parts = Season.COUNT * SeasonState.PARTS;
        double position = (double) state.ticksInYear() / (state.seasonTicks() * Season.COUNT) * parts - 0.5;
        int index = (int) Math.floor(position);
        float blend = (float) (position - index);
        float from = values[Math.floorMod(index, parts)], to = values[Math.floorMod(index + 1, parts)];
        return from + (to - from) * blend;
    }

    /** Sorts the biomes of the (server or client) registry when tags load. */
    public static void categorize(TagsUpdatedEvent event) {
        event.getRegistryAccess().registry(Registries.BIOME).ifPresent(registry -> {
            List<Holder<Biome>> biomes = List.copyOf(registry.holders().toList());
            if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) clientBiomes = biomes;
            else serverBiomes = biomes;
            biomes.forEach(Climate::categorize);
            for (Holder<Biome> holder : biomes) ((ClimateBiome) (Object) holder.value()).seasonsrework$setColorProfile(ColorProfile.of(holder));
        });
    }

    /** The config changed (climate temperatures). */
    public static void recategorize() {
        serverBiomes.forEach(Climate::categorize);
        clientBiomes.forEach(Climate::categorize);
    }

    private static void categorize(Holder<Biome> holder) {
        float own = holder.value().getBaseTemperature();
        Float configured = holder.unwrapKey().map(key -> Config.biomeTemperature(key.location())).orElse(null);
        float temperature = configured != null ? configured : own;
        byte climate;
        if (holder.is(NO_SEASONS)) climate = ClimateBiome.NONE;
        else if (holder.is(TROPICAL)) climate = ClimateBiome.TROPICAL;
        else if (temperature > MAX_SEASONAL_TEMPERATURE) climate = ClimateBiome.HOT;
        else climate = ClimateBiome.SEASONAL;
        ((ClimateBiome) (Object) holder.value()).seasonsrework$setClimate(climate, temperature - own);
    }

    /** The temperature at pos without the season (snowy biomes and high peaks are cold all year). */
    public static float baseTemperature(Biome biome, net.minecraft.core.BlockPos pos) {
        ClimateBiome climate = (ClimateBiome) (Object) biome;
        float temperature = climate.seasonsrework$temperature(pos);
        return climate.seasonsrework$climate() == ClimateBiome.SEASONAL ? temperature - offset : temperature;
    }

    /** The temperature at pos now, season included (snow falls and water freezes below 0.15). */
    public static float temperature(Biome biome, net.minecraft.core.BlockPos pos) {
        return ((ClimateBiome) (Object) biome).seasonsrework$temperature(pos);
    }

    public static byte climateOf(Biome biome) {
        return ((ClimateBiome) (Object) biome).seasonsrework$climate();
    }

    private Climate() {}
}
