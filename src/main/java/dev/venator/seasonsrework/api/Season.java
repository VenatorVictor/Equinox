package dev.venator.seasonsrework.api;

import org.jetbrains.annotations.Nullable;

public enum Season {
    SPRING, SUMMER, AUTUMN, WINTER;

    public static final Season[] VALUES = values();
    public static final int COUNT = VALUES.length;

    public String id() {
        return name().toLowerCase();
    }

    public static @Nullable Season byId(String id) {
        for (Season season : VALUES) if (season.id().equals(id)) return season;
        return null;
    }
}
