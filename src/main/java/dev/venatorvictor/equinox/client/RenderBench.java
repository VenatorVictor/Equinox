package dev.venatorvictor.equinox.client;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * TEMPORARY (comparing the snow storages): client command /snowbench redraws every chunk section 5 times and tells
 * how long each redraw took until all were built again. Stand still, same place and view, for both storages.
 */
public final class RenderBench {
    private static final int RUNS = 5;
    private static int run = -1;
    private static long start;
    private static boolean busy;
    private static final long[] TIMES = new long[RUNS];
    /** Last /snowbench: ms to redraw one section (best run / sections), -1 = not run. Used by SnowAnimBench. */
    static double lastMsPerSection = -1;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> event.getDispatcher().register(
                Commands.literal("snowbench").executes(context -> {
                    run = 0;
                    begin();
                    return 1;
                })));
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, event -> frame());
    }

    private static void begin() {
        Minecraft.getInstance().levelRenderer.allChanged();
        start = System.nanoTime();
        busy = false;
    }

    private static void frame() {
        if (run < 0) return;
        var renderer = Minecraft.getInstance().levelRenderer;
        boolean empty = renderer.hasRenderedAllSections();
        if (!empty) {
            busy = true;
            return;
        }
        // Done once the queue filled and emptied again (or stayed empty for 2 seconds).
        if (!busy && System.nanoTime() - start < 2_000_000_000L) return;
        TIMES[run++] = System.nanoTime() - start;
        if (run < RUNS) {
            begin();
            return;
        }
        run = -1;
        long sum = 0, best = Long.MAX_VALUE;
        StringBuilder all = new StringBuilder();
        for (long t : TIMES) {
            sum += t;
            best = Math.min(best, t);
            all.append(t / 1_000_000).append(' ');
        }
        String text = String.format("Snow bench: redraw all sections %d ms average, %d ms best (%s ms), %d sections", sum / RUNS / 1_000_000, best / 1_000_000,
                all.toString().trim(), renderer.countRenderedSections());
        lastMsPerSection = (double) best / 1_000_000 / Math.max(1, renderer.countRenderedSections());
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(text), false);
    }

    private RenderBench() {}
}
