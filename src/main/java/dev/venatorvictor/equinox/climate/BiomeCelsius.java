package dev.venatorvictor.equinox.climate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.registries.datamaps.DataMapType;

import java.util.List;
import java.util.Optional;

/**
 * A biome's air temperature in °C in spring and autumn, at midnight (night) and noon (day), and its water temperature
 * (optional: else worked out from the air). From the data map
 * data/<namespace>/data_maps/worldgen/biome/celsius.json; first values from dev/specs/coldsweat-biome-temperatures.md.
 * In cave biomes the value replaces the season and the depth (lush caves 20, deep dark 5).
 * <p>
 * seasons (optional): the biome's own year, midnight and noon at mid spring, summer, autumn and winter (8 values:
 * night, day, night, day...; a real place's April, July, October, January). Replaces its climate zone's change
 * (config celsius_changes); night and day should then be the spring and autumn average.
 */
public record BiomeCelsius(float night, float day, Optional<Float> water, Optional<List<Float>> seasons) {
    public static final Codec<BiomeCelsius> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("night").forGetter(BiomeCelsius::night),
            Codec.FLOAT.fieldOf("day").forGetter(BiomeCelsius::day),
            Codec.FLOAT.optionalFieldOf("water").forGetter(BiomeCelsius::water),
            Codec.FLOAT.listOf(8, 8).optionalFieldOf("seasons").forGetter(BiomeCelsius::seasons)
    ).apply(instance, BiomeCelsius::new));

    /** The biome's own change from night/day at each season middle (8 values, like celsius_changes), or null. */
    public float[] changes() {
        if (seasons.isEmpty()) return null;
        List<Float> values = seasons.get();
        float[] changes = new float[8];
        for (int i = 0; i < 8; i++) changes[i] = values.get(i) - (i % 2 == 0 ? night : day);
        return changes;
    }

    public static final DataMapType<Biome, BiomeCelsius> TYPE = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath("equinox", "celsius"), Registries.BIOME, CODEC).synced(CODEC, false).build();
}
