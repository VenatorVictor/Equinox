package dev.venator.seasonsrework.client.config;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.client.ChunkRefresher;
import dev.venator.seasonsrework.client.ClientConfig;
import dev.venator.seasonsrework.client.config.Setting.Cost;
import dev.venator.seasonsrework.client.config.Setting.Kind;
import dev.venator.seasonsrework.client.config.Setting.Side;
import dev.venator.seasonsrework.snow.SnowConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The mod's config screen (Mods > Seasons Rework > Config): tabs, search, one row per setting with a cost mark,
 * a control in real units (days, °C, blocks, seconds) and a reset button; hovering a name explains it. Changes are
 * kept until Done (or Esc) and then saved to their files, which reloads them.
 */
public final class SettingsScreen extends Screen implements Setting.Values {
    private static final int ROW = 22, CONTROL = 150, RESET = 40, GAP = 2;
    private static final int WHITE = 0xFFFFFFFF, CHANGED = 0xFFFFD27F, QUIET = 0xFFA0A0A0, LOCKED = 0xFF707070, NOTICE = 0xFFFFAA00;

    private final Screen parent;
    private final Map<ModConfigSpec.ConfigValue<?>, Object> pending = new LinkedHashMap<>();
    private String tab = Settings.TABS.get(0);
    private String query = "";
    private int scroll;
    private EditBox search;
    private boolean dirty;

    // Layout of the current frame.
    private int listX, listWidth, top, bottom, visible, total;
    private final List<Object[]> rows = new ArrayList<>();      // {y, Setting}
    private final List<Object[]> headings = new ArrayList<>();  // {y, String}
    private String note;

    public SettingsScreen(ModContainer container, Screen parent) {
        super(Component.literal("Seasons Rework"));
        this.parent = parent;
    }

    /** A screen text from the language file: seasonsrework.settings.<key>. */
    static String t(String key, Object... args) {
        return I18n.get(Setting.PREFIX + key, args);
    }

    // ---- values

    /** The value being edited, else the saved one, else (world settings outside a world) the default. */
    Object current(Setting s) {
        if (pending.containsKey(s.value)) return pending.get(s.value);
        return readable(s) ? s.value.get() : s.value.getDefault();
    }

    private static boolean readable(Setting s) {
        return s.side != Side.SERVER || Config.SPEC.isLoaded();
    }

    @Override
    public int daysPerSeason() {
        for (Setting s : Settings.ALL) if (s.value == Config.DAYS_PER_SEASON) return Math.max(1, ((Number) current(s)).intValue());
        return 42;
    }

    /** Why this setting can't be changed here, or null. */
    String locked(Setting s) {
        if (s.side == Side.CLIENT || minecraft == null) return null;
        boolean remote = minecraft.getConnection() != null && !minecraft.hasSingleplayerServer();
        if (remote) return t("locked.remote");
        if (s.side == Side.SERVER) {
            if (!Config.SPEC.isLoaded()) return t("locked.no_world");
            if (minecraft.getSingleplayerServer() != null && minecraft.getSingleplayerServer().isPublished())
                return t("locked.lan");
        }
        return null;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Math.abs(x.doubleValue() - y.doubleValue()) < 1e-6;
        return Objects.equals(a, b);
    }

    void edit(Setting s, Object value) {
        Object saved = readable(s) ? s.value.get() : s.value.getDefault();
        if (same(value, saved)) pending.remove(s.value);
        else pending.put(s.value, value);
    }

    private Object toStored(Setting s, double shown) {
        double raw = s.stored.apply(shown, this);
        if (s.value instanceof ModConfigSpec.IntValue) return (int) Math.round(raw);
        if (s.value instanceof ModConfigSpec.LongValue) return Math.round(raw);
        return raw;
    }

    private double toShown(Setting s, Object stored) {
        return s.shown.apply(((Number) stored).doubleValue(), this);
    }

    String labelOf(Setting s, Object stored) {
        return switch (s.kind) {
            case TOGGLE -> (Boolean.TRUE.equals(stored) ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF).getString();
            case CHOICE -> {
                for (Map.Entry<Object, String> choice : s.choices.entrySet()) if (same(choice.getKey(), stored)) yield t("choice." + choice.getValue());
                yield String.valueOf(stored);
            }
            case SLIDER -> s.label.apply(toShown(s, stored), this);
            case LIST -> t("entries", ((List<?>) stored).size());
            case TEXT -> String.valueOf(stored);
        };
    }

    // ---- layout

