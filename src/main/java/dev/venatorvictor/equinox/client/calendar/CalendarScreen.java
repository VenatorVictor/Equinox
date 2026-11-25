package dev.venatorvictor.equinox.client.calendar;

import dev.venatorvictor.equinox.api.Season;
import dev.venatorvictor.equinox.calendar.CalendarDates;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The calendar's pages (preview calendar_page_v13), in game time or real time (CalendarDates).
 * - Month page: season bar, the days by week (real time: Monday to Sunday, named), today framed in red, the chosen day
 *   shaded, past days crossed out, the new and full moons on their first day, the equinoxes and solstices banded.
 *   Footer about the chosen day (click a day; today at first): its moon (click: the moon page), the next equinox or
 *   solstice (click: the events page), its day length; and the days to the next season. Arrows: back and forward.
 * - Moon page: the 8 phases with their days this month.
 * - Events page: the year's equinoxes and solstices, with date, day length and how far from today.
 */
public final class CalendarScreen extends Screen {
    private static final ResourceLocation LIST_BG = tex("moon"), MOONS = tex("moons"), HAND_X = tex("hand_x");
    private static final int[] BG_ROWS = {2, 4, 6, 8};
    private static final int W = 226, L = 14, RM = 20, TOP = 26, CONTENT = 192, CH = 18, RH = 14, MARK_RH = 28;
    private enum Page { MONTH, MOON, MARKS }

    private int offset;              // months from the current one
    private Page page = Page.MONTH;
    private long selected = Long.MIN_VALUE;   // the chosen day; MIN_VALUE: today, or the month's first day
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

    private static CalendarDates dates() {
        Minecraft mc = Minecraft.getInstance();
        return CalendarDates.current(mc.level == null ? 0 : mc.level.getDayTime());
    }

    /** The first day of the month on show. */
    private long firstDay(CalendarDates d) {
        return d.plusMonths(d.firstOfMonth(d.today()), offset);
    }

    private long chosen(CalendarDates d) {
        if (selected != Long.MIN_VALUE) return selected;
        return offset == 0 ? d.today() : firstDay(d);
    }

    /** Grid layout of a month: columns, its first day's column, rows of days, and a row of weekday names on top. */
    private record Grid(int cols, int lead, int rows, boolean header) {
        int allRows() {
            return rows + (header ? 1 : 0);
        }
    }

    private static Grid grid(CalendarDates d, long first) {
        int cols = d.weekLength(), lead = d.firstColumn(first);
        return new Grid(cols, lead, (lead + d.monthLength(first) + cols - 1) / cols, d.weekdayNames());
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
        CalendarDates d = dates();
        long first = firstDay(d);
        int rows = bgRows(grid(d, first).allRows());
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
        if (first > d.earliest())                                 // nothing before the world's first month
            addRenderableWidget(new PageButton(left + L + 2, ay, false, b -> turn(-1), true));
        addRenderableWidget(new PageButton(left + W - RM - 23 - 14, ay, true, b -> turn(1), true));
    }

