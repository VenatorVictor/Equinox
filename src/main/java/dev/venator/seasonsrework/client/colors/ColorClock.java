package dev.venator.seasonsrework.client.colors;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.api.SeasonState;
import dev.venator.seasonsrework.api.Seasons;
import dev.venator.seasonsrework.client.ClientConfig;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Where the client is in the color tables: 24 steps a year (2 per season part), and how far through the step,
 * rounded down to the configured updates. Read from chunk-meshing threads, written on the client tick.
 */
public final class ColorClock {
    public static volatile boolean active = false;
    public static volatile int step = 0;
    /** 0..1 through the current step, rounded down to the configured updates. */
    public static volatile float progress = 0F;

    private static int lastUpdate = -1;

    /** Returns true when the colors changed since the last tick (new step or new blend update). */
    public static boolean tick(ClientLevel level) {
        SeasonState state = Seasons.get(level);
        if (state != null) state = state.lagged(Config.temperatureLagDays()); // colors follow the temperature
        if (state == null) {
            boolean changed = active;
            reset();
            return changed;
        }
        int updates = ClientConfig.UPDATES_PER_STEP.get();
        double position = state.yearProgress() * ColorTables.STEPS;
        int newStep = Math.min((int) position, ColorTables.STEPS - 1);
        int updateInStep = Math.min((int) ((position - newStep) * updates), updates - 1);

        step = newStep;
        progress = (float) updateInStep / updates;
        active = true;

        int update = newStep * updates + updateInStep;
        boolean changed = lastUpdate != -1 && update != lastUpdate;
        lastUpdate = update;
        return changed;
    }

    public static void reset() {
        active = false;
        lastUpdate = -1;
    }

    public static int previousStep() {
        return (step + ColorTables.STEPS - 1) % ColorTables.STEPS;
    }

    private ColorClock() {}
}
