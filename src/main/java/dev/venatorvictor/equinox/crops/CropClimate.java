package dev.venatorvictor.equinox.crops;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.venatorvictor.equinox.api.Season;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.datamaps.DataMapType;

/**
 * A crop's temperature range in °C: dies below min (tonight's low) or above max (today's high); on the day's average
 * it's dormant below grows_from (alive, not growing, like winter wheat under snow), grows slowly up to ideal_min,
 * faster between ideal_min and ideal_max, slowly again up to grows_to, and stops above it (heat; dies there on dry
 * farmland). From the data map data/<namespace>/data_maps/block/crop_climate.json.
 */
public record CropClimate(float min, float growsFrom, float idealMin, float idealMax, float growsTo, float max) {
    public static final Codec<CropClimate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("min").forGetter(CropClimate::min),
            Codec.FLOAT.fieldOf("grows_from").forGetter(CropClimate::growsFrom),
            Codec.FLOAT.fieldOf("ideal_min").forGetter(CropClimate::idealMin),
            Codec.FLOAT.fieldOf("ideal_max").forGetter(CropClimate::idealMax),
            Codec.FLOAT.fieldOf("grows_to").forGetter(CropClimate::growsTo),
            Codec.FLOAT.fieldOf("max").forGetter(CropClimate::max)
    ).apply(instance, CropClimate::new));

    public static final DataMapType<Block, CropClimate> TYPE = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath("equinox", "crop_climate"), Registries.BLOCK, CODEC).synced(CODEC, false).build();

    /** Ranges for crops known only by their season tags (spring, summer, autumn, winter); several seasons: the union. */
    private static final CropClimate[] BY_SEASON = {
            new CropClimate(-5, 5, 10, 20, 28, 38), new CropClimate(2, 12, 18, 30, 33, 43),
            new CropClimate(-5, 5, 10, 20, 28, 38), new CropClimate(-15, 0, 5, 15, 22, 32)};

    /** From a season bit mask (bit = Season ordinal), or null for none. */
    static CropClimate ofSeasons(int seasons) {
        CropClimate result = null;
        for (Season season : Season.VALUES) {
            if ((seasons & 1 << season.ordinal()) == 0) continue;
            CropClimate c = BY_SEASON[season.ordinal()];
            result = result == null ? c : new CropClimate(Math.min(result.min, c.min), Math.min(result.growsFrom, c.growsFrom),
                    Math.min(result.idealMin, c.idealMin),
                    Math.max(result.idealMax, c.idealMax), Math.max(result.growsTo, c.growsTo), Math.max(result.max, c.max));
        }
        return result;
    }
}
