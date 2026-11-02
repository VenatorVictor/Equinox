package dev.venatorvictor.equinox.season;

import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.climate.Climate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Server side. The season is the day count (Seasons.get), so there is nothing to save or send: clients already get
 * the day time. This only posts the change events, and moves the day time for the season command.
 */
public final class SeasonClock {
    private static final Map<ResourceKey<Level>, SeasonWatcher> WATCHERS = new HashMap<>();
    /** Set while /season set moves the clock: ServerLevelTimeMixin lets it set any time, even day 0. */
    public static boolean settingSeason;

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WATCHERS.computeIfAbsent(level.dimension(), key -> new SeasonWatcher()).tick(level);
            Climate.update(level);
        }
    }

    /** Sets the climate before the spawn chunks generate. */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) Climate.update(level);
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        WATCHERS.clear();
    }

    /** Sets the day time of every dimension, as /time set does (they share the overworld's clock). */
    static void setDayTime(MinecraftServer server, long dayTime) {
        settingSeason = true;
        try {
            for (ServerLevel level : server.getAllLevels()) {
                level.setDayTime(Math.max(0, dayTime));
                Climate.update(level); // now, not next tick, so commands right after see the new climate
            }
        } finally {
            settingSeason = false;
        }
    }

    /** The day time of the start of a season part this year, keeping the time of day. */
    static long startOf(SeasonState state, int seasonIndex, int part) {
        long yearStart = state.year() * state.seasonTicks() * 4;
        long day = (long) seasonIndex * state.daysPerSeason() + (long) part * state.daysPerSeason() / SeasonState.PARTS;
        return yearStart + day * SeasonState.DAY + Math.floorMod(state.dayTime(), SeasonState.DAY);
    }

    private SeasonClock() {}
}
