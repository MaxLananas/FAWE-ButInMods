package com.maxlananas.fawebim.fabric.client;

import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.platform.ConfigUi;
import com.maxlananas.fawebim.core.platform.Setting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The configuration screen {@code /fawebim} opens.
 *
 * <p>It is drawn from {@link ConfigUi}: the groups, the search and the value
 * checks come from the engine, and only the drawing lives here. The sidebar lists
 * the groups, every setting has a row with the editor of its type, and a change
 * is written to {@code config/fawebim.yml} as soon as it is made - the same thing
 * the chat form of the command does, so both stay in step.</p>
 */
public final class ConfigurationScreen extends Screen {

    private static final int PADDING = 14;
    private static final int SIDEBAR_WIDTH = 108;
    private static final int ROW_HEIGHT = 22;
    private static final int EDITOR_HEIGHT = 18;
    private static final int RESET_WIDTH = 50;
    private static final int TOGGLE_WIDTH = 64;
    private static final int NUMBER_WIDTH = 72;
    private static final int STEP_WIDTH = 18;
    private static final int PANEL_COLOUR = 0xE6141418;
    private static final int SIDEBAR_COLOUR = 0xE61C1C22;
    private static final int HEADER_COLOUR = 0xE6242430;
    private static final int ROW_HOVER = 0x28FFFFFF;
    private static final int ACCENT = 0xFF7FD1FF;
    private static final int LABEL = 0xFFE8E8EC;
    private static final int DIM = 0xFF9AA0A6;
    private static final int GOOD = 0xFF7CE38B;
    private static final int BAD = 0xFFFF8080;

    private final ConfigUi ui = new ConfigUi(Config.get());
    private final List<Row> rows = new ArrayList<>();

    private String group = "All";
    private String query = "";
    private int page;
    private int pageCount = 1;
    private int visibleCount;
    private String status = "";
    private boolean statusGood = true;
    private EditBox search;

    /** One visible setting, where it sits on screen and the widgets that edit it. */
    private static final class Row {

        private final Setting<?> setting;
        private final int y;
        private final List<AbstractWidget> widgets = new ArrayList<>();
        private EditBox box;
        private Button toggle;
        private boolean on;

        private Row(Setting<?> setting, int y) {
            this.setting = setting;
            this.y = y;
        }
    }

    public ConfigurationScreen() {
        super(Component.literal("FAWE-BIM configuration"));
    }

    @Override
    protected void init() {
        addSidebar();
        addSearch();
        addFooter();
        rebuildRows();
    }

    /** The groups, which is also how one group is shown on its own. */
    private void addSidebar() {
        int x = PADDING;
        int y = panelTop() + 28;
        List<String> names = new ArrayList<>();
        names.add("All");
        for (ConfigUi.Group entry : ui.groups()) {
            names.add(entry.name());
        }
        for (String name : names) {
            Button button = Button.builder(Component.literal(name), pressed -> {
                group = name;
                page = 0;
                rebuildRows();
            }).bounds(x, y, SIDEBAR_WIDTH, 20).build();
            addRenderableWidget(button);
            y += ROW_HEIGHT;
        }
    }

    /** The search box, which narrows the rows while it is typed in. */
    private void addSearch() {
        search = new EditBox(this.font, contentRight() - 160, panelTop() + 5, 160, EDITOR_HEIGHT,
                Component.literal("Search"));
        search.setHint(Component.literal("Search settings"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            page = 0;
            rebuildRows();
        });
        addRenderableWidget(search);
    }

