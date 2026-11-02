package dev.venator.seasonsrework.client.config;

import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One row of the config screen: a config value plus how to show it (tab, name, description, cost, units). */
public final class Setting {
    /** Which file holds it, which decides who may change it. */
    public enum Side {
        CLIENT("config/seasonsrework-client.toml"),
        COMMON("config/seasonsrework-common.toml"),
        SERVER("<world>/serverconfig/seasonsrework-server.toml");

        final String file;

        Side(String file) {
            this.file = file;
        }

        String label() {
            return I18n.get(PREFIX + "side." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** How much it costs to have it on or set high (the colored mark in front of the name). */
    public enum Cost {
        NONE(0xFF4CAF50), LOW(0xFFC0CA33), MEDIUM(0xFFFFA000), HIGH(0xFFE53935), SAVES(0xFF42A5F5);

        final int color;

        Cost(int color) {
            this.color = color;
        }

        /** Short name for the key under the list. */
        String key() {
            return I18n.get(PREFIX + "cost." + name().toLowerCase(java.util.Locale.ROOT));
        }

        /** Longer words for the tooltip. */
        String label() {
            return I18n.get(PREFIX + "cost." + name().toLowerCase(java.util.Locale.ROOT) + ".long");
        }
    }

    enum Kind { TOGGLE, CHOICE, SLIDER, LIST, TEXT }

    /** Values other settings' units depend on (the ones being edited, not yet saved). */
    public interface Values {
        int daysPerSeason();
    }

    @FunctionalInterface
    public interface Convert {
        double apply(double value, Values values);
    }

    @FunctionalInterface
    public interface Label {
        String apply(double shown, Values values);
    }

    static final String PREFIX = "seasonsrework.settings.";

    final ModConfigSpec.ConfigValue<?> value;
    final Side side;
    /** Language keys: seasonsrework.settings.<id>.name / .desc / .cost (why it costs) / .format (list lines). */
    final String id;
    String tab = "", heading;
    Cost cost = Cost.NONE;
    String applies;
    Kind kind;
    // CHOICE: stored value -> label, in order.
    final Map<Object, String> choices = new LinkedHashMap<>();
    // SLIDER, in shown units.
    double min, max, step = 1;
    java.util.function.ToDoubleFunction<Values> maxOf;
    Convert shown = (v, values) -> v, stored = (v, values) -> v;
    Label label = (v, values) -> trim(v);
    Setting(ModConfigSpec.ConfigValue<?> value, Side side, String id) {
        this.value = value;
        this.side = side;
        this.id = id;
        if (value instanceof ModConfigSpec.BooleanValue) kind = Kind.TOGGLE;
        else if (value instanceof ModConfigSpec.EnumValue<?> e) {
            kind = Kind.CHOICE;
            for (Object constant : e.getDefault().getDeclaringClass().getEnumConstants()) choices.put(constant, constant.toString());
        } else if (value.getDefault() instanceof List) kind = Kind.LIST;
        else if (value instanceof ModConfigSpec.LongValue) kind = Kind.TEXT;
        else kind = Kind.SLIDER;
    }

    Setting tab(String tab, String heading) {
        this.tab = tab;
        this.heading = heading;
        return this;
    }

    String name() {
        return I18n.get(PREFIX + id + ".name");
    }

    String description() {
        return I18n.get(PREFIX + id + ".desc");
    }

    /** Why it costs what it does, or null. */
    String costNote() {
        return I18n.exists(PREFIX + id + ".cost") ? I18n.get(PREFIX + id + ".cost") : null;
    }

    /** When a change shows (e.g. chunks redraw), or null. */
    String appliesText() {
        return applies == null ? null : I18n.get(PREFIX + "applies." + applies);
    }

    /** The line format of a list setting. */
    String format() {
        return I18n.exists(PREFIX + id + ".format") ? I18n.get(PREFIX + id + ".format") : "";
    }

    String headingText() {
        return I18n.get(PREFIX + "heading." + heading);
    }

    Setting cost(Cost cost) {
        this.cost = cost;
        return this;
    }

    Setting applies(String applies) {
        this.applies = applies;
        return this;
    }

    /** A choice; its label is the language key seasonsrework.settings.choice.<label>. */
    Setting choice(Object stored, String label) {
        if (kind != Kind.CHOICE) {
            kind = Kind.CHOICE;
            choices.clear();
        }
        choices.put(stored, label);
        return this;
    }

    /** A slider from min to max (shown units) in steps. */
    Setting range(double min, double max, double step) {
        this.min = min;
        this.max = max;
        this.step = step;
        return this;
    }

    /** The slider's top depends on other settings (e.g. a season's days). */
    Setting max(java.util.function.ToDoubleFunction<Values> maxOf) {
        this.maxOf = maxOf;
        return this;
    }

    /** Shown = stored * factor (e.g. microseconds to milliseconds: 0.001). */
    Setting scale(double factor) {
        shown = (v, values) -> v * factor;
        stored = (v, values) -> v / factor;
        return this;
    }

    Setting convert(Convert shown, Convert stored) {
        this.shown = shown;
        this.stored = stored;
        return this;
    }

    Setting label(Label label) {
        this.label = label;
        return this;
    }

    String path() {
        return String.join(".", value.getPath());
    }

    static String trim(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        String s = String.format(java.util.Locale.ROOT, "%.3f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
