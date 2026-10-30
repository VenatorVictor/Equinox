package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.api.SeasonState;
import dev.venator.seasonsrework.season.SeasonClock;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The season is the day count, and vanilla /time set (day, noon, 1000...) or a mod setting a time of day gave an
 * absolute time, which jumped back to day 0 of the first year: early spring. A time under a day now only moves the
 * clock within the current day, keeping the date and season. Times of a day or more stay absolute, and /season set
 * (SeasonClock) sets any time it needs.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTimeMixin {
    @ModifyVariable(method = "setDayTime", at = @At("HEAD"), argsOnly = true)
    private long seasonsrework$keepDate(long time) {
        if (SeasonClock.settingSeason || time < 0 || time >= SeasonState.DAY) return time;
        return Math.floorDiv(((ServerLevel) (Object) this).getDayTime(), SeasonState.DAY) * SeasonState.DAY + time;
    }
}
