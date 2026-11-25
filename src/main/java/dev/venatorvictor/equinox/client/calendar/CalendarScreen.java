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
 * The calendar's pages (preview calendar_page_v13): the month page (season bar, the days by week, today framed, past
 * days crossed out, the new and full moons on their first day, an equinox or solstice band) with a footer (today's
 * moon, which opens the moon page; the next equinox or solstice; the day length; days to the next season), the page
 * arrow going on through the months; and the moon page (all 8 phases with their days), its arrow going back.
 */
public final class CalendarScreen extends Screen {
    private static final ResourceLocation MOON_BG = tex("moon"), MOONS = tex("moons"), HAND_X = tex("hand_x");
    private static final int[] BG_ROWS = {2, 4, 6, 8};
    private static final int W = 226, L = 14, RM = 20, TOP = 26, CONTENT = 192, CH = 18, RH = 14;

    private int offset;              // months after the current one (0 .. 11)
    private boolean moonPage;
    private int left, top, height_;
    // the footer's moon line (opens the moon page)
    private int moonX0, moonY0, moonX1, moonY1;

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

    /** The first day of the month on show. */
    private SeasonState viewed(SeasonState now) {
        long day = Math.floorDiv(now.dayTime(), SeasonState.DAY) - now.dayOfMonth() + (long) offset * now.monthDays();
        return new SeasonState(day * SeasonState.DAY, now.daysPerSeason(), now.daysPerWeek());
    }

    private static int cols(SeasonState s) {
        return Math.max(1, Math.min(s.daysPerWeek(), s.monthDays()));
    }

    private static int rows(SeasonState s) {
        return (s.monthDays() + cols(s) - 1) / cols(s);
    }

