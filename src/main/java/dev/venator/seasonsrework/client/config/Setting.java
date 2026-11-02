package dev.venator.seasonsrework.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One row of the config screen: a config value plus how to show it (tab, name, description, cost, units). */
public final class Setting {
    /** Which file holds it, which decides who may change it. */
    public enum Side {
        CLIENT("Your setting (this computer only)", "config/seasonsrework-client.toml"),
        COMMON("Shared setting (every world on this computer; on a server, the server's file decides)", "config/seasonsrework-common.toml"),
        SERVER("World setting (each world has its own; on a server, the server's file decides)", "<world>/serverconfig/seasonsrework-server.toml");

        final String label, file;

        Side(String label, String file) {
            this.label = label;
            this.file = file;
        }
    }

    /** How much it costs to have it on or set high (the colored mark in front of the name). */
    public enum Cost {
        NONE("None", "little to none", 0xFF4CAF50),
        LOW("Low", "low", 0xFFC0CA33),
        MEDIUM("Medium", "medium", 0xFFFFA000),
        HIGH("High", "high", 0xFFE53935),
        SAVES("Saves", "lowers the cost of other settings", 0xFF42A5F5);

        final String key, label;
        final int color;

        Cost(String key, String label, int color) {
            this.key = key;
            this.label = label;
            this.color = color;
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

    final ModConfigSpec.ConfigValue<?> value;
    final Side side;
    final String name;
    String tab = "", heading;
    String description = "";
    Cost cost = Cost.NONE;
    String costNote;
    String applies;
    Kind kind;
    // CHOICE: stored value -> label, in order.
    final Map<Object, String> choices = new LinkedHashMap<>();
    // SLIDER, in shown units.
    double min, max, step = 1;
    java.util.function.ToDoubleFunction<Values> maxOf;
    Convert shown = (v, values) -> v, stored = (v, values) -> v;
    Label label = (v, values) -> trim(v);
    // LIST: format line shown above the entries.
    String format = "";

    Setting(ModConfigSpec.ConfigValue<?> value, Side side, String name) {
        this.value = value;
        this.side = side;
        this.name = name;
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

    Setting desc(String... lines) {
        description = String.join("\n", lines);
        return this;
    }

    Setting cost(Cost cost, String note) {
        this.cost = cost;
        this.costNote = note;
        return this;
    }

    Setting applies(String applies) {
        this.applies = applies;
        return this;
    }

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

    Setting format(String format) {
        this.format = format;
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
