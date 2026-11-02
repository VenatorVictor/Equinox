package dev.venatorvictor.equinox.api;

import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;

/**
 * Posted on the NeoForge event bus when a dimension enters a new part of a season (early, mid, late), on the server,
 * and on the client for the level the player is in. Compare the seasons to see whether the season itself changed.
 */
public class SeasonChangedEvent extends Event {
    private final Level level;
    private final SeasonState previous, current;

    public SeasonChangedEvent(Level level, SeasonState previous, SeasonState current) {
        this.level = level;
        this.previous = previous;
        this.current = current;
    }

    public Level getLevel() {
        return level;
    }

    public SeasonState getPrevious() {
        return previous;
    }

    public SeasonState getCurrent() {
        return current;
    }

    public boolean isNewSeason() {
        return previous.season() != current.season();
    }
}