    private void turn(int months) {
        offset += months;
        selected = Long.MIN_VALUE;
        rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        CalendarDates d = dates();
        ResourceLocation bg = page == Page.MONTH ? tex("month_" + bgRows(grid(d, firstDay(d)).allRows())) : LIST_BG;
        g.blit(bg, left, top, 0, 0, W, height_, W, height_);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        CalendarDates d = dates();
        switch (page) {
            case MONTH -> renderMonthPage(g, d, mouseX, mouseY);
            case MOON -> renderMoonPage(g, d);
            case MARKS -> renderMarksPage(g, d);
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
            int c = pass == 0 ? CalendarLook.SHADE[i] : 0xFFFFFF, dd = 1 - pass;
            text(g, leftText, x0 + 4 + dd, y0 + 3 + dd, c);
            textRight(g, rightText, x1 - 4 + dd, y0 + 3 + dd, c);
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

    private static String daylight(CalendarDates d, long day) {
        double hours = d.daylightHours(day);
        return clock(12 - hours / 2) + "-" + clock(12 + hours / 2);
    }

    private static String yearText(CalendarDates d, long day) {
        return I18n.get("equinox.calendar.year", CalendarLook.season(d.season(day)), d.year(day));
    }

    /** A new (0) or full (4) moon starts on this day. */
    private static boolean moonStarts(CalendarDates d, long day, int step) {
        return d.moonStep(day) == step && d.moonStep(day - 1) != step;
    }

    // ---------- the month page

    private void renderMonthPage(GuiGraphics g, CalendarDates d, int mouseX, int mouseY) {
        long first = firstDay(d), today = d.today(), pick = chosen(d);
        Season viewSeason = d.season(first);
        seasonBar(g, viewSeason, CalendarLook.month(d.month(first)), yearText(d, first));

        Grid grid = grid(d, first);
        int monthDays = d.monthLength(first), bgRows = bgRows(grid.allRows());
        int ch = grid.allRows() <= bgRows ? CH : Math.max(9, bgRows * CH / grid.allRows());
        int cw = CONTENT / grid.cols(), gx = left + L, gy = top + TOP + 20;
        if (grid.header()) {                                       // weekday names
            for (int c = 0; c < grid.cols(); c++) {
                String name = I18n.get("equinox.calendar.weekday." + c);
                text(g, name, gx + c * cw + (cw - font.width(name)) / 2, gy + ch - 11, CalendarLook.FADED);
            }
            gy += ch;
        }
        for (int i = 0; i < monthDays; i++) {
            int slot = grid.lead() + i, r = slot / grid.cols(), c = slot % grid.cols();
            long day = first + i;
            int mark = d.isMark(day) ? d.season(day).ordinal() : -1;
            int moon = moonStarts(d, day, 0) ? 4 : moonStarts(d, day, 4) ? 0 : -1;
            cell(g, gx + c * cw, gy + r * ch, cw, ch, r == 0 || slot - grid.cols() < grid.lead(), c == 0 || i == 0, i + 1,
                    day == today, day < today, day < d.earliest(), day == pick, d.season(day).ordinal(), mark, moon);
        }

        // footer: about the chosen day
        int fy = top + TOP + 20 + bgRows * CH + 4, x1 = left + W - RM;
        g.fill(gx, fy, x1, fy + 1, argb(CalendarLook.GRID));
        int y = fy + 6;
        int phase = (d.moonStep(pick) + 4) % 8;
        moon(g, gx + 1, y - 1, phase);
        link(g, CalendarLook.moon(phase), gx + 14, y, CalendarLook.INK, moonBox, gx, mouseX, mouseY);
        y += 12;

        // the next equinox or solstice from the chosen day (that day's own, if it is one)
        long markAt = d.nextMark(pick);
        int markS = d.season(markAt).ordinal();
        swatch(g, gx + 1, y, markS);
        String mark = I18n.get("equinox.calendar.mark." + Season.VALUES[markS].id());
        link(g, I18n.get("equinox.calendar.mark_on", mark, CalendarLook.monthInText(d.month(markAt)), d.dayOfMonth(markAt) + 1),
                gx + 14, y, CalendarLook.SHADE[markS], markBox, gx, mouseX, mouseY);
        y += 12;

        text(g, I18n.get("equinox.calendar.day_length"), gx + 1, y, CalendarLook.FADED);
        textRight(g, daylight(d, pick), x1, y, CalendarLook.INK);
        y += 12;

        long nextSeason = d.nextMark(today + 1);
        long daysLeft = nextSeason - today;
        text(g, I18n.get("equinox.calendar.next_label", CalendarLook.season(d.season(nextSeason))), gx + 1, y, CalendarLook.FADED);
        textRight(g, I18n.get(daysLeft == 1 ? "equinox.calendar.next_value_one" : "equinox.calendar.next_value", daysLeft), x1, y, CalendarLook.INK);
    }

    private void cell(GuiGraphics g, int x0, int y0, int cw, int ch, boolean topEdge, boolean leftEdge, int day,
                      boolean today, boolean past, boolean beforeWorld, boolean picked, int season, int markSeason, int moon) {
        int grid = argb(CalendarLook.GRID);
        g.fill(x0, y0 + ch - 1, x0 + cw, y0 + ch, grid);
        if (topEdge) g.fill(x0, y0, x0 + cw, y0 + 1, grid);
        g.fill(x0 + cw - 1, y0, x0 + cw, y0 + ch, grid);
        if (leftEdge) g.fill(x0, y0, x0 + 1, y0 + ch, grid);
        if (today) g.fill(x0 + 1, y0 + 1, x0 + cw - 1, y0 + ch - 1, argb(CalendarLook.RED));
        if (picked) g.fill(x0 + 2, y0 + 2, x0 + cw - 2, y0 + ch - 2, argb(CalendarLook.LIGHT[season]));
        else if (today) g.fill(x0 + 2, y0 + 2, x0 + cw - 2, y0 + ch - 2, argb(0xFFFBF0));
        if (markSeason >= 0) {                                     // equinox or solstice: the new season's band
            int inset = today ? 2 : 1;
            g.fill(x0 + inset, y0 + inset, x0 + cw - inset, y0 + 3, argb(CalendarLook.BAND[markSeason]));
            g.fill(x0 + inset, y0 + 3, x0 + cw - inset, y0 + 4, argb(CalendarLook.SHADE[markSeason]));
        }
        String n = String.valueOf(day);
        int nx = x0 + (cw - font.width(n)) / 2, ny = y0 + ch - 11;
        text(g, n, nx, ny, beforeWorld ? CalendarLook.GRID : CalendarLook.INK);   // before the world began: faint
        if (moon >= 0) moon(g, x0 + cw - 12, y0 + ch - 12, moon);  // the new and the full moon, on their first day
        if (past && !beforeWorld) {
            int v = (day - 1) % 12;                                // each day its own cross
            g.blit(HAND_X, nx + font.width(n) / 2 - 10, ny + 3 - 8, v * 20, 0, 20, 16, 240, 16);
        }
    }

    // ---------- the moon page

    private void renderMoonPage(GuiGraphics g, CalendarDates d) {
        long first = firstDay(d);
        seasonBar(g, d.season(first), I18n.get("equinox.calendar.moon_title", CalendarLook.monthInText(d.month(first))), yearText(d, first));
        int monthDays = d.monthLength(first);
        // each phase's days this month (a phase can come twice, or be skipped in short months), in the order they come
        List<Integer> order = new ArrayList<>();
        StringBuilder[] days = new StringBuilder[8];
        for (int i = 0; i < monthDays; i++) {
            int st = d.moonStep(first + i);
            if (i > 0 && st == d.moonStep(first + i - 1)) continue;
            int end = i;
            while (end + 1 < monthDays && d.moonStep(first + end + 1) == st) end++;
            String range = end > i ? I18n.get("equinox.calendar.days", i + 1, end + 1) : I18n.get("equinox.calendar.day", i + 1);
            if (days[st] == null) { days[st] = new StringBuilder(range); order.add(st); }
            else days[st].append(", ").append(range);
        }
        int start = order.isEmpty() ? 0 : order.get(0);           // phases skipped this month go last
        for (int k = 0; k < 8; k++) if (!order.contains((start + k) % 8)) order.add((start + k) % 8);
        long pick = chosen(d);
        int pickStep = pick >= first && pick < first + monthDays ? d.moonStep(pick) : -1;
        int gx = left + L, x1 = left + W - RM, gy = top + TOP + 22;
        for (int k = 0; k < 8; k++) {
            int st = order.get(k), phase = (st + 4) % 8, y = gy + k * RH;
            if (st == pickStep) g.fill(gx, y - 2, x1, y + RH - 3, argb(CalendarLook.LIGHT[d.season(first).ordinal()]));
            moon(g, gx + 2, y - 1, phase);
            text(g, CalendarLook.moon(phase), gx + 16, y, CalendarLook.INK);
            String text = days[st] == null ? "-" : days[st].toString();
            textRight(g, text, x1 - 2, y, st == pickStep ? CalendarLook.RED : CalendarLook.FADED);
        }
    }

    // ---------- the events page

    private void renderMarksPage(GuiGraphics g, CalendarDates d) {
        long first = firstDay(d), today = d.today();
        long january = d.firstOfYear(first), nextJanuary = d.firstOfYear(january + 400);
        seasonBar(g, d.season(first), I18n.get("equinox.calendar.marks_title"), yearText(d, first));
        int gx = left + L, x1 = left + W - RM, gy = top + TOP + 22;
        long next = d.nextMark(today);                             // the next one from today is shaded
        long day = d.nextMark(january);
        for (int k = 0; k < 4 && day < nextJanuary; k++, day = d.nextMark(day + 1)) {
            int s = d.season(day).ordinal(), y = gy + k * MARK_RH;
            if (day == next) g.fill(gx, y - 3, x1, y + MARK_RH - 5, argb(CalendarLook.LIGHT[s]));
            swatch(g, gx + 2, y, s);
            text(g, I18n.get("equinox.calendar.mark." + Season.VALUES[s].id()), gx + 16, y, CalendarLook.SHADE[s]);
            textRight(g, I18n.get("equinox.calendar.date", CalendarLook.monthInText(d.month(day)), d.dayOfMonth(day) + 1), x1 - 2, y, CalendarLook.INK);
            text(g, I18n.get("equinox.calendar.day_length") + " " + daylight(d, day), gx + 16, y + 11, CalendarLook.FADED);
            long gap = day - today;
            String when = gap == 0 ? I18n.get("equinox.calendar.today")
                    : gap > 0 ? I18n.get(gap == 1 ? "equinox.calendar.in_day" : "equinox.calendar.in_days", gap)
                    : I18n.get(gap == -1 ? "equinox.calendar.day_ago" : "equinox.calendar.days_ago", -gap);
            textRight(g, when, x1 - 2, y + 11, day == next ? CalendarLook.RED : CalendarLook.FADED);
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
            Long day = dayAt(mouseX, mouseY);
            if (day != null) {
                selected = day;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.4F));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** The day under the mouse on the month page, or null. */
    private Long dayAt(double mouseX, double mouseY) {
        CalendarDates d = dates();
        long first = firstDay(d);
        Grid grid = grid(d, first);
        int bgRows = bgRows(grid.allRows()), ch = grid.allRows() <= bgRows ? CH : Math.max(9, bgRows * CH / grid.allRows());
        int cw = CONTENT / grid.cols(), gx = left + L, gy = top + TOP + 20 + (grid.header() ? ch : 0);
        if (mouseX < gx || mouseY < gy) return null;
        int c = (int) ((mouseX - gx) / cw), r = (int) ((mouseY - gy) / ch);
        if (c >= grid.cols() || r >= grid.rows()) return null;
        int i = r * grid.cols() + c - grid.lead();
        return i >= 0 && i < d.monthLength(first) ? first + i : null;
    }
}
