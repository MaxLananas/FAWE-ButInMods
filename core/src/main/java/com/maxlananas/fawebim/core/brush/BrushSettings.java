package com.maxlananas.fawebim.core.brush;

import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.transform.Transform;

import java.util.LinkedHashMap;
import java.util.Map;

/** The tunables every brush shares (FAWE's {@code BrushSettings}). */
public final class BrushSettings {

    private Pattern fill;
    private int size;
    private int range = 100;
    private double opacity = 1;
    private boolean hollow;
    private boolean applyOnLeftClick;
    private boolean permissive;
    private boolean maskEnabled = true;
    private boolean fillEnabled = true;
    private final Map<String, Object> extra = new LinkedHashMap<>();
    private double height = 1;
    private int smoothCycles = 1;
    private boolean oneshot;
    private Mask traceMask;
    private Mask sourceMask;
    private int targetMode;
    private int targetOffset;
    private Transform transform;
    private com.maxlananas.fawebim.core.tool.Scroll scrollAction;
    private String scrollActionName = "";

    /** The mask this tool reads through, or null to use the session's. */
    public Mask getSourceMask() {
        return sourceMask;
    }

    public void setSourceMask(Mask sourceMask) {
        this.sourceMask = sourceMask;
    }

    /** The mask a tool trace stops at, or null for "any solid block". */
    public Mask getTraceMask() {
        return traceMask;
    }

    public void setTraceMask(Mask traceMask) {
        this.traceMask = traceMask;
    }

    /** See {@code ToolTarget.Mode} for the values. */
    public int getTargetMode() {
        return targetMode;
    }

    public void setTargetMode(int targetMode) {
        this.targetMode = targetMode;
    }

    public int getTargetOffset() {
        return targetOffset;
    }

    public void setTargetOffset(int targetOffset) {
        this.targetOffset = targetOffset;
    }

    public Transform getTransform() {
        return transform;
    }

    public void setTransform(Transform transform) {
        this.transform = transform;
    }

    /** What the mouse wheel changes while this brush is held. */
    public com.maxlananas.fawebim.core.tool.Scroll getScrollAction() {
        return scrollAction;
    }

    public void setScrollAction(com.maxlananas.fawebim.core.tool.Scroll scrollAction) {
        this.scrollAction = scrollAction;
    }

    /** The command line that built the scroll action, kept for brush presets. */
    public String getScrollActionName() {
        return scrollActionName;
    }

    public void setScrollActionName(String name) {
        this.scrollActionName = name == null ? "" : name;
    }

    public Pattern getFill() {
        return fill;
    }

    public void setFill(Pattern fill) {
        this.fill = fill;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int getRange() {
        return range;
    }

    public void setRange(int range) {
        this.range = range;
    }

    public double getOpacity() {
        return opacity;
    }

    public void setOpacity(double opacity) {
        this.opacity = opacity;
    }

    public boolean isHollow() {
        return hollow;
    }

    public void setHollow(boolean hollow) {
        this.hollow = hollow;
    }

    public boolean isApplyOnLeftClick() {
        return applyOnLeftClick;
    }

    public void setApplyOnLeftClick(boolean value) {
        this.applyOnLeftClick = value;
    }

    public boolean isPermissive() {
        return permissive;
    }

    public void setPermissive(boolean permissive) {
        this.permissive = permissive;
    }

    public boolean isMaskEnabled() {
        return maskEnabled;
    }

    public void setMaskEnabled(boolean maskEnabled) {
        this.maskEnabled = maskEnabled;
    }

    public boolean isFillEnabled() {
        return fillEnabled;
    }

    public void setFillEnabled(boolean fillEnabled) {
        this.fillEnabled = fillEnabled;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public int getSmoothCycles() {
        return smoothCycles;
    }

    public void setSmoothCycles(int smoothCycles) {
        this.smoothCycles = smoothCycles;
    }

    /** {@code -h} makes the brush a one-shot: it unbinds after use. */
    public boolean isOneshot() {
        return oneshot;
    }

    public void setOneshot(boolean oneshot) {
        this.oneshot = oneshot;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("radius=").append(size);
        if (fill != null) {
            sb.append(" fill=").append(fill.getClass().getSimpleName());
        }
        if (hollow) {
            sb.append(" hollow");
        }
        return sb.toString();
    }
}
