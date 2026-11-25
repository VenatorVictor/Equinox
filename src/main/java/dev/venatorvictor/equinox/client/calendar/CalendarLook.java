package dev.venatorvictor.equinox.client.calendar;

import dev.venatorvictor.equinox.api.Season;
import net.minecraft.client.resources.language.I18n;

/** The calendar's colours (the page previews) and its words. */
final class CalendarLook {
    // per season: the bar, its light tint (today's cell, the moon page's current phase), its dark edge and text shadow
    static final int[] BAND = {0x68A042, 0xD6A028, 0xBE6026, 0x6892BE};
    static final int[] LIGHT = {0xD6E8BE, 0xF6E4A8, 0xF0CEB0, 0xD2E2F2};
    static final int[] SHADE = {0x3C6424, 0x8C6012, 0x783614, 0x3A5882};
    static final int GRID = 0xDED2B6, INK = 0x3A3028, FADED = 0x968A74, RED = 0xB82424;
    // the hanging calendar's day dots
    static final int DOT_PAST = 0xCC483C, DOT_LATER = 0xC0B6A2;

    static String season(Season s) {
        return I18n.get("equinox.calendar.season." + s.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** 0 = April (the year starts at the spring equinox). */
    static String month(int month) {
        return I18n.get("equinox.month." + Math.floorMod(month, 12));
    }

    /** The month as it sits inside a sentence ("1 de abril" in Portuguese, "April 1" in English). */
    static String monthInText(int month) {
        String m = month(month);
        return "true".equals(I18n.get("equinox.calendar.lower_months")) ? m.toLowerCase(java.util.Locale.ROOT) : m;
    }

    static String moon(int vanillaPhase) {
        return I18n.get("equinox.moon." + vanillaPhase);
    }

    private CalendarLook() {}
}
