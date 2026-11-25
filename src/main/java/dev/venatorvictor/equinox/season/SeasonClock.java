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
            if (level.dimension() == Level.OVERWORLD) followRealClock(level);
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

    /**
     * Real time: once a second, sets the day time from the real clock and date (RealTime) if it has drifted. Between
     * times the day runs at real speed (DaySpeed: a day of 24 real hours), so the sun moves smoothly.
     */
    private static void followRealClock(ServerLevel overworld) {
        if (!dev.venatorvictor.equinox.realtime.RealTime.on() || overworld.getGameTime() % 20 != 0) return;
        long target = dev.venatorvictor.equinox.realtime.RealTime.dayTime(dev.venatorvictor.equinox.realtime.RealTime.now());
        // the day runs at real speed between; only a real drift (5 ticks = 18 s) or a jump is set
        if (Math.abs(overworld.getDayTime() - target) >= 5) setDayTime(overworld.getServer(), target);
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

    /**
     * The world's day time on the first day of a season's week (0 .. weeksPerSeason - 1, days_per_week each) this year,
     * keeping the time of day (like sleeping): the internal day (equal seasons) turned back into Equinox's day.
     */
    static long startOf(SeasonState state, int seasonIndex, int week, long worldDayTime) {
        long yearStart = state.year() * state.daysPerSeason() * 4L;
        long day = (long) seasonIndex * state.daysPerSeason() + (long) Math.min(week, state.weeksPerSeason() - 1) * state.daysPerWeek();
        long target = dev.venatorvictor.equinox.api.GameYear.worldDay(yearStart + day, state.daysPerSeason());
        return dev.venatorvictor.equinox.api.GameYear.atDay(target, worldDayTime);
    }

    private SeasonClock() {}
}
