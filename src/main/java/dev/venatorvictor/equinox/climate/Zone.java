package dev.venatorvictor.equinox.climate;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * A biome's climate, from the tag equinox:climate/<id>, each modelled on a real place (Vienna, New Orleans,
 * Helsinki, Utqiagvik, Säntis, Svalbard, Phoenix, Moab, Manaus, Kolkata, Darwin). Seasonal zones change the biome's
 * °C by their value at the start of each season's mid part (config celsius_changes), blended in between, and their Minecraft
 * temperature follows the °C (Climate.categorize). Every land zone has seasons, with its real swing: tropical ones
 * barely change (savanna about 4 °C, jungle none) and also get wet and dry seasons (rain); leaves don't fall there.
 */
public enum Zone {
    TEMPERATE(ClimateBiome.SEASONAL, false, 1F),
    MILD(ClimateBiome.SEASONAL, false, 1F),
    BOREAL(ClimateBiome.SEASONAL, false, 1.5F),
    TUNDRA(ClimateBiome.SEASONAL, true, 1.5F),
    ALPINE(ClimateBiome.SEASONAL, false, 1.5F),
    POLAR_SEA(ClimateBiome.SEASONAL, true, 1.5F),
    /** Never rains or snows (Climate.precipitation). */
    HOT_DESERT(ClimateBiome.SEASONAL, false, 0.8F),
    COLD_DESERT(ClimateBiome.SEASONAL, false, 0.8F),
    /** Rain all year, no dry season. */
    RAINFOREST(ClimateBiome.SEASONAL, false, 0.3F, true),
    /** Wet summer, dry winter. */
    MONSOON(ClimateBiome.SEASONAL, false, 0.3F, true),
    /** Wet summer, dry from late autumn to early spring. */
    SAVANNA(ClimateBiome.SEASONAL, false, 0.3F, true),
    /** Oceans: no seasons in Minecraft (snow, ice), but the °C climate changes a little (sea lags, tropics barely). */
    WARM_OCEAN(ClimateBiome.NONE, false, 0.3F),
    LUKEWARM_OCEAN(ClimateBiome.NONE, false, 0.3F),
    OCEAN(ClimateBiome.NONE, false, 0.3F),
    COLD_OCEAN(ClimateBiome.NONE, false, 0.3F),
    /** Cave biomes: no seasons underground. */
    CAVE(ClimateBiome.NONE, false, 0F),
    /** Untagged biomes hotter than 0.8: tropical; crops grow as in the tropics. */
    HOT(ClimateBiome.SEASONAL, false, 0.3F, true),
    /** Nether, end, untagged oceans. */
    NONE(ClimateBiome.NONE, false, 0.3F);

    /** Tundra and polar seas warm to at least this daily mean (°C) in mid summer, so the snow and the sea ice melt. */
    public static final float THAW = 5F;

    public final String id = name().toLowerCase(java.util.Locale.ROOT);
    public final TagKey<Biome> tag = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("equinox", "climate/" + id));
    /** ClimateBiome.SEASONAL or NONE. */
    public final byte kind;
    public final boolean thaws;
    /** Multiplies the season and daily variation (real-life spread: tropics small, the cold north big). */
    public final float factor;

    /** Warm all year: rain decides its seasons (wet and dry), leaves don't fall, no tree fruit to pick. */
    public final boolean tropical;

    Zone(byte kind, boolean thaws, float factor) {
        this(kind, thaws, factor, false);
    }

    Zone(byte kind, boolean thaws, float factor, boolean tropical) {
        this.kind = kind;
        this.thaws = thaws;
        this.factor = factor;
        this.tropical = tropical;
    }

    /** Salt water: freezes at −2 °C instead of 0. */
    public boolean sea() {
        return this == WARM_OCEAN || this == LUKEWARM_OCEAN || this == OCEAN || this == COLD_OCEAN || this == POLAR_SEA;
    }

    public boolean seasonal() {
        return kind == ClimateBiome.SEASONAL;
    }

    public static Zone byId(String id) {
        for (Zone zone : values()) if (zone.id.equals(id)) return zone;
        return null;
    }
}
