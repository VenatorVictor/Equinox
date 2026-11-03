package dev.venatorvictor.equinox.daylength;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.api.SeasonState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * How long a Minecraft day lasts in real time (config day_minutes), through NeoForge's own day speed (the one
 * /neoforge day length sets; saved with the world and sent to players). Only the speed changes: the clock still
 * counts 24000 per day, so the seasons, the seasonal day length and everything reading the day time stay as they are.
 */
public final class DaySpeed {
    /** Vanilla: 20 real minutes per day. */
    private static final int VANILLA_MINUTES = 20;

    public static void onServerStarted(ServerStartedEvent event) {
        apply(event.getServer());
    }

    /** After a config reload, on the running server (if any). */
    public static void reapply() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.execute(() -> apply(server));
    }

    private static void apply(MinecraftServer server) {
        int minutes = Config.dayMinutes();
        if (minutes <= 0) return; // left to the world (or /neoforge day)
        ServerLevel overworld = server.overworld();
        // -1 = NeoForge's "not set": plain vanilla speed, no fractions.
        float perTick = minutes == VANILLA_MINUTES ? -1f : SeasonState.DAY / (minutes * 1200f);
        if (overworld.getDayTimePerTick() != perTick) overworld.setDayTimePerTick(perTick);
    }

    private DaySpeed() {}
}
