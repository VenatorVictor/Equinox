package dev.venatorvictor.equinox.client.colors;

import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.api.Seasons;
import dev.venatorvictor.equinox.client.ClientConfig;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Where the client is in the color tables: 64 weeks a year (16 a season, 0 = the spring equinox), and how far
 * through the week, rounded down to the configured updates. On the calendar. Read from chunk-meshing threads, written
 * on the client tick.
 */
public final class ColorClock {
    public static volatile boolean active = false;
    public static volatile int step = 0;
    /** 0..1 through the current step, rounded down to the configured updates. */
    public static volatile float progress = 0F;
    /** The week of the year for seasonal textures (0 .. 63, 16 a season, whole days; SeasonState.week). */
    public static volatile int week = 0;

    private static int lastUpdate = -1;

    /** Returns true when the colors changed since the last tick (new step or new blend update). */
    public static boolean tick(ClientLevel level) {
        SeasonState calendar = Seasons.get(level);
        if (calendar == null) {
            boolean changed = active;
            reset();
            return changed;
        }
        int updates = ClientConfig.UPDATES_PER_STEP.get();
        int update = quantize(calendar.yearProgress() * ColorTables.STEPS, updates);
        step = update / updates;
        week = calendar.week(); // same as step (whole-day weeks); kept for the texture code
        progress = (float) (update % updates) / updates;
        active = true;

        boolean changed = lastUpdate != -1 && update != lastUpdate;
        lastUpdate = update;
        return changed;
    }

    /** step * updates + update in step. */
    private static int quantize(double position, int updates) {
        int s = Math.min((int) position, ColorTables.STEPS - 1);
        int u = Math.min((int) ((position - s) * updates), updates - 1);
        return s * updates + u;
    }

    public static void reset() {
        active = false;
        lastUpdate = -1;
    }

    public static int previousStep() {
        return previous(step);
    }

    public static int previous(int s) {
        return (s + ColorTables.STEPS - 1) % ColorTables.STEPS;
    }

    private ColorClock() {}
}
