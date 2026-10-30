package dev.venator.seasonsrework.crops;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.venator.seasonsrework.api.Season;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.datamaps.DataMapType;

/**
 * A crop's temperature range in °C: dies below min (tonight's low) or above max (today's high), grows faster between
 * ideal_min and ideal_max (day's average). From the data map data/<namespace>/data_maps/block/crop_climate.json.
 */
public record CropClimate(float min, float idealMin, float idealMax, float max) {
    public static final Codec<CropClimate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("min").forGetter(CropClimate::min),
            Codec.FLOAT.fieldOf("ideal_min").forGetter(CropClimate::idealMin),
            Codec.FLOAT.fieldOf("ideal_max").forGetter(CropClimate::idealMax),
            Codec.FLOAT.fieldOf("max").forGetter(CropClimate::max)
    ).apply(instance, CropClimate::new));

    public static final DataMapType<Block, CropClimate> TYPE = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath("seasonsrework", "crop_climate"), Registries.BLOCK, CODEC).synced(CODEC, false).build();

    /** Ranges for crops known only by their season tags (spring, summer, autumn, winter); several seasons: the union. */
    private static final CropClimate[] BY_SEASON = {
            new CropClimate(-5, 8, 20, 30), new CropClimate(2, 18, 30, 40),
            new CropClimate(-5, 8, 20, 30), new CropClimate(-15, 0, 12, 25)};

    /** From a season bit mask (bit = Season ordinal), or null for none. */
    static CropClimate ofSeasons(int seasons) {
        CropClimate result = null;
        for (Season season : Season.VALUES) {
            if ((seasons & 1 << season.ordinal()) == 0) continue;
            CropClimate c = BY_SEASON[season.ordinal()];
            result = result == null ? c : new CropClimate(Math.min(result.min, c.min), Math.min(result.idealMin, c.idealMin),
                    Math.max(result.idealMax, c.idealMax), Math.max(result.max, c.max));
        }
        return result;
    }
}
