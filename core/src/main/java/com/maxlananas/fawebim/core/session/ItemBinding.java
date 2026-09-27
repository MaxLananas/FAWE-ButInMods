package com.maxlananas.fawebim.core.session;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.tool.Tool;

/**
 * What an item is bound to, as FAWE binds it: a tool, or a brush for each
 * click - the primary one fired by the right click, the secondary one by the
 * left click.
 *
 * <p>FAWE's item holds one tool, its brush tool being one: binding a tool to
 * an item takes its brushes off, and binding a brush takes its tool off. Each
 * item keeps its own, so a sphere brush on the shovel and a smooth brush on the
 * hoe are both there to use.</p>
 *
 * <p>Server thread only, like the session.</p>
 */
public final class ItemBinding {

    private Tool tool;
    private Brush primary;
    private Brush secondary;
    private String brushLine;

    ItemBinding() {
    }

    public Tool tool() {
        return tool;
    }

    /** Binds a tool, which takes the brushes off. */
    public void setTool(Tool tool) {
        this.tool = tool;
        if (tool != null) {
            primary = null;
            secondary = null;
            brushLine = null;
        }
    }

    /** The brush of the right click. */
    public Brush primary() {
        return primary;
    }

    /**
     * Binds the brush of the right click, which takes the tool off.
     *
     * @param line the command that built it, for a preset to save
     */
    public void setPrimary(Brush brush, String line) {
        primary = brush;
        brushLine = brush == null ? null : line;
        if (brush != null) {
            tool = null;
        }
    }

    /** The brush of the left click. */
    public Brush secondary() {
        return secondary;
    }

    /** Binds the brush of the left click, which takes the tool off. */
    public void setSecondary(Brush brush) {
        secondary = brush;
        if (brush != null) {
            tool = null;
        }
    }

    /** The command that built the primary brush, or null. */
    public String brushLine() {
        return brushLine;
    }

    /** Whether a brush is bound to either click. */
    public boolean hasBrush() {
        return primary != null || secondary != null;
    }

    public boolean isEmpty() {
        return tool == null && primary == null && secondary == null;
    }
}
