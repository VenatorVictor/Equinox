package dev.venatorvictor.equinox.client.calendar;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.api.Season;
import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.daylength.DayLength;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.util.Locale;

/**
 * The calendar's pages (preview calendar_page_v13).
 * - Month page: season bar, the days by week, today framed in red, the chosen day shaded, past days crossed out, the
 *   new and full moons on their first day, an equinox or solstice band. Footer about the chosen day (click a day;
 *   today at first): its moon (click: the moon page), the next equinox or solstice (click: the equinox/solstice page),
 *   its day length; and the days to the next season. Arrows: back and on through the months.
 * - Moon page: all 8 phases with their days this month.
 * - Equinox/solstice page: the year's 4, with date, day length and how far from today.
 */
public final class CalendarScreen extends Screen {
    private static final ResourceLocation LIST_BG = tex("moon"), MOONS = tex("moons"), HAND_X = tex("hand_x");
    private static final int[] BG_ROWS = {2, 4, 6, 8};
    private static final int W = 226, L = 14, RM = 20, TOP = 26, CONTENT = 192, CH = 18, RH = 14, MARK_RH = 28;
    private enum Page { MONTH, MOON, MARKS }

    private int offset;              // months from the current one
    private Page page = Page.MONTH;
    private long selected = -1;      // the chosen day (days since the world began); -1: today, or the month's first day
    private int left, top, height_;
    // clickable footer lines: the moon, the equinox/solstice
    private final int[] moonBox = new int[4], markBox = new int[4];

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath("equinox", "textures/gui/calendar/" + name + ".png");
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.setScreen(new CalendarScreen());
    }

    private CalendarScreen() {
        super(Component.translatable("block.equinox.calendar"));
    }

    private static SeasonState now() {
        Minecraft mc = Minecraft.getInstance();
        long time = mc.level == null ? 0 : mc.level.getDayTime();
        return new SeasonState(time, Config.daysPerSeason(), Config.daysPerWeek());
    }

    private static long dayOf(SeasonState s) {
        return Math.floorDiv(s.dayTime(), SeasonState.DAY);
    }

    /** Midday of a day. */
    private static SeasonState at(long day, SeasonState like) {
        return new SeasonState(day * SeasonState.DAY + 6000, like.daysPerSeason(), like.daysPerWeek());
    }

    /** The first day of the month on show. */
    private long firstDay(SeasonState now) {
        return dayOf(now) - now.dayOfMonth() + (long) offset * now.monthDays();
    }

    private long chosen(SeasonState now) {
        if (selected >= 0) return selected;
        return offset == 0 ? dayOf(now) : firstDay(now);
    }

    private static int cols(SeasonState s) {
        return Math.max(1, Math.min(s.daysPerWeek(), s.monthDays()));
    }

    private static int rows(SeasonState s) {
        return (s.monthDays() + cols(s) - 1) / cols(s);
    }

    /** The background made for at least this many rows (or the tallest). */
    private static int bgRows(int rows) {
        for (int r : BG_ROWS) if (r >= rows) return r;
        return BG_ROWS[BG_ROWS.length - 1];
    }

    private static int monthHeight(int bgRows) {
        return TOP + 20 + bgRows * CH + 4 + 4 * 12 + 6 + 24 + 20;
    }

    @Override
    protected void init() {
        SeasonState now = now();
        int rows = bgRows(rows(now));
        height_ = page == Page.MONTH ? monthHeight(rows) : TOP + 22 + 8 * RH + 34;
        left = (width - W) / 2;
        top = (height - height_) / 2;
        if (page != Page.MONTH) {
            addRenderableWidget(new PageButton(left + L + 2, top + TOP + 22 + 8 * RH + 2, false, b -> {
                page = Page.MONTH;
                rebuildWidgets();
            }, true));
            return;
        }
        int ay = top + TOP + 20 + rows * CH + 4 + 6 + 48 + 8;
        if (firstDay(now) - now.monthDays() >= 0)                 // nothing before the world's first month
            addRenderableWidget(new PageButton(left + L + 2, ay, false, b -> turn(-1), true));
        addRenderableWidget(new PageButton(left + W - RM - 23 - 14, ay, true, b -> turn(1), true));
    }

    private void turn(int months) {
        offset += months;
        selected = -1;
        rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        ResourceLocation bg = page == Page.MONTH ? tex("month_" + bgRows(rows(now()))) : LIST_BG;
        g.blit(bg, left, top, 0, 0, W, height_, W, height_);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        SeasonState now = now();
        switch (page) {
            case MONTH -> renderMonthPage(g, now, mouseX, mouseY);
            case MOON -> renderMoonPage(g, now);
            case MARKS -> renderMarksPage(g, now);
        }
    }

    // ---------- drawing helpers

    private static int argb(int rgb) {
        return 0xFF000000 | rgb;
    }

    private void text(GuiGraphics g, String s, int x, int y, int rgb) {
        g.drawString(font, s, x, y, argb(rgb), false);
    }

    private void textRight(GuiGraphics g, String s, int right, int y, int rgb) {
        text(g, s, right - font.width(s), y, rgb);
    }

    /** A footer line that opens a page: underlined while the mouse is on it; its box is kept for clicks. */
    private void link(GuiGraphics g, String s, int x, int y, int rgb, int[] box, int boxX0, int mouseX, int mouseY) {
        box[0] = boxX0; box[1] = y - 2; box[2] = x + font.width(s); box[3] = y + 10;
        g.drawString(font, Component.literal(s).withStyle(Style.EMPTY.withUnderlined(inside(box, mouseX, mouseY))), x, y, argb(rgb), false);
    }

    private static boolean inside(int[] box, double x, double y) {
        return x >= box[0] && x < box[2] && y >= box[1] && y < box[3];
    }

    private void seasonBar(GuiGraphics g, Season season, String leftText, String rightText) {
        int i = season.ordinal(), x0 = left + L, x1 = left + W - RM, y0 = top + TOP;
        g.fill(x0, y0, x1, y0 + 13, argb(CalendarLook.BAND[i]));
        g.fill(x0, y0 + 13, x1, y0 + 14, argb(CalendarLook.SHADE[i]));
        for (int pass = 0; pass < 2; pass++) {
            int c = pass == 0 ? CalendarLook.SHADE[i] : 0xFFFFFF, d = 1 - pass;
            text(g, leftText, x0 + 4 + d, y0 + 3 + d, c);
            textRight(g, rightText, x1 - 4 + d, y0 + 3 + d, c);
        }
    }

    private void swatch(GuiGraphics g, int x, int y, int season) {
        g.fill(x, y - 1, x + 10, y + 9, argb(CalendarLook.SHADE[season]));
        g.fill(x + 1, y, x + 9, y + 8, argb(CalendarLook.BAND[season]));
    }

    private static void moon(GuiGraphics g, int x, int y, int vanillaPhase) {
        g.blit(MOONS, x, y, vanillaPhase * 10, 0, 10, 10, 80, 10);
    }

    private static String clock(double hours) {
        int minutes = (int) Math.round(hours * 60);
        return String.format(Locale.ROOT, "%d:%02d", Math.floorMod(minutes / 60, 24), Math.floorMod(minutes, 60));
    }

    private static String daylight(SeasonState day) {
        double hours = DayLength.daylightHours(day.dayTime(), day.daysPerSeason());
        return clock(12 - hours / 2) + " - " + clock(12 + hours / 2);
    }

    private static String yearText(SeasonState s) {
        return I18n.get("equinox.calendar.year", CalendarLook.season(s.season()), s.year() + 1);
    }

    /** The equinox or solstice starting this season: day 1 of the season's first month. */
    private static long markDay(long yearStart, int season, int monthDays) {
        return yearStart + (long) season * 3 * monthDays;
    }

    // ---------- the month page

    private void renderMonthPage(GuiGraphics g, SeasonState now, int mouseX, int mouseY) {
        long first = firstDay(now), today = dayOf(now), pick = chosen(now);
        SeasonState view = at(first, now), picked = at(pick, now);
        int month = view.month(), seasonIdx = view.season().ordinal();
        seasonBar(g, view.season(), CalendarLook.month(month), yearText(view));

        int cols = cols(view), rows = rows(view), monthDays = view.monthDays();
        int bgRows = bgRows(rows), ch = rows <= bgRows ? CH : Math.max(9, bgRows * CH / rows);
        int cw = CONTENT / cols, gx = left + L, gy = top + TOP + 20;
        int per = view.daysPerMoonPhase();
        int markSeason = month % 3 == 0 ? month / 3 : -1;         // the first month of a season starts it
        for (int i = 0; i < monthDays; i++) {
            int r = i / cols, c = i % cols;
            long day = first + i;
            cell(g, gx + c * cw, gy + r * ch, cw, ch, r == 0, c == 0, i + 1, day == today, day < today, day == pick,
                    seasonIdx, i == 0 ? markSeason : -1, per);
        }

        // footer: about the chosen day
        int fy = gy + bgRows * CH + 4, x1 = left + W - RM;
        g.fill(gx, fy, x1, fy + 1, argb(CalendarLook.GRID));
        int y = fy + 6;
        int phase = picked.moonPhase();
        moon(g, gx + 1, y - 1, phase);
        link(g, CalendarLook.moon(phase), gx + 14, y, CalendarLook.INK, moonBox, gx, mouseX, mouseY);
        y += 12;

        // the next equinox or solstice from the chosen day (that day's own, if it is one)
        long yearStart = first - (long) month * monthDays;
        int markS = -1;
        long markAt = 0;
        for (int k = 0; k < 8 && markS < 0; k++) {
            long d = markDay(yearStart, k % 4, monthDays) + (k / 4) * 12L * monthDays;
            if (d >= pick) { markS = k % 4; markAt = d; }
        }
        swatch(g, gx + 1, y, markS);
        String mark = I18n.get("equinox.calendar.mark." + Season.VALUES[markS].id());
        link(g, I18n.get("equinox.calendar.mark_on", mark, CalendarLook.monthInText(at(markAt, now).month()), 1),
                gx + 14, y, CalendarLook.SHADE[markS], markBox, gx, mouseX, mouseY);
        y += 12;

        text(g, I18n.get("equinox.calendar.day_length"), gx + 1, y, CalendarLook.FADED);
        textRight(g, daylight(picked), x1, y, CalendarLook.INK);
        y += 12;

        Season next = Season.VALUES[(now.season().ordinal() + 1) % Season.COUNT];
        int daysLeft = now.daysPerSeason() - now.dayOfSeason();
        text(g, I18n.get("equinox.calendar.next_label", CalendarLook.season(next)), gx + 1, y, CalendarLook.FADED);
        textRight(g, I18n.get(daysLeft == 1 ? "equinox.calendar.next_value_one" : "equinox.calendar.next_value", daysLeft), x1, y, CalendarLook.INK);
    }

    private void cell(GuiGraphics g, int x0, int y0, int cw, int ch, boolean firstRow, boolean firstCol, int day,
                      boolean today, boolean past, boolean picked, int season, int markSeason, int per) {
        int grid = argb(CalendarLook.GRID);
        g.fill(x0, y0 + ch - 1, x0 + cw, y0 + ch, grid);
        if (firstRow) g.fill(x0, y0, x0 + cw, y0 + 1, grid);
        g.fill(x0 + cw - 1, y0, x0 + cw, y0 + ch, grid);
        if (firstCol) g.fill(x0, y0, x0 + 1, y0 + ch, grid);
        if (today) g.fill(x0 + 1, y0 + 1, x0 + cw - 1, y0 + ch - 1, argb(CalendarLook.RED));
        if (picked) g.fill(x0 + 2, y0 + 2, x0 + cw - 2, y0 + ch - 2, argb(CalendarLook.LIGHT[season]));
        else if (today) g.fill(x0 + 2, y0 + 2, x0 + cw - 2, y0 + ch - 2, argb(0xFFFBF0));
        if (markSeason >= 0) {
            int inset = today ? 2 : 1;
            g.fill(x0 + inset, y0 + inset, x0 + cw - inset, y0 + 3, argb(CalendarLook.BAND[markSeason]));
            g.fill(x0 + inset, y0 + 3, x0 + cw - inset, y0 + 4, argb(CalendarLook.SHADE[markSeason]));
        }
        String n = String.valueOf(day);
        int nx = x0 + (cw - font.width(n)) / 2, ny = y0 + ch - 11;
        text(g, n, nx, ny, CalendarLook.INK);
        // the new and the full moon, on their first day
        if (day == 1) moon(g, x0 + cw - 12, y0 + ch - 12, 4);
        else if (day == 4 * per + 1) moon(g, x0 + cw - 12, y0 + ch - 12, 0);
        if (past) {
            int v = (day - 1) % 12;                                // each day its own cross
            g.blit(HAND_X, nx + font.width(n) / 2 - 10, ny + 3 - 8, v * 20, 0, 20, 16, 240, 16);
        }
    }

    // ---------- the moon page

    private void renderMoonPage(GuiGraphics g, SeasonState now) {
        long first = firstDay(now);
        SeasonState view = at(first, now);
        seasonBar(g, view.season(), I18n.get("equinox.calendar.moon_title", CalendarLook.monthInText(view.month())), yearText(view));
        int per = view.daysPerMoonPhase();
        long pick = chosen(now);
        int pickStep = pick >= first && pick < first + view.monthDays() ? Math.min((int) (pick - first) / per, 7) : -1;
        int gx = left + L, x1 = left + W - RM, gy = top + TOP + 22;
        for (int k = 0; k < 8; k++) {
            int phase = (k + 4) % 8, a = k * per + 1, b = k == 7 ? view.monthDays() : k * per + per, y = gy + k * RH;
            if (k == pickStep) g.fill(gx, y - 2, x1, y + RH - 3, argb(CalendarLook.LIGHT[view.season().ordinal()]));
            moon(g, gx + 2, y - 1, phase);
            text(g, CalendarLook.moon(phase), gx + 16, y, CalendarLook.INK);
            String d = b > a ? I18n.get("equinox.calendar.days", a, b) : I18n.get("equinox.calendar.day", a);
            textRight(g, d, x1 - 2, y, k == pickStep ? CalendarLook.RED : CalendarLook.FADED);
        }
    }

    // ---------- the equinox and solstice page

    private void renderMarksPage(GuiGraphics g, SeasonState now) {
        long first = firstDay(now), today = dayOf(now);
        SeasonState view = at(first, now);
        int monthDays = view.monthDays();
        long yearStart = first - (long) view.month() * monthDays;
        seasonBar(g, view.season(), I18n.get("equinox.calendar.marks_title"), yearText(view));
        int gx = left + L, x1 = left + W - RM, gy = top + TOP + 22;
        // the next one from today is shaded
        long next = Long.MAX_VALUE;
        for (int s = 0; s < 4; s++) {
            long d = markDay(yearStart, s, monthDays);
            if (d >= today && d < next) next = d;
        }
        for (int s = 0; s < 4; s++) {
            long d = markDay(yearStart, s, monthDays);
            SeasonState day = at(d, now);
            int y = gy + s * MARK_RH;
            if (d == next) g.fill(gx, y - 3, x1, y + MARK_RH - 5, argb(CalendarLook.LIGHT[s]));
            swatch(g, gx + 2, y, s);
            text(g, I18n.get("equinox.calendar.mark." + Season.VALUES[s].id()), gx + 16, y, CalendarLook.SHADE[s]);
            textRight(g, I18n.get("equinox.calendar.date", CalendarLook.monthInText(day.month()), 1), x1 - 2, y, CalendarLook.INK);
            text(g, I18n.get("equinox.calendar.day_length") + " " + daylight(day), gx + 16, y + 11, CalendarLook.FADED);
            long gap = d - today;
            String when = gap == 0 ? I18n.get("equinox.calendar.today")
                    : gap > 0 ? I18n.get(gap == 1 ? "equinox.calendar.in_day" : "equinox.calendar.in_days", gap)
                    : I18n.get(gap == -1 ? "equinox.calendar.day_ago" : "equinox.calendar.days_ago", -gap);
            textRight(g, when, x1 - 2, y + 11, d == next ? CalendarLook.RED : CalendarLook.FADED);
        }
    }

    // ---------- clicks

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (page == Page.MONTH && button == 0) {
            Page open = inside(moonBox, mouseX, mouseY) ? Page.MOON : inside(markBox, mouseX, mouseY) ? Page.MARKS : null;
            if (open != null) {
                page = open;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
                rebuildWidgets();
                return true;
            }
            long day = dayAt(mouseX, mouseY);
            if (day >= 0) {
                selected = day;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.4F));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** The day under the mouse on the month page, or -1. */
    private long dayAt(double mouseX, double mouseY) {
        SeasonState now = now();
        int cols = cols(now), rows = rows(now), bgRows = bgRows(rows), ch = rows <= bgRows ? CH : Math.max(9, bgRows * CH / rows);
        int cw = CONTENT / cols, gx = left + L, gy = top + TOP + 20;
        if (mouseX < gx || mouseY < gy) return -1;
        int c = (int) ((mouseX - gx) / cw), r = (int) ((mouseY - gy) / ch);
        if (c >= cols || r >= rows) return -1;
        int i = r * cols + c;
        return i < now.monthDays() ? firstDay(now) + i : -1;
    }
}
