package dev.venator.seasonsrework.snow;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.climate.Climate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * First snow: in the snowy season (temperature change below the configured value), the snow cover waits for
 * the first snowfall, then covers the whole world wherever it's cold. Optionally that first snowfall is
 * started when the snowy season begins. Back to waiting once the season warms up. Overworld only.
 */
public final class FirstSnow {
    /** 0 = waiting for the first snowfall, 1 = it has snowed this cold season. Server side, sent to clients. */
    public static volatile float coverage = 1F;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) tick(level);
        });
        NeoForge.EVENT_BUS.addListener(LevelEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD)
                coverage = SnowConfig.FIRST_SNOW.get() ? data(level).coverage : 1F;
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer player) PacketDistributor.sendToPlayer(player, new SnowCoveragePayload(coverage));
        });
    }

    private static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0) return;
        Data data = data(level);
        if (!Config.hasSeasons(level.dimension())) return;
        boolean snowySeason = Climate.offset() < SnowConfig.SNOWY_SEASON.get();
        // The snowy season just began: start the first snowfall (rain falls as snow where it's cold).
        if (snowySeason && !data.snowySeason && SnowConfig.FORCE_SNOWFALL.get() && data.coverage < 1F && !level.isRaining())
            level.setWeatherParameters(0, SnowConfig.SNOWFALL_TICKS.get(), true, false);
        float value = !SnowConfig.FIRST_SNOW.get() ? 1F
                : !snowySeason ? 0F
                : data.coverage >= 1F || level.isRaining() ? 1F : 0F;
        if (value != data.coverage || snowySeason != data.snowySeason) {
            data.coverage = value;
            data.snowySeason = snowySeason;
            data.setDirty();
        }
        if (value == coverage) return;
        coverage = value;
        PacketDistributor.sendToAllPlayers(new SnowCoveragePayload(value));
    }

    private static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Data::new, Data::load, null), "seasonsrework_first_snow");
    }

    private static final class Data extends SavedData {
        float coverage;
        boolean snowySeason;

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            data.coverage = tag.getFloat("coverage") >= 1F ? 1F : 0F;
            data.snowySeason = tag.getBoolean("snowy_season");
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putFloat("coverage", coverage);
            tag.putBoolean("snowy_season", snowySeason);
            return tag;
        }
    }

    private FirstSnow() {}
}