    private List<Object> items() {
        List<Object> out = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            for (Setting s : Settings.ALL) {
                if (!s.tab.equals(tab)) continue;
                if (s.heading != null) out.add(s.headingText());
                out.add(s);
            }
            return out;
        }
        String lastTab = null;
        for (Setting s : Settings.ALL) {
            if (!(s.name().toLowerCase(Locale.ROOT).contains(q) || s.description().toLowerCase(Locale.ROOT).contains(q)
                    || s.path().toLowerCase(Locale.ROOT).contains(q))) continue;
            if (!s.tab.equals(lastTab)) out.add(t("tab." + (lastTab = s.tab)));
            out.add(s);
        }
        return out;
    }

    @Override
    protected void init() {
        rows.clear();
        headings.clear();
        dirty = false;
        listWidth = Math.min(400, width - 24);
        listX = (width - listWidth) / 2;

        if (search == null) {
            search = new EditBox(font, 0, 0, 200, 16, Component.literal("Search"));
            search.setMaxLength(64);
            search.setHint(Component.translatable(Setting.PREFIX + "search").withStyle(ChatFormatting.DARK_GRAY));
            search.setResponder(text -> {
                if (!text.equals(query)) {
                    query = text;
                    scroll = 0;
                    dirty = true;
                }
            });
        }
        int searchWidth = Math.min(220, listWidth);
        search.setRectangle(searchWidth, 16, width / 2 - searchWidth / 2, 20);
        addRenderableWidget(search);

        boolean searching = !query.isBlank();
        int namesWidth = 0;
        for (String name : Settings.TABS) namesWidth += font.width(t("tab." + name)) + 12;
        int gaps = GAP * (Settings.TABS.size() - 1), room = width - 8;
        double squeeze = namesWidth + gaps > room ? (double) (room - gaps) / namesWidth : 1;
        int tabX = (width - (int) (namesWidth * squeeze) - gaps) / 2;
        for (String name : Settings.TABS) {
            int w = (int) ((font.width(t("tab." + name)) + 12) * squeeze);
            Button button = Button.builder(Component.literal(t("tab." + name)), b -> {
                tab = name;
                scroll = 0;
                query = "";
                search.setValue("");
                dirty = true;
            }).bounds(tabX, 42, w, 18).build();
            button.active = searching || !name.equals(tab);
            addRenderableWidget(button);
            tabX += w + GAP;
        }

        List<Object> items = items();
        note = null;
        for (Object item : items) if (item instanceof Setting s && locked(s) != null) { note = locked(s); break; }
        top = note != null ? 78 : 66;
        bottom = height - 48;
        visible = Math.max(1, (bottom - top) / ROW);
        total = items.size();
        scroll = Math.max(0, Math.min(scroll, total - visible));
        for (int i = scroll; i < Math.min(total, scroll + visible); i++) {
            int y = top + (i - scroll) * ROW;
            if (items.get(i) instanceof Setting s) {
                rows.add(new Object[] {y, s});
                place(s, y);
            } else headings.add(new Object[] {y, items.get(i)});
        }

        int buttonsY = height - 26, w = 100;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> cancel()).bounds(width / 2 - w - 2, buttonsY, w, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 2, buttonsY, w, 20).build());
        if (searching) setFocused(search);
    }

    private int controlX() {
        return listX + listWidth - RESET - GAP - CONTROL;
    }

    private void place(Setting s, int y) {
        int x = controlX();
        Object value = current(s);
        boolean active = locked(s) == null;
        Component name = Component.literal(s.name());
        switch (s.kind) {
            case TOGGLE -> {
                CycleButton<Boolean> button = CycleButton.onOffBuilder(Boolean.TRUE.equals(value)).displayOnlyValue()
                        .create(x, y, CONTROL, 20, name, (b, v) -> { edit(s, v); dirty = true; });
                button.active = active;
                addRenderableWidget(button);
            }
            case CHOICE -> {
                List<Object> options = new ArrayList<>(s.choices.keySet());
                Object start = null;
                for (Object option : options) if (same(option, value)) start = option;
                if (start == null) options.add(start = value);
                CycleButton<Object> button = CycleButton.<Object>builder(v -> Component.literal(labelOf(s, v)))
                        .withValues(options).withInitialValue(start).displayOnlyValue()
                        .create(x, y, CONTROL, 20, name, (b, v) -> { edit(s, v); dirty = true; });
                button.active = active;
                addRenderableWidget(button);
            }
            case SLIDER -> {
                Slider slider = new Slider(x, y, s, stops(s, value), toShown(s, value));
                slider.active = active;
                addRenderableWidget(slider);
            }
            case LIST -> {
                @SuppressWarnings("unchecked") List<String> list = (List<String>) value;
                Button button = Button.builder(Component.literal(t("edit_list", list.size())),
                        b -> minecraft.setScreen(new ListEditScreen(this, s, list, active))).bounds(x, y, CONTROL, 20).build();
                addRenderableWidget(button);
            }
            case TEXT -> {
                EditBox box = new EditBox(font, x + 1, y + 1, CONTROL - 2, 18, name);
                box.setMaxLength(32);
                box.setValue(String.valueOf(value));
                box.setResponder(text -> {
                    try {
                        edit(s, Long.parseLong(text.trim()));
                        box.setTextColor(0xFFE0E0E0);
                    } catch (NumberFormatException e) {
                        box.setTextColor(0xFFFF5555);
                    }
                });
                box.setEditable(active);
                addRenderableWidget(box);
            }
        }
        Button reset = Button.builder(Component.translatable("controls.reset"), b -> { edit(s, s.value.getDefault()); dirty = true; })
                .bounds(listX + listWidth - RESET, y, RESET, 20).build();
        reset.active = active && !same(value, s.value.getDefault());
        addRenderableWidget(reset);
    }

    /** Slider stops in shown units: the range by its step, plus the current and default values if they're off the grid. */
    private double[] stops(Setting s, Object value) {
        double max = s.maxOf != null ? s.maxOf.applyAsDouble(this) : s.max;
        TreeSet<Double> stops = new TreeSet<>();
        int count = (int) Math.round((max - s.min) / s.step);
        for (int i = 0; i <= count; i++) stops.add(Math.round((s.min + i * s.step) * 1e6) / 1e6);
        stops.add(Math.round(toShown(s, value) * 1e6) / 1e6);
        stops.add(Math.round(toShown(s, s.value.getDefault()) * 1e6) / 1e6);
        double[] out = new double[stops.size()];
        int i = 0;
        for (double stop : stops) out[i++] = stop;
        return out;
    }

    @Override
    public void tick() {
        super.tick();
        if (dirty) rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= top && mouseY < bottom && total > visible) {
            int next = Math.max(0, Math.min(total - visible, scroll - (int) Math.signum(scrollY)));
            if (next != scroll) {
                scroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- drawing

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        gfx.drawCenteredString(font, title, width / 2, 6, WHITE);
        String status = pending.isEmpty() ? "" : t(pending.size() == 1 ? "unsaved.one" : "unsaved", pending.size());
        gfx.drawString(font, status, listX + listWidth - font.width(status), 6, CHANGED);
        if (note != null) gfx.drawCenteredString(font, note, width / 2, 65, NOTICE);

        for (Object[] row : rows) {
            int y = (Integer) row[0];
            Setting s = (Setting) row[1];
            gfx.fill(listX, y + 5, listX + 4, y + 14, s.cost.color);
            boolean locked = locked(s) != null;
            int color = locked ? LOCKED : same(current(s), s.value.getDefault()) ? WHITE : CHANGED;
            String name = font.plainSubstrByWidth(s.name(), controlX() - listX - 14);
            gfx.drawString(font, name, listX + 8, y + 6, color);
        }
        for (Object[] heading : headings) {
            int y = (Integer) heading[0];
            String text = (String) heading[1];
            int w = font.width(text);
            gfx.fill(listX, y + 10, width / 2 - w / 2 - 6, y + 11, 0x40FFFFFF);
            gfx.fill(width / 2 + w / 2 + 6, y + 10, listX + listWidth, y + 11, 0x40FFFFFF);
            gfx.drawCenteredString(font, text, width / 2, y + 7, QUIET);
        }
        if (total > visible) {
            int barX = listX + listWidth + 6, height = visible * ROW;
            int thumb = Math.max(12, height * visible / total);
            int thumbY = top + (height - thumb) * scroll / Math.max(1, total - visible);
            gfx.fill(barX, top, barX + 3, top + height, 0x30FFFFFF);
            gfx.fill(barX, thumbY, barX + 3, thumbY + thumb, 0xA0FFFFFF);
        }
        if (total == 0) gfx.drawCenteredString(font, t("no_match"), width / 2, top + 8, QUIET);

        // Cost key.
        String lead = t("cost_key");
        int keyWidth = font.width(lead) + 8;
        for (Cost cost : Cost.values()) keyWidth += 7 + font.width(cost.key()) + 10;
        int keyX = width / 2 - keyWidth / 2, keyY = height - 41;
        gfx.drawString(font, lead, keyX, keyY, QUIET);
        keyX += font.width(lead) + 8;
        for (Cost cost : Cost.values()) {
            gfx.fill(keyX, keyY - 1, keyX + 4, keyY + 8, cost.color);
            gfx.drawString(font, cost.key(), keyX + 7, keyY, QUIET);
            keyX += 7 + font.width(cost.key()) + 10;
        }

        Setting over = hovered(mouseX, mouseY);
        if (over != null) gfx.renderTooltip(font, describe(over), mouseX, mouseY);
    }

    private Setting hovered(int mouseX, int mouseY) {
        if (mouseX < listX || mouseX >= controlX() - GAP) return null;
        for (Object[] row : rows) {
            int y = (Integer) row[0];
            if (mouseY >= y && mouseY < y + 20) return (Setting) row[1];
        }
        return null;
    }

    private List<FormattedCharSequence> describe(Setting s) {
        int wrap = Math.min(300, width - 24);
        List<FormattedCharSequence> out = new ArrayList<>();
        out.addAll(font.split(Component.literal(s.name()).withStyle(ChatFormatting.BOLD), wrap));
        for (String line : s.description().split("\n")) out.addAll(font.split(Component.literal(line), wrap));
        out.add(FormattedCharSequence.EMPTY);
        out.add(Component.literal(t("tooltip.cost") + " ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(s.cost.label()).withColor(s.cost.color & 0xFFFFFF)).getVisualOrderText());
        String costNote = s.costNote();
        if (costNote != null) out.addAll(font.split(Component.literal(costNote).withStyle(ChatFormatting.GRAY), wrap));
        out.add(Component.literal(t("tooltip.default", labelOf(s, s.value.getDefault()))).withStyle(ChatFormatting.GRAY).getVisualOrderText());
        String applies = s.appliesText();
        if (applies != null) out.addAll(font.split(Component.literal(applies).withStyle(ChatFormatting.AQUA), wrap));
        String locked = locked(s);
        if (locked != null) out.addAll(font.split(Component.literal(locked).withStyle(ChatFormatting.GOLD), wrap));
        out.add(FormattedCharSequence.EMPTY);
        out.addAll(font.split(Component.literal(s.side.label()).withStyle(ChatFormatting.DARK_GRAY), wrap));
        out.addAll(font.split(Component.literal(s.side.file + " > " + s.path()).withStyle(ChatFormatting.DARK_GRAY), wrap));
        return out;
    }

    // ---- closing

    /** Saves every changed value to its file (which reloads it) and redraws what changed on screen. */
    private void save() {
        if (pending.isEmpty()) return;
        Set<ModConfigSpec> specs = new HashSet<>();
        boolean redraw = false, textures = false;
        for (Setting s : Settings.ALL) {
            if (!pending.containsKey(s.value) || locked(s) != null) continue;
            @SuppressWarnings("unchecked") ModConfigSpec.ConfigValue<Object> value = (ModConfigSpec.ConfigValue<Object>) s.value;
            value.set(pending.get(s.value));
            specs.add(switch (s.side) {
                case CLIENT -> ClientConfig.SPEC;
                case COMMON -> SnowConfig.SPEC;
                case SERVER -> Config.SPEC;
            });
            if (s.value == ClientConfig.CROP_COLORS) textures = true;
            else if ("redraw".equals(s.applies)) redraw = true;
        }
        pending.clear();
        for (ModConfigSpec spec : specs) spec.save();
        if (minecraft != null && minecraft.level != null && redraw) ChunkRefresher.refreshAll();
        if (minecraft != null && textures) minecraft.reloadResourcePacks();
    }

    private void cancel() {
        pending.clear();
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        save();
        minecraft.setScreen(parent);
    }

    /** A slider that snaps to its stops and shows the value in the setting's units. */
    private final class Slider extends AbstractSliderButton {
        private final Setting setting;
        private final double[] stops;

        Slider(int x, int y, Setting setting, double[] stops, double current) {
            super(x, y, CONTROL, 20, Component.empty(), 0);
            this.setting = setting;
            this.stops = stops;
            int nearest = 0;
            for (int i = 1; i < stops.length; i++) if (Math.abs(stops[i] - current) < Math.abs(stops[nearest] - current)) nearest = i;
            value = stops.length < 2 ? 0 : (double) nearest / (stops.length - 1);
            updateMessage();
        }

        private int index() {
            return stops.length < 2 ? 0 : (int) Math.round(value * (stops.length - 1));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(setting.label.apply(stops[index()], SettingsScreen.this)));
        }

        @Override
        protected void applyValue() {
            edit(setting, toStored(setting, stops[index()]));
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            dirty = true; // reset button, other sliders' units (season length)
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            // Arrow keys move one stop.
            if (stops.length > 1 && (keyCode == 263 || keyCode == 262)) {
                int i = Math.max(0, Math.min(stops.length - 1, index() + (keyCode == 263 ? -1 : 1)));
                value = (double) i / (stops.length - 1);
                applyValue();
                updateMessage();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }
}
