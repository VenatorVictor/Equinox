package dev.venator.seasonsrework.climate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.registries.datamaps.DataMapType;

/**
 * How many days a year snow lies in a biome, in real days of a 365-day year (a real place's average, see the Biome
 * Climate Table): a share of the year, so it fits any days_per_season. The biome's temperature is tuned so its
 * seasonal curve is cold enough to snow (below 0.15) for this share of the year (Climate.categorize); height and the
 * season and daily variation still move it. 0 = never, 365 = all year. Biomes without a value keep their curve.
 * From data/<namespace>/data_maps/worldgen/biome/snow.json.
 */
public record BiomeSnow(float days) {
    public static final Codec<BiomeSnow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.floatRange(0, 365).fieldOf("days").forGetter(BiomeSnow::days)
    ).apply(instance, BiomeSnow::new));

    public static final DataMapType<Biome, BiomeSnow> TYPE = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath("seasonsrework", "snow"), Registries.BIOME, CODEC).synced(CODEC, false).build();
}
