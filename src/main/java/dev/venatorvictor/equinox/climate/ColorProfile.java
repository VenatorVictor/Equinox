package dev.venatorvictor.equinox.climate;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.common.Tags;

import java.util.List;

/**
 * How strongly a biome's grass and leaves change color through the year (color profiles). Picked by
 * biome tags, never by temperature (some biome mods change temperatures to tint grass): an explicit color tag
 * first (ours, then the eclipticseasons names so biome mods that ship those work), otherwise the profile rules in order.
 * Stored on the biome when tags load, with the climate.
 */
public enum ColorProfile {
    STABLE("stable"),
    SLIGHTLY("slightly"),
    MONSOONAL("monsoonal"),
    SEASONAL_HOT("seasonal/hot"),
    SEASONAL_COLD("seasonal/cold"),
    SEASONAL("seasonal");

    public static final ColorProfile[] VALUES = values();

    private final List<TagKey<Biome>> tags;

    ColorProfile(String name) {
        this.tags = List.of(tag("equinox", name), tag("eclipticseasons", name));
    }

    public static ColorProfile of(Holder<Biome> b) {
        for (ColorProfile profile : VALUES) {
            for (TagKey<Biome> tag : profile.tags) if (b.is(tag)) return profile;
        }
        if (b.is(Climate.NO_SEASONS)) return STABLE;
        if (b.is(Tags.Biomes.IS_CAVE) || b.is(Tags.Biomes.IS_BADLANDS) || b.is(Tags.Biomes.IS_DESERT) || b.is(Tags.Biomes.IS_VOID))
            return STABLE;
        if (b.is(Tags.Biomes.IS_BEACH) || b.is(Tags.Biomes.IS_OCEAN) || b.is(Biomes.JUNGLE)) return SLIGHTLY;
        if (b.is(Tags.Biomes.IS_SAVANNA)) return MONSOONAL;
        if (b.is(Tags.Biomes.IS_HOT_OVERWORLD)) return SEASONAL_HOT;
        if ((b.is(Tags.Biomes.IS_MOUNTAIN_PEAK) || b.is(Tags.Biomes.IS_SNOWY) || b.is(Tags.Biomes.IS_ICY)) && b.is(Tags.Biomes.IS_OVERWORLD))
            return SEASONAL_COLD;
        if (b.is(Tags.Biomes.IS_OVERWORLD)) return SEASONAL;
        return STABLE;
    }

    private static TagKey<Biome> tag(String namespace, String path) {
        return TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(namespace, "color/" + path));
    }
}
