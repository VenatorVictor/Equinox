package dev.venatorvictor.equinox.snow;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.climate.Climate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * The snowpack, per seasonal biome: the warmest temperature (at sea level) since it last snowed there. While it
 * rains or snows the value follows the temperature; after, it only goes up. So snow lies where it has stayed cold
 * since the last snowfall (SnowRules.lies): a thaw or rain takes it away, the next snowfall brings it back, a colder
 * snowfall covers more (patches, then everywhere, then deeper). Sent to clients for LOD snow and snowy textures.
 * Also starts a snowfall within a day where a player is and it's cold enough to snow but nothing lies. Overworld only.
 */
public final class Snowpack {
    /** Values move in steps of this before clients are told (and the snow cover redone). */
    private static final float STEP = 0.01F;
    private static final int DAY = 24000;

    /** The server's values; read by the snow cover, snowfall, crops. Replaced whole, never changed in place. */
    private static volatile Map<ResourceKey<Biome>, Float> warmest = Map.of();
    private static volatile int version;
    private static volatile Data current;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) tick(level);
        });
        NeoForge.EVENT_BUS.addListener(LevelEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) publish(current = data(level), false);
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer player) PacketDistributor.sendToPlayer(player, payload());
        });
    }

    /** The server's warmest-since-snowfall values (for SnowRules). */
    public static Map<ResourceKey<Biome>, Float> warmest() {
        return warmest;
    }

    /** Game time it last rained or snowed in a biome (Long.MIN_VALUE: never). Server. */
    public static long lastSnowfall(Holder<Biome> biome) {
        Data data = current;
        ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
        Long time = data == null || key == null ? null : data.lastFall.get(key);
        return time == null ? Long.MIN_VALUE : time;
    }

    /** Changes when any value moves by STEP (clients are told then). */
    public static int version() {
        return version;
    }

    private static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0 || !Config.hasSeasons(level.dimension())) return;
        Data data = data(level);
        current = data;
        boolean raining = level.isRaining();
        for (Holder<Biome> holder : Climate.seasonalBiomes()) {
            ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
            if (key == null) continue;
            float now = Climate.surfaceTemperature(holder.value());
            Float old = data.warmest.get(key);
            // A new world (or biome) starts as if it had just snowed: worldgen snow stays where it's cold.
            boolean falling = old == null || raining && holder.value().hasPrecipitation();
            data.warmest.put(key, falling ? now : Math.max(old, now));
            // Worlds from before this was kept count as if it had just snowed too.
            if (falling || !data.lastFall.containsKey(key)) data.lastFall.put(key, level.getGameTime());
        }
        data.setDirty();
        publish(data, true);
        forceSnowfall(level, data, raining);
    }

    /** Takes the values when one moved by STEP since the last time; tells the clients. */
    private static void publish(Data data, boolean send) {
        int hash = 1;
        for (Map.Entry<ResourceKey<Biome>, Float> entry : data.warmest.entrySet())
            hash += entry.getKey().hashCode() ^ Math.round(entry.getValue() / STEP);
        if (hash == version && !warmest.isEmpty()) return;
        version = hash;
        warmest = Map.copyOf(data.warmest);
        if (send) PacketDistributor.sendToAllPlayers(payload());
    }

    private static SnowpackPayload payload() {
        Map<ResourceLocation, Float> values = new HashMap<>();
        warmest.forEach((key, value) -> values.put(key.location(), value));
        return new SnowpackPayload(values);
    }

    /**
     * Cold enough to snow where a player is, but no snow lies: a snowfall comes within a day (at a random time), at
     * most one a day. Weather is global, so it rains where it's warm.
     */
    private static void forceSnowfall(ServerLevel level, Data data, boolean raining) {
        if (!SnowConfig.FORCE_SNOWFALL.get()) return;
        long time = level.getGameTime();
        if (raining) {
            data.pending = 0;
            return;
        }
        boolean wanted = false;
        for (ServerPlayer player : level.players()) {
            BlockPos pos = player.blockPosition();
            Holder<Biome> biome = level.getBiome(pos);
            Float since = biome.unwrapKey().map(warmest::get).orElse(null);
            if (since == null || !biome.value().hasPrecipitation() || !SnowRules.cold(biome, pos)) continue;
            // Nothing lies here: stayed above the snow line since the last snowfall (height counted).
            if (since + Climate.temperature(biome.value(), pos) - Climate.surfaceTemperature(biome.value()) >= Climate.SNOW_LINE) wanted = true;
        }
        if (!wanted) {
            data.pending = 0;
            return;
        }
        if (data.pending == 0) data.pending = Math.max(time + level.random.nextInt(DAY), data.nextAllowed);
        if (time < data.pending) return;
        level.setWeatherParameters(0, SnowConfig.SNOWFALL_TICKS.get(), true, false);
        data.pending = 0;
        data.nextAllowed = time + DAY;
    }

    private static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Data::new, Data::load, null), "equinox_snowpack");
    }

    private static final class Data extends SavedData {
        final Map<ResourceKey<Biome>, Float> warmest = new HashMap<>();
        /** Game time it last rained or snowed per biome (the snow cover's catch-up adds snow only after one). */
        final Map<ResourceKey<Biome>, Long> lastFall = new java.util.concurrent.ConcurrentHashMap<>();
        long pending, nextAllowed;

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            CompoundTag values = tag.getCompound("warmest");
            for (String id : values.getAllKeys()) {
                ResourceLocation location = ResourceLocation.tryParse(id);
                if (location != null) data.warmest.put(ResourceKey.create(Registries.BIOME, location), values.getFloat(id));
            }
            CompoundTag falls = tag.getCompound("last_fall");
            for (String id : falls.getAllKeys()) {
                ResourceLocation location = ResourceLocation.tryParse(id);
                if (location != null) data.lastFall.put(ResourceKey.create(Registries.BIOME, location), falls.getLong(id));
            }
            data.pending = tag.getLong("pending");
            data.nextAllowed = tag.getLong("next_allowed");
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            CompoundTag values = new CompoundTag();
            warmest.forEach((key, value) -> values.putFloat(key.location().toString(), value));
            tag.put("warmest", values);
            CompoundTag falls = new CompoundTag();
            lastFall.forEach((key, value) -> falls.putLong(key.location().toString(), value));
            tag.put("last_fall", falls);
            tag.putLong("pending", pending);
            tag.putLong("next_allowed", nextAllowed);
            return tag;
        }
    }

    private Snowpack() {}
}
