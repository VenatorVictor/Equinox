package dev.venator.seasonsrework.season;

import dev.venator.seasonsrework.api.SeasonChangedEvent;
import dev.venator.seasonsrework.api.SeasonState;
import dev.venator.seasonsrework.api.Seasons;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/** Watches one level's season each tick and posts SeasonChangedEvent when the part (early/mid/late) changes. */
public final class SeasonWatcher {
    private @Nullable SeasonState last;

    public void tick(Level level) {
        SeasonState now = Seasons.get(level);
        if (now == null) {
            last = null;
            return;
        }
        SeasonState before = last;
        if (before != null && now.partsSinceStart() != before.partsSinceStart()) {
            // The client sets its clock from the server's time packets, sometimes a few ticks back: don't count
            // that as going back a part (a real jump back is more than that).
            long back = before.dayTime() - now.dayTime();
            if (back > 0 && back < SeasonState.DAY / 2) return;
            last = now;
            NeoForge.EVENT_BUS.post(new SeasonChangedEvent(level, before, now));
            return;
        }
        last = now;
    }

    public void reset() {
        last = null;
    }
}
