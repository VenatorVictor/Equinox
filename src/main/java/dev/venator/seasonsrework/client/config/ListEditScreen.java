package dev.venator.seasonsrework.client.config;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/** Edits a list setting one line per entry; lines that don't parse are red and keep Done off. */
final class ListEditScreen extends Screen {
    private static final int ROW = 22;

    private final SettingsScreen parent;
    private final Setting setting;
    private final List<String> lines;
    private final boolean editable;
    private int scroll, listX, listWidth, top, bottom, visible;
    private Button done;
    private boolean dirty;

    ListEditScreen(SettingsScreen parent, Setting setting, List<String> current, boolean editable) {
        super(Component.literal(setting.name()));
        this.parent = parent;
        this.setting = setting;
        this.lines = new ArrayList<>(current);
        this.editable = editable;
    }

    private boolean valid(String line) {
        return setting.value.getSpec() instanceof ModConfigSpec.ListValueSpec spec ? spec.testElement(line) : true;
    }

    private boolean allValid() {
        for (String line : lines) if (!valid(line)) return false;
        return true;
    }

    @Override
    protected void init() {
        dirty = false;
        listWidth = Math.min(420, width - 24);
        listX = (width - listWidth) / 2;
        top = 44;
        bottom = height - 34;
        visible = Math.max(1, (bottom - top) / ROW);
        int rowsWithAdd = lines.size() + (editable ? 1 : 0);
        scroll = Math.max(0, Math.min(scroll, rowsWithAdd - visible));

        for (int i = scroll; i < Math.min(rowsWithAdd, scroll + visible); i++) {
            int y = top + (i - scroll) * ROW;
            if (i == lines.size()) {
                addRenderableWidget(Button.builder(Component.literal(SettingsScreen.t("list.add")), b -> {
                    Object fresh = setting.value.getSpec() instanceof ModConfigSpec.ListValueSpec spec ? spec.getNewElementSupplier().get() : "";
                    lines.add(String.valueOf(fresh));
                    scroll = lines.size() + 1;
                    dirty = true;
                }).bounds(listX, y, 100, 20).build());
                continue;
            }
            int index = i;
            EditBox box = new EditBox(font, listX + 1, y + 1, listWidth - 26, 18, Component.literal(setting.name()));
            box.setMaxLength(256);
            box.setValue(lines.get(i));
            box.setTextColor(valid(lines.get(i)) ? 0xFFE0E0E0 : 0xFFFF5555);
            box.setResponder(text -> {
                lines.set(index, text);
                box.setTextColor(valid(text) ? 0xFFE0E0E0 : 0xFFFF5555);
                if (done != null) done.active = editable && allValid();
            });
            box.setEditable(editable);
            addRenderableWidget(box);
            Button remove = Button.builder(Component.literal("x"), b -> {
                lines.remove(index);
                dirty = true;
            }).bounds(listX + listWidth - 20, y, 20, 20).build();
            remove.active = editable;
            addRenderableWidget(remove);
        }

        int w = 100, y = height - 26;
        Button reset = Button.builder(Component.literal(SettingsScreen.t("list.defaults")), b -> {
            lines.clear();
            for (Object line : (List<?>) setting.value.getDefault()) lines.add(String.valueOf(line));
            scroll = 0;
            dirty = true;
        }).bounds(width / 2 - w * 3 / 2 - 4, y, w, 20).build();
        reset.active = editable;
        addRenderableWidget(reset);
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> minecraft.setScreen(parent)).bounds(width / 2 - w / 2, y, w, 20).build());
        done = Button.builder(CommonComponents.GUI_DONE, b -> finish()).bounds(width / 2 + w / 2 + 4, y, w, 20).build();
        done.active = editable && allValid();
        addRenderableWidget(done);
    }

    private void finish() {
        parent.edit(setting, List.copyOf(lines));
        minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();
        if (dirty) rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int next = Math.max(0, Math.min(lines.size() + 1 - visible, scroll - (int) Math.signum(scrollY)));
        if (next != scroll) {
            scroll = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        gfx.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        List<FormattedCharSequence> format = font.split(Component.literal(setting.format()).withStyle(ChatFormatting.GRAY), listWidth);
        for (int i = 0; i < Math.min(2, format.size()); i++) gfx.drawCenteredString(font, format.get(i), width / 2, 22 + i * 10, 0xFFA0A0A0);
        String count = SettingsScreen.t("entries", lines.size()) + (editable ? "" : " " + SettingsScreen.t("list.locked"));
        gfx.drawString(font, count, listX + listWidth - font.width(count), 8, 0xFFA0A0A0);
        if (!allValid()) gfx.drawString(font, SettingsScreen.t("list.invalid"), listX, 8, 0xFFFF5555);
    }

    @Override
    public void onClose() {
        if (editable && allValid()) finish();
        else minecraft.setScreen(parent);
    }
}
