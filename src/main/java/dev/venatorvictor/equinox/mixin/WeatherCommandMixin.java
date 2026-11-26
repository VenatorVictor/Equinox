package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.weather.ServerWeather;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.WeatherCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Weather per region: /weather rain and /weather thunder start a storm over where the command runs (the player):
 * clouds gather there first, then it rains or thunders for the time given (vanilla's random length without one), and
 * the storm drifts on with the wind. /weather clear stays world-wide (ServerLevelWeatherMixin).
 */
@Mixin(WeatherCommand.class)
public abstract class WeatherCommandMixin {
    @Inject(method = "setRain", at = @At("HEAD"), cancellable = true)
    private static void equinox$stormHere(CommandSourceStack source, int time, CallbackInfoReturnable<Integer> cir) {
        start(source, time, false, cir);
    }

    @Inject(method = "setThunder", at = @At("HEAD"), cancellable = true)
    private static void equinox$thunderstormHere(CommandSourceStack source, int time, CallbackInfoReturnable<Integer> cir) {
        start(source, time, true, cir);
    }

    private static void start(CommandSourceStack source, int time, boolean thunder, CallbackInfoReturnable<Integer> cir) {
        ServerLevel level = source.getServer().overworld();
        if (!Weather.on(level)) return;
        int duration = time != -1 ? time : (thunder ? ServerLevel.THUNDER_DURATION : ServerLevel.RAIN_DURATION).sample(level.getRandom());
        Vec3 pos = source.getPosition();
        // a newer command wins: an earlier /weather clear (or a world-wide rain from an older version) ends
        ServerWeather.command(level, 0, 0, false, false);
        ServerWeather.forceStorm(level, pos.x, pos.z, duration, thunder);
        int minutes = Math.round(Weather.FORCED_BUILD / 1200F * 10) ;
        source.sendSuccess(() -> Component.translatable(thunder ? "commands.equinox.weather.thunder_here" : "commands.equinox.weather.rain_here",
                String.format(java.util.Locale.ROOT, "%.1f", minutes / 10F), Math.round(Weather.FORCED_RADIUS)), true);
        cir.setReturnValue(duration);
    }
}
