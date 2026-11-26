package dev.venatorvictor.equinox.weather;

import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.climate.ClimateBiome;
import dev.venatorvictor.equinox.climate.Zone;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

/**
 * How wet the land is, on a coarse grid (CELL blocks), so storms know where they may rain, out past the loaded chunks
 * too (far clouds). The server reads each cell's biome from the world's biome placement and sends tiles of TILE x TILE
 * cells to players; both sides then work out the same rain share, blended between cell centres (soft borders).
 * A cell keeps the biome's own downfall (0 if it has no rain) and its climate zone; the season's wet and dry parts are
 * applied when read (share).
 */
public final class ClimateGrid {
    public static final int CELL = 64, TILE = 8, TILE_BLOCKS = CELL * TILE, CELLS = TILE * TILE;
    /** Vanilla's share of the time it rains: rain lasts 12 000-24 000 ticks, clear weather 12 000-180 000 (means). */
    public static final float VANILLA_SHARE = 18000F / (18000F + 96000F);
    /** Downfall of a biome where vanilla's share applies (between plains 0.4 and forest 0.8). */
    private static final float BASE_DOWNFALL = 0.5F;
    /** Wet seasons of dry biomes: monsoon and savanna rain like a jungle (vanilla jungle 0.9); cold deserts rarely snow. */
    private static final float WET_SEASON_DOWNFALL = 0.9F, COLD_DESERT_DOWNFALL = 0.1F;
    private static final float MAX_SHARE = 0.6F;

    /** Tile key -> {downfall x 250 (0..250), zone ordinal} per cell, CELLS each. */
    private final Long2ObjectOpenHashMap<byte[]> tiles = new Long2ObjectOpenHashMap<>();

    public static long tileKey(int tileX, int tileZ) {
        return ChunkPos.asLong(tileX, tileZ);
    }

    public static int tileOf(int cell) {
        return Math.floorDiv(cell, TILE);
    }

    /** Server: makes a missing tile (null on clients: they wait for the server's). */
    private final java.util.function.LongFunction<byte[]> loader;

    public ClimateGrid(java.util.function.LongFunction<byte[]> loader) {
        this.loader = loader;
    }

    public synchronized byte[] tile(long key) {
        byte[] data = tiles.get(key);
        if (data == null && loader != null) {
            data = loader.apply(key);
            if (data != null) tiles.put(key, data);
        }
        return data;
    }

    public synchronized void put(long key, byte[] data) {
        if (data.length == CELLS * 2) tiles.put(key, data);
        version++;
    }

    private volatile int version;

    /** Changes when tiles arrive or go (clouds are drawn again). */
    public int version() {
        return version;
    }

    public synchronized boolean has(long key) {
        return tiles.containsKey(key);
    }

    public synchronized int size() {
        return tiles.size();
    }

    public synchronized void clear() {
        tiles.clear();
        version++;
    }

    /** Keeps tiles within radius (tiles) of a tile; the rest go. */
    public synchronized void trim(int centerX, int centerZ, int radius) {
        tiles.long2ObjectEntrySet().removeIf(e -> Math.max(Math.abs(ChunkPos.getX(e.getLongKey()) - centerX),
                Math.abs(ChunkPos.getZ(e.getLongKey()) - centerZ)) > radius);
    }

    /** Server: a tile from the world's biome placement (cave biomes under mountains: read higher up). */
    public static byte[] compute(ServerLevel level, int tileX, int tileZ) {
        byte[] data = new byte[CELLS * 2];
        int sea = level.getSeaLevel();
        for (int i = 0; i < TILE; i++) {
            for (int j = 0; j < TILE; j++) {
                int x = (tileX * TILE + i) * CELL + CELL / 2, z = (tileZ * TILE + j) * CELL + CELL / 2;
                Holder<Biome> holder = null;
                Zone zone = Zone.NONE;
                for (int y = sea; y <= sea + 128; y += 64) {
                    holder = level.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
                    zone = ((ClimateBiome) (Object) holder.value()).equinox$zone();
                    if (zone != Zone.CAVE) break;
                }
                Biome.ClimateSettings climate = holder.value().getModifiedClimateSettings();
                float downfall = climate.hasPrecipitation() ? Math.max(0, Math.min(1, climate.downfall())) : 0;
                int index = i * TILE + j;
                data[index] = (byte) Math.round(downfall * 250);
                data[CELLS + index] = (byte) zone.ordinal();
            }
        }
        return data;
    }

    /** Share of the time it rains (0..MAX_SHARE) for a downfall and zone now (wet and dry seasons). */
    public static float share(float downfall, Zone zone) {
        Boolean rain = Climate.precipitation(zone);
        if (rain == Boolean.FALSE) return 0;
        float d = downfall;
        if (rain == Boolean.TRUE) d = Math.max(d, zone == Zone.COLD_DESERT ? COLD_DESERT_DOWNFALL : WET_SEASON_DOWNFALL);
        if (d <= 0) return 0;
        return Math.min(MAX_SHARE, VANILLA_SHARE * d / BASE_DOWNFALL);
    }

    /** Rain share of a biome now (its own downfall and zone). */
    public static float share(Biome biome) {
        Biome.ClimateSettings climate = biome.getModifiedClimateSettings();
        float downfall = climate.hasPrecipitation() ? Math.max(0, Math.min(1, climate.downfall())) : 0;
        // the same rounding as a grid cell
        return share(Math.round(downfall * 250) / 250F, ((ClimateBiome) (Object) biome).equinox$zone());
    }

    private static final Zone[] ZONES = Zone.values();

    /** Rain share of one cell, or NaN if its tile is missing. */
    private float cellShare(int cellX, int cellZ) {
        byte[] data = tile(tileKey(tileOf(cellX), tileOf(cellZ)));
        if (data == null) return Float.NaN;
        int index = Math.floorMod(cellX, TILE) * TILE + Math.floorMod(cellZ, TILE);
        int zone = data[CELLS + index] & 0xFF;
        return share((data[index] & 0xFF) / 250F, zone < ZONES.length ? ZONES[zone] : Zone.NONE);
    }

    /** Rain share at a block, blended between the 4 nearest cell centres (missing cells count as dry). */
    public float share(double x, double z) {
        double gx = x / CELL - 0.5, gz = z / CELL - 0.5;
        int cx = (int) Math.floor(gx), cz = (int) Math.floor(gz);
        float fx = (float) (gx - cx), fz = (float) (gz - cz);
        float a = dry(cellShare(cx, cz)), b = dry(cellShare(cx + 1, cz)), c = dry(cellShare(cx, cz + 1)), d = dry(cellShare(cx + 1, cz + 1));
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    /** Climate zone of the cell a block is in, or NONE if its tile is missing. */
    public Zone zone(double x, double z) {
        int cx = (int) Math.floor(x / CELL), cz = (int) Math.floor(z / CELL);
        byte[] data = tile(tileKey(tileOf(cx), tileOf(cz)));
        if (data == null) return Zone.NONE;
        int zone = data[CELLS + Math.floorMod(cx, TILE) * TILE + Math.floorMod(cz, TILE)] & 0xFF;
        return zone < ZONES.length ? ZONES[zone] : Zone.NONE;
    }

    private static float dry(float share) {
        return Float.isNaN(share) ? 0 : share;
    }
}