    /** The background made for at least this many rows (or the tallest), and the cell height that fills it. */
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
        if (moonPage) {
            height_ = TOP + 22 + 8 * RH + 34;
        } else {
            height_ = monthHeight(bgRows(rows(viewed(now))));
        }
        left = (width - W) / 2;
        top = (height - height_) / 2;
        if (moonPage) {
            addRenderableWidget(new PageButton(left + L + 2, top + TOP + 22 + 8 * RH + 2, false, b -> {
                moonPage = false;
                rebuildWidgets();
            }, true));
        } else {
            int fy = top + TOP + 20 + bgRows(rows(viewed(now))) * CH + 4;
            addRenderableWidget(new PageButton(left + W - RM - 23 - 14, fy + 6 + 48 + 8, true, b -> {
                offset = (offset + 1) % 12;                   // on through the months, back to this one after a year
                rebuildWidgets();
            }, true));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        ResourceLocation bg = moonPage ? MOON_BG : tex("month_" + bgRows(rows(viewed(now()))));
        g.blit(bg, left, top, 0, 0, W, height_, W, height_);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        SeasonState now = now();
        if (moonPage) renderMoonPage(g, now);
        else renderMonthPage(g, now, mouseX, mouseY);
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

    private static void moon(GuiGraphics g, int x, int y, int vanillaPhase) {
        g.blit(MOONS, x, y, vanillaPhase * 10, 0, 10, 10, 80, 10);
    }

    private static String clock(double hours) {
        int minutes = (int) Math.round(hours * 60);
        return String.format(Locale.ROOT, "%d:%02d", Math.floorMod(minutes / 60, 24), Math.floorMod(minutes, 60));
    }

    private static String yearText(SeasonState s) {
        return I18n.get("equinox.calendar.year", CalendarLook.season(s.season()), s.year() + 1);
    }

    // ---------- the month page

    private void renderMonthPage(GuiGraphics g, SeasonState now, int mouseX, int mouseY) {
        SeasonState view = viewed(now);
        int month = view.month(), seasonIdx = view.season().ordinal();
        boolean current = offset == 0;
        int today = current ? now.dayOfMonth() + 1 : 0;
        seasonBar(g, view.season(), CalendarLook.month(month), yearText(view));

        int cols = cols(view), rows = rows(view), monthDays = view.monthDays();
        int bgRows = bgRows(rows), ch = rows <= bgRows ? CH : Math.max(9, bgRows * CH / rows);
        int cw = CONTENT / cols, gx = left + L, gy = top + TOP + 20;
        int per = Math.max(1, monthDays / 8);
        // the first month of a season starts it: a band of the season's colour on day 1
        int markSeason = month % 3 == 0 ? month / 3 : -1;
        for (int i = 0; i < monthDays; i++) {
            int r = i / cols, c = i % cols, day = i + 1;
            int x0 = gx + c * cw, y0 = gy + r * ch;
            cell(g, x0, y0, cw, ch, r == 0, c == 0, day, today, seasonIdx, day == 1 ? markSeason : -1, per);
        }

        // footer
        int fy = gy + bgRows * CH + 4;
        int x1 = left + W - RM;
        g.fill(gx, fy, x1, fy + 1, argb(CalendarLook.GRID));
        int y = fy + 6;
        int phaseDay = current ? today : 1;
        int step = Math.min((phaseDay - 1) / per, 7), phase = (step + 4) % 8;
        moon(g, gx + 1, y - 1, phase);
        String moonName = CalendarLook.moon(phase);
        moonX0 = gx; moonY0 = y - 2; moonX1 = gx + 14 + font.width(moonName); moonY1 = y + 10;
        boolean hover = mouseX >= moonX0 && mouseX < moonX1 && mouseY >= moonY0 && mouseY < moonY1;
        g.drawString(font, Component.literal(moonName).withStyle(Style.EMPTY.withUnderlined(hover)), gx + 14, y, argb(CalendarLook.INK), false);
        if (current) textRight(g, I18n.get("equinox.calendar.day_of", today, monthDays), x1, y, CalendarLook.FADED);
        y += 12;

        // the next equinox or solstice (this month's, if it starts a season)
        int markS = month % 3 == 0 ? month / 3 : (month / 3 + 1) % 4;
        int markMonth = markS * 3;
        g.fill(gx + 1, y - 1, gx + 11, y + 9, argb(CalendarLook.SHADE[markS]));
        g.fill(gx + 2, y, gx + 10, y + 8, argb(CalendarLook.BAND[markS]));
        String mark = I18n.get("equinox.calendar.mark." + Season.VALUES[markS].id());
        text(g, I18n.get("equinox.calendar.mark_on", mark, CalendarLook.monthInText(markMonth), 1), gx + 14, y, CalendarLook.SHADE[markS]);
        y += 12;

        long dayTime = current ? now.dayTime() : view.dayTime() + 6000;
        double hours = DayLength.daylightHours(dayTime, view.daysPerSeason());
        text(g, I18n.get("equinox.calendar.day_length"), gx + 1, y, CalendarLook.FADED);
        textRight(g, clock(12 - hours / 2) + " - " + clock(12 + hours / 2), x1, y, CalendarLook.INK);
        y += 12;

        Season next = Season.VALUES[(now.season().ordinal() + 1) % Season.COUNT];
        int left_ = now.daysPerSeason() - now.dayOfSeason();
        text(g, I18n.get("equinox.calendar.next_label", CalendarLook.season(next)), gx + 1, y, CalendarLook.FADED);
        textRight(g, I18n.get(left_ == 1 ? "equinox.calendar.next_value_one" : "equinox.calendar.next_value", left_), x1, y, CalendarLook.INK);
    }

    private void cell(GuiGraphics g, int x0, int y0, int cw, int ch, boolean firstRow, boolean firstCol, int day, int today,
                      int season, int markSeason, int per) {
        int grid = argb(CalendarLook.GRID);
        g.fill(x0, y0 + ch - 1, x0 + cw, y0 + ch, grid);
        if (firstRow) g.fill(x0, y0, x0 + cw, y0 + 1, grid);
        g.fill(x0 + cw - 1, y0, x0 + cw, y0 + ch, grid);
        if (firstCol) g.fill(x0, y0, x0 + 1, y0 + ch, grid);
        if (day == today) {
            g.fill(x0 + 1, y0 + 1, x0 + cw - 1, y0 + ch - 1, argb(CalendarLook.RED));
            g.fill(x0 + 2, y0 + 2, x0 + cw - 2, y0 + ch - 2, argb(CalendarLook.LIGHT[season]));
        }
        if (markSeason >= 0) {
            int inset = day == today ? 2 : 1;
            g.fill(x0 + inset, y0 + inset, x0 + cw - inset, y0 + 3, argb(CalendarLook.BAND[markSeason]));
            g.fill(x0 + inset, y0 + 3, x0 + cw - inset, y0 + 4, argb(CalendarLook.SHADE[markSeason]));
        }
        String n = String.valueOf(day);
        int nx = x0 + (cw - font.width(n)) / 2, ny = y0 + ch - 11;
        text(g, n, nx, ny, CalendarLook.INK);
        // the new and the full moon, on their first day
        if (day == 1) moon(g, x0 + cw - 12, y0 + ch - 12, 4);
        else if (day == 4 * per + 1) moon(g, x0 + cw - 12, y0 + ch - 12, 0);
        if (today > 0 && day < today) {
            int v = (day - 1) % 12;                                // each day its own cross
            g.blit(HAND_X, nx + font.width(n) / 2 - 10, ny + 3 - 8, v * 20, 0, 20, 16, 240, 16);
        }
    }

    // ---------- the moon page

    private void renderMoonPage(GuiGraphics g, SeasonState now) {
        SeasonState view = viewed(now);
        seasonBar(g, view.season(), I18n.get("equinox.calendar.moon_title", CalendarLook.monthInText(view.month())), yearText(view));
        int per = Math.max(1, view.monthDays() / 8);
        int todayStep = offset == 0 ? Math.min(now.dayOfMonth() / per, 7) : -1;
        int gx = left + L, x1 = left + W - RM, gy = top + TOP + 22;
        for (int k = 0; k < 8; k++) {
            int phase = (k + 4) % 8, a = k * per + 1, b = k * per + per, y = gy + k * RH;
            if (k == todayStep) g.fill(gx, y - 2, x1, y + RH - 3, argb(CalendarLook.LIGHT[view.season().ordinal()]));
            moon(g, gx + 2, y - 1, phase);
            text(g, CalendarLook.moon(phase), gx + 16, y, CalendarLook.INK);
            String d = b > a ? I18n.get("equinox.calendar.days", a, b) : I18n.get("equinox.calendar.day", a);
            textRight(g, d, x1 - 2, y, k == todayStep ? CalendarLook.RED : CalendarLook.FADED);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!moonPage && button == 0 && mouseX >= moonX0 && mouseX < moonX1 && mouseY >= moonY0 && mouseY < moonY1) {
            moonPage = true;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
            rebuildWidgets();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
