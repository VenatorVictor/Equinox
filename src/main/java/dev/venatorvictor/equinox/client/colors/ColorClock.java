package dev.venatorvictor.equinox.client.colors;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.api.Seasons;
import dev.venatorvictor.equinox.client.ClientConfig;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Where the client is in the color tables: 24 steps a year (2 per season part), and how far through the step,
 * rounded down to the configured updates. Read from chunk-meshing threads, written on the client tick.
 * <p>
 * Two clocks: grass and most colors follow the temperature (lagged behind the calendar); deciduous trees follow
 * {@link #treeStep}: the lagged clock while the leaves come out (warmth), the calendar from mid summer on, because
 * autumn colors and leaf fall follow the day length. The hand-offs sit where the tree tables are flat
 * (summer green, winter).
 */
public final class ColorClock {
    /** Tree clock: calendar from this step (lesser heat, early July) to the end of the year. */
    public static final int CALENDAR_FROM = 10;

    public static volatile boolean active = false;
    public static volatile int step = 0;
    /** 0..1 through the current step, rounded down to the configured updates. */
    public static volatile float progress = 0F;
    public static volatile int treeStep = 0;
    public static volatile float treeProgress = 0F;

    private static int lastUpdate = -1;
    private static int lastTreeUpdate = -1;

    /** Returns true when the colors changed since the last tick (new step or new blend update). */
    public static boolean tick(ClientLevel level) {
        SeasonState calendar = Seasons.get(level);
        if (calendar == null) {
            boolean changed = active;
            reset();
            return changed;
        }
        SeasonState lagged = calendar.lagged(Config.temperatureLag()); // colors follow the temperature
        int updates = ClientConfig.UPDATES_PER_STEP.get();
        double position = lagged.yearProgress() * ColorTables.STEPS;
        double calendarPosition = calendar.yearProgress() * ColorTables.STEPS;
        double treePosition = calendarPosition >= CALENDAR_FROM ? calendarPosition : position;

        int update = quantize(position, updates);
        int treeUpdate = quantize(treePosition, updates);
        step = update / updates;
        progress = (float) (update % updates) / updates;
        treeStep = treeUpdate / updates;
        treeProgress = (float) (treeUpdate % updates) / updates;
        active = true;

        boolean changed = lastUpdate != -1 && (update != lastUpdate || treeUpdate != lastTreeUpdate);
        lastUpdate = update;
        lastTreeUpdate = treeUpdate;
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
        lastTreeUpdate = -1;
    }

    public static int previousStep() {
        return previous(step);
    }

    public static int previous(int s) {
        return (s + ColorTables.STEPS - 1) % ColorTables.STEPS;
    }

    private ColorClock() {}
}
