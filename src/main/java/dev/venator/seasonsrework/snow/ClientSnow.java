package dev.venator.seasonsrework.snow;

import dev.venator.seasonsrework.client.SeasonsReworkClient;

/** Client copy of the server's first-snow state (0 = waiting, 1 = snowed). Read from Distant Horizons' worker threads. */
public final class ClientSnow {
    /** Full coverage until the server says otherwise (or forever, if the server doesn't have the mod). */
    public static volatile float coverage = 1F;

    static void receive(float value) {
        if (value == coverage) return;
        coverage = value;
        SeasonsReworkClient.snowChanged();
    }

    public static void reset() {
        coverage = 1F;
    }

    private ClientSnow() {}
}
