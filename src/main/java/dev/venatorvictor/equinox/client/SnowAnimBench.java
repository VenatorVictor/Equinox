package dev.venatorvictor.equinox.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

/**
 * TEMPORARY: measures the snow animation in game.
 * /snowanim watch [seconds]  - measures real snowfall/thaw around you (default 60 s).
 * /snowanim stress <per_second> [seconds] - fakes that many snow changes per second (half thaw and sink, half come back and rise; client only, snow layers
 *   and snowy plants within animation_distance), worst-case test without waiting for weather (default 10 s); the snow is put back at the end.
 * Reports: drawing time per frame (average / worst), moving copies at once, snow put in after rising.
 */
public final class SnowAnimBench {
    private static long endAt = -1, startNanos;
    private static long frames, frameNanos, lastFrame;
    private static int stressPerSecond;
    private static double stressCarry;
    private static List<BlockPos> candidates = List.of();
    private static final java.util.Map<BlockPos, BlockState> ORIGINAL = new java.util.HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    public static void init() {
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> event.getDispatcher().register(
                Commands.literal("snowanim")
                        .then(Commands.literal("watch")
                                .executes(c -> start(60, 0))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                        .executes(c -> start(IntegerArgumentType.getInteger(c, "seconds"), 0))))
                        .then(Commands.literal("stress")
                                .then(Commands.argument("per_second", IntegerArgumentType.integer(1, 2000))
                                        .executes(c -> start(10, IntegerArgumentType.getInteger(c, "per_second")))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                                                .executes(c -> start(IntegerArgumentType.getInteger(c, "seconds"),
                                                        IntegerArgumentType.getInteger(c, "per_second"))))))));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, event -> {
            if (endAt < 0) return;
            long now = System.nanoTime();
            if (lastFrame != 0) {
                frames++;
                frameNanos += now - lastFrame;
            }
            lastFrame = now;
        });
    }

    private static int start(int seconds, int perSecond) {
        SnowAnimation.benchDrawNanos = SnowAnimation.benchMaxDrawNanos = SnowAnimation.benchActive = SnowAnimation.benchMaxActive = 0;
        SnowAnimation.benchStarted = SnowAnimation.benchApplied = 0;
        frames = frameNanos = lastFrame = 0;
        stressPerSecond = perSecond;
        stressCarry = 0;
        candidates = perSecond > 0 ? findSnow() : List.of();
        if (perSecond > 0 && candidates.isEmpty()) {
            say("No snow layers or snowy plants within animation_distance.");
            return 0;
        }
        startNanos = System.nanoTime();
        endAt = startNanos + seconds * 1_000_000_000L;
        say(perSecond > 0 ? "Snow anim stress: " + perSecond + "/s for " + seconds + " s on " + candidates.size() + " snow blocks"
                : "Snow anim watch: " + seconds + " s");
        return 1;
    }

    private static List<BlockPos> findSnow() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return List.of();
        int r = ClientConfig.SNOW_ANIMATION_DISTANCE.get();
        BlockPos center = mc.player.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        ORIGINAL.clear();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                for (int y = -16; y <= 16; y++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.SNOW) || dev.venatorvictor.equinox.snow.SnowyPlants.stateLayers(state) > 0) {
                        found.add(pos.immutable());
                        ORIGINAL.put(pos.immutable(), state);
                    }
                }
            }
        }
        return found;
    }

    private static void tick() {
        if (endAt < 0) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            endAt = -1;
            return;
        }
        if (stressPerSecond > 0) {
            stressCarry += stressPerSecond / 20.0;
            while (stressCarry >= 1) {
                stressCarry--;
                // Fake the server: a snowy block thaws (sinks), a thawed one gets its snow back (rises).
                BlockPos pos = candidates.get(RANDOM.nextInt(candidates.size()));
                BlockState state = level.getBlockState(pos);
                BlockState snowy = ORIGINAL.get(pos);
                if (snowy == null) continue;
                boolean hasSnow = state.is(Blocks.SNOW) || dev.venatorvictor.equinox.snow.SnowyPlants.stateLayers(state) > 0;
                BlockState bare = snowy.is(Blocks.SNOW) ? Blocks.AIR.defaultBlockState() : dev.venatorvictor.equinox.snow.SnowyPlants.withLayers(snowy, 0);
                level.setServerVerifiedBlockState(pos, hasSnow ? bare : snowy, 19);
            }
        }
        if (System.nanoTime() < endAt) return;
        endAt = -1;
        if (stressPerSecond > 0) ORIGINAL.forEach((pos, state) -> level.setServerVerifiedBlockState(pos, state, 19));
        report();
    }

    private static void report() {
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        long f = Math.max(1, frames);
        double drawMs = SnowAnimation.benchDrawNanos / 1e6 / f;
        double frameMs = frameNanos / 1e6 / f;
        double started = SnowAnimation.benchStarted / seconds;
        // Snow arriving: the redraw happens when the held-back block goes in (the same one redraw as without animation).
        double applied = SnowAnimation.benchApplied / seconds;
        say(String.format("Snow anim: %.1f s, %d frames (%.1f fps, %.2f ms/frame)", seconds, frames, frames / seconds, frameMs));
        say(String.format("Drawing copies: %.3f ms/frame average (%.1f%% of the frame), %.3f ms worst",
                drawMs, frameMs > 0 ? 100 * drawMs / frameMs : 0, SnowAnimation.benchMaxDrawNanos / 1e6));
        say(String.format("Moving at once: %.1f average, %d most; started %.1f/s", SnowAnimation.benchActive / (double) f,
                SnowAnimation.benchMaxActive, started));
        say(String.format("Held-back snow put in: %.1f/s (no extra redraws: each is the redraw the change needs anyway)", applied));
    }

    private static void say(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(text), false);
        dev.venatorvictor.equinox.Equinox.LOGGER.info(text);
    }

    private SnowAnimBench() {}
}
