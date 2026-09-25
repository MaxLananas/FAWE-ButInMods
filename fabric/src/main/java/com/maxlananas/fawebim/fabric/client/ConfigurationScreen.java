package com.maxlananas.fawebim.fabric.client;

import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.platform.ConfigUi;
import com.maxlananas.fawebim.core.platform.Setting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
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
    private static final int HEADER_HEIGHT = 48;
    private static final int FOOTER_HEIGHT = 76;
    private static final int ROW_HEIGHT = 22;
    private static final int EDITOR_HEIGHT = 18;
    private static final int RESET_WIDTH = 50;
    private static final int TOGGLE_WIDTH = 64;
    private static final int NUMBER_WIDTH = 72;
    private static final int STEP_WIDTH = 18;
    private static final int PANEL_COLOUR = 0xF0161A21;
    private static final int PANEL_BORDER = 0xFF2C3340;
    private static final int SIDEBAR_COLOUR = 0xF01B2029;
    private static final int HEADER_COLOUR = 0xF01E2430;
    private static final int CARD = 0x30FFFFFF;
    private static final int CARD_ALT = 0x18FFFFFF;
    private static final int ROW_HOVER = 0x38FFFFFF;
    private static final int ACCENT = 0xFF6FC3FF;
    private static final int ACCENT_DIM = 0x806FC3FF;
    private static final int CHANGED = 0xFFFFC46B;
    private static final int LABEL = 0xFFE8EBF0;
    private static final int DIM = 0xFF98A2B3;
    private static final int GOOD = 0xFF7CE38B;
    private static final int BAD = 0xFFFF8080;

    /** The screen that opened this one, so closing returns to it. */
    private final Screen parent;
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
        this(null);
    }

    /** Opened from another screen - the mod list, for instance. */
    public ConfigurationScreen(Screen parent) {
        super(Component.literal("FAWE-BIM configuration"));
        this.parent = parent;
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
        int x = PADDING + 4;
        int y = rowsTop();
        List<String> names = new ArrayList<>();
        names.add("All");
        for (ConfigUi.Group entry : ui.groups()) {
            names.add(entry.name());
        }
        // The buttons share the height of the row area: they used to keep their
        // pitch on a short window and land on the footer buttons.
        int pitch = Math.min(ROW_HEIGHT + 2, Math.max(16, (rowsBottom() - rowsTop()) / names.size()));
        for (String name : names) {
            if (y + pitch - 2 > rowsBottom()) {
                break;
            }
            Button button = Button.builder(Component.literal(name), pressed -> {
                group = name;
                page = 0;
                rebuildRows();
            }).bounds(x, y, SIDEBAR_WIDTH - 8, pitch - 2).build();
            addRenderableWidget(button);
            y += pitch;
        }
    }

    /** The search box, which narrows the rows while it is typed in. */
    private void addSearch() {
        search = new EditBox(this.font, searchLeft(), panelTop() + 8, 160, EDITOR_HEIGHT,
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
        int y = panelBottom() - 26;
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
                Button toggle = Button.builder(Component.literal("Enabled"), pressed -> applyRow(row, !row.on))
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
            row.toggle.setMessage(Component.literal(row.on ? "Enabled" : "Disabled")
                    .withStyle(row.on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        if (row.box != null && !row.box.isFocused()) {
            row.box.setValue(row.setting.value());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // A dimmed world behind a raised panel, a header for what is shown and a
        // sidebar for the groups; the selected group carries an accent bar.
        // Bands rather than a gradient call: the frame stays on the plain fill
        // every screen has drawn since 1.21.1.
        for (int band = 0; band < 8; band++) {
            int from = this.height * band / 8;
            int to = this.height * (band + 1) / 8;
            graphics.fill(0, from, this.width, to, bandColour(band));
        }
        panel(graphics, PADDING, panelTop(), this.width - PADDING, panelBottom(), PANEL_COLOUR);
        panel(graphics, PADDING, panelTop(), PADDING + SIDEBAR_WIDTH, panelBottom(), SIDEBAR_COLOUR);
        int headerBottom = panelTop() + HEADER_HEIGHT - 8;
        graphics.fill(PADDING, panelTop(), this.width - PADDING, headerBottom, HEADER_COLOUR);
        graphics.fill(PADDING, headerBottom, this.width - PADDING, headerBottom + 1, ACCENT_DIM);

        graphics.drawString(this.font, "FAWE-BIM", PADDING + 12, panelTop() + 9, ACCENT, true);
        int titleX = PADDING + SIDEBAR_WIDTH + 14;
        int titleRoom = searchLeft() - 12 - titleX;
        graphics.drawString(this.font, clipped(group, titleRoom), titleX, panelTop() + 9, LABEL, true);
        graphics.drawString(this.font, clipped(visibleCount + " setting(s)   page " + (page + 1) + "/"
                + pageCount, titleRoom), titleX, panelTop() + 22, DIM, false);
        graphics.fill(PADDING + SIDEBAR_WIDTH - 5, headerBottom, PADDING + SIDEBAR_WIDTH - 4,
                panelBottom(), PANEL_BORDER);

        Row hovered = hovered(mouseX, mouseY);
        int cardLeft = PADDING + SIDEBAR_WIDTH + 6;
        int cardRight = this.width - PADDING - 6;
        boolean stripe = false;
        for (Row row : rows) {
            stripe = !stripe;
            graphics.fill(cardLeft, row.y - 3, cardRight, row.y + EDITOR_HEIGHT + 2,
                    row == hovered ? ROW_HOVER : (stripe ? CARD : CARD_ALT));
            boolean changed = !row.setting.value().equals(row.setting.defaultValue());
            graphics.fill(cardLeft, row.y - 3, cardLeft + 2, row.y + EDITOR_HEIGHT + 2,
                    changed ? CHANGED : ACCENT_DIM);
            graphics.fill(cardLeft + 2, row.y - 3, cardRight, row.y - 2, PANEL_BORDER);
            graphics.fill(cardLeft + 2, row.y + EDITOR_HEIGHT + 1, cardRight, row.y + EDITOR_HEIGHT + 2,
                    PANEL_BORDER);
            graphics.drawString(this.font, row.setting.key(), PADDING + SIDEBAR_WIDTH + 14, row.y + 5,
                    changed ? CHANGED : LABEL, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        int textX = PADDING + SIDEBAR_WIDTH + 10;
        Row described = hovered != null ? hovered : (rows.isEmpty() ? null : rows.get(0));
        graphics.fill(PADDING + SIDEBAR_WIDTH + 4, panelBottom() - 72, this.width - PADDING - 4,
                panelBottom() - 71, PANEL_BORDER);
        if (described != null) {
            graphics.drawString(this.font, clipped(described.setting.description(), this.width - PADDING - textX),
                    textX, panelBottom() - 66, DIM, false);
            graphics.drawString(this.font, clipped(described.setting.path() + "   default "
                            + described.setting.defaultValue() + "   " + ConfigUi.expectedOf(described.setting),
                    this.width - PADDING - textX), textX, panelBottom() - 54, DIM, false);
        }
        if (!status.isEmpty()) {
            graphics.fill(textX - 2, panelBottom() - 38, textX, panelBottom() - 36, statusGood ? GOOD : BAD);
            graphics.drawString(this.font, clipped(status, this.width - PADDING - textX),
                    textX + 6, panelBottom() - 40, statusGood ? GOOD : BAD, false);
        }
    }

    /** A filled panel with a one pixel border. */
    private void panel(GuiGraphics graphics, int x1, int y1, int x2, int y2, int fill) {
        graphics.fill(x1, y1, x2, y2, fill);
        graphics.fill(x1, y1, x2, y1 + 1, PANEL_BORDER);
        graphics.fill(x1, y2 - 1, x2, y2, PANEL_BORDER);
        graphics.fill(x1, y1, x1 + 1, y2, PANEL_BORDER);
        graphics.fill(x2 - 1, y1, x2, y2, PANEL_BORDER);
    }

    /** The colour of one band of the backdrop gradient, top to bottom. */
    private static int bandColour(int band) {
        int[] colours = {
                0xE00A0C11, 0xE00B0E14, 0xE00D1017, 0xE00F131A,
                0xE011161E, 0xE0131921, 0xE0141A24, 0xE0151A23,
        };
        return colours[Math.max(0, Math.min(colours.length - 1, band))];
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
    public boolean keyPressed(KeyEvent event) {
        // Enter, on the main row of keys or on the numpad, confirms the field
        // that holds the focus.
        if (event.key() == 257 || event.key() == 335) {
            for (Row row : rows) {
                if (row.box != null && row.box.isFocused()) {
                    applyRow(row, null);
                    return true;
                }
            }
        }
        return super.keyPressed(event);
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
        // The opener: the mod list when Mod Menu opened this screen, null when
        // the command did, which is the same close vanilla does.
        Minecraft.getInstance().setScreen(parent);
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

    private int searchLeft() {
        return contentRight() - 160;
    }

    /** A string cut to the room it has, so a long line never runs under a field. */
    private String clipped(String text, int room) {
        if (room <= 0 || this.font.width(text) <= room) {
            return text;
        }
        String cut = this.font.plainSubstrByWidth(text, Math.max(0, room - 6));
        return cut + "...";
    }

    private int rowsTop() {
        return panelTop() + HEADER_HEIGHT;
    }

    private int rowsBottom() {
        return panelBottom() - FOOTER_HEIGHT;
    }

    private int rowsPerPage() {
        return Math.max(1, (rowsBottom() - rowsTop()) / ROW_HEIGHT);
    }

    private int labelRight(Row row) {
        return PADDING + SIDEBAR_WIDTH + 10 + this.font.width(row.setting.key()) + 8;
    }
}