    private void addFooter() {
        int y = panelBottom() - 24;
        int x = PADDING + SIDEBAR_WIDTH + 10;
        addRenderableWidget(Button.builder(Component.literal("Reload"), pressed -> {
            ui.reloadFromDisk();
            status("Reloaded " + ui.path(), true);
            rebuildRows();
        }).bounds(x, y, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), pressed -> {
            ui.saveToDisk();
            status("Saved to " + ui.path(), true);
        }).bounds(x + 74, y, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Previous"), pressed -> {
            page = Math.max(0, page - 1);
            rebuildRows();
        }).bounds(contentRight() - 134, y, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"), pressed -> {
            page = Math.min(pageCount - 1, page + 1);
            rebuildRows();
        }).bounds(contentRight() - 70, y, 70, 20).build());
    }

    /** Rebuilds the rows of the current group, search text and page. */
    private void rebuildRows() {
        for (Row row : rows) {
            for (AbstractWidget widget : row.widgets) {
                removeWidget(widget);
            }
        }
        rows.clear();

        List<Setting<?>> visible = ui.settings("All".equals(group) ? null : group, query);
        visibleCount = visible.size();
        int perPage = rowsPerPage();
        pageCount = Math.max(1, (visible.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pageCount - 1));
        int from = page * perPage;
        int to = Math.min(visible.size(), from + perPage);

        int y = rowsTop();
        for (Setting<?> setting : visible.subList(from, to)) {
            Row row = new Row(setting, y);
            buildRow(row);
            rows.add(row);
            y += ROW_HEIGHT;
        }
    }

    /** The editor of one setting: a switch, a number or a text field, plus a reset. */
    private void buildRow(Row row) {
        Setting<?> setting = row.setting;
        int editorRight = contentRight() - RESET_WIDTH - 6;
        switch (setting.kind()) {
            case BOOLEAN -> {
                Button toggle = Button.builder(Component.literal("On"), pressed -> applyRow(row, !row.on))
                        .bounds(editorRight - TOGGLE_WIDTH, row.y, TOGGLE_WIDTH, EDITOR_HEIGHT).build();
                row.toggle = toggle;
                row.widgets.add(toggle);
            }
            case INTEGER -> {
                Button minus = Button.builder(Component.literal("-"), pressed -> step(row, -1))
                        .bounds(editorRight - STEP_WIDTH - 4 - NUMBER_WIDTH - 4 - STEP_WIDTH, row.y,
                                STEP_WIDTH, EDITOR_HEIGHT).build();
                EditBox box = newField(editorRight - STEP_WIDTH - 4 - NUMBER_WIDTH, row.y, NUMBER_WIDTH,
                        setting, 12);
                Button plus = Button.builder(Component.literal("+"), pressed -> step(row, 1))
                        .bounds(editorRight - STEP_WIDTH, row.y, STEP_WIDTH, EDITOR_HEIGHT).build();
                row.box = box;
                row.widgets.add(minus);
                row.widgets.add(box);
                row.widgets.add(plus);
            }
            case TEXT -> {
                int width = Math.min(220, Math.max(80, editorRight - labelRight(row)));
                row.box = newField(editorRight - width, row.y, width, setting, 512);
                row.widgets.add(row.box);
            }
            default -> {
            }
        }
        row.widgets.add(Button.builder(Component.literal("Reset"), pressed -> resetRow(row))
                .bounds(contentRight() - RESET_WIDTH, row.y, RESET_WIDTH, EDITOR_HEIGHT).build());
        for (AbstractWidget widget : row.widgets) {
            addRenderableWidget(widget);
        }
        syncRow(row);
    }

    private EditBox newField(int x, int y, int width, Setting<?> setting, int maxLength) {
        EditBox box = new EditBox(this.font, x, y, width, EDITOR_HEIGHT, Component.literal(setting.key()));
        box.setMaxLength(maxLength);
        box.setValue(setting.value());
        return box;
    }

    /** Applies what a row holds: its switch, or the text of its field. */
    private void applyRow(Row row, Boolean toggleValue) {
        Setting<?> setting = row.setting;
        String value;
        if (toggleValue != null) {
            value = toggleValue ? "true" : "false";
        } else if (row.box != null) {
            value = row.box.getValue();
        } else {
            value = setting.value();
        }
        String error = ui.set(setting.key(), value);
        if (error == null) {
            status(setting.key() + " = " + setting.value(), true);
        } else {
            status(setting.key() + " expects " + ConfigUi.expectedOf(setting), false);
        }
        syncRow(row);
    }

    /** Steps a number, which is what the two small buttons do. */
    private void step(Row row, int delta) {
        try {
            int value = Integer.parseInt(row.setting.value().trim()) + delta;
            applyRow(row, null);
            ui.set(row.setting.key(), Integer.toString(value));
            status(row.setting.key() + " = " + row.setting.value(), true);
        } catch (NumberFormatException notANumber) {
            applyRow(row, null);
        }
        syncRow(row);
    }

    private void resetRow(Row row) {
        ui.reset(row.setting.key());
        status(row.setting.key() + " back to " + row.setting.defaultValue(), true);
        syncRow(row);
    }

    /** Makes the widgets of a row show what the setting holds now. */
    private void syncRow(Row row) {
        row.on = row.setting.value().equalsIgnoreCase("true");
        if (row.toggle != null) {
            row.toggle.setMessage(Component.literal(row.on ? "On" : "Off"));
        }
        if (row.box != null && !row.box.isFocused()) {
            row.box.setValue(row.setting.value());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
        graphics.fill(PADDING, panelTop(), this.width - PADDING, panelBottom(), PANEL_COLOUR);
        graphics.fill(PADDING, panelTop(), PADDING + SIDEBAR_WIDTH, panelBottom(), SIDEBAR_COLOUR);
        graphics.fill(PADDING + SIDEBAR_WIDTH, panelTop(), this.width - PADDING, panelTop() + 24, HEADER_COLOUR);

        graphics.drawString(this.font, "FAWE-BIM configuration", PADDING + 4, panelTop() - 11, ACCENT, true);
        graphics.drawString(this.font, group + " - " + visibleCount + " setting(s), page " + (page + 1)
                + "/" + pageCount, PADDING + SIDEBAR_WIDTH + 10, panelTop() + 8, LABEL, false);

        Row hovered = hovered(mouseX, mouseY);
        for (Row row : rows) {
            if (row == hovered) {
                graphics.fill(PADDING + SIDEBAR_WIDTH + 4, row.y - 2, this.width - PADDING - 4,
                        row.y + EDITOR_HEIGHT + 1, ROW_HOVER);
            }
            int colour = row.setting.value().equals(row.setting.defaultValue()) ? LABEL : ACCENT;
            graphics.drawString(this.font, row.setting.key(), PADDING + SIDEBAR_WIDTH + 10, row.y + 5,
                    colour, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        Row described = hovered != null ? hovered : (rows.isEmpty() ? null : rows.get(0));
        if (described != null) {
            graphics.drawString(this.font, described.setting.description(), PADDING + SIDEBAR_WIDTH + 10,
                    panelBottom() - 40, DIM, false);
            graphics.drawString(this.font, described.setting.path() + "   default "
                            + described.setting.defaultValue() + "   " + ConfigUi.expectedOf(described.setting),
                    PADDING + SIDEBAR_WIDTH + 10, panelBottom() - 30, DIM, false);
        }
        if (!status.isEmpty()) {
            graphics.drawString(this.font, status, PADDING + SIDEBAR_WIDTH + 10, panelBottom() - 13,
                    statusGood ? GOOD : BAD, false);
        }
    }

    private Row hovered(int mouseX, int mouseY) {
        if (mouseX < PADDING + SIDEBAR_WIDTH || mouseX > this.width - PADDING
                || mouseY < rowsTop() - 2 || mouseY > rowsBottom()) {
            return null;
        }
        for (Row row : rows) {
            if (mouseY >= row.y - 2 && mouseY <= row.y + EDITOR_HEIGHT + 1) {
                return row;
            }
        }
        return null;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            for (Row row : rows) {
                if (row.box != null && row.box.isFocused()) {
                    applyRow(row, null);
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // Text typed but not confirmed is applied on the way out, so closing the
        // screen with the mouse cannot lose a value.
        for (Row row : rows) {
            if (row.box != null && !row.box.getValue().equals(row.setting.value())) {
                ui.set(row.setting.key(), row.box.getValue());
            }
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        // Editing the settings of a single-player world does not pause it.
        return false;
    }

    private void status(String message, boolean good) {
        this.status = message;
        this.statusGood = good;
    }

    private int panelTop() {
        return PADDING + 16;
    }

    private int panelBottom() {
        return this.height - PADDING;
    }

    private int contentRight() {
        return this.width - PADDING - 8;
    }

    private int rowsTop() {
        return panelTop() + 30;
    }

    private int rowsBottom() {
        return panelBottom() - 48;
    }

    private int rowsPerPage() {
        return Math.max(1, (rowsBottom() - rowsTop()) / ROW_HEIGHT);
    }

    private int labelRight(Row row) {
        return PADDING + SIDEBAR_WIDTH + 10 + this.font.width(row.setting.key()) + 8;
    }
}
