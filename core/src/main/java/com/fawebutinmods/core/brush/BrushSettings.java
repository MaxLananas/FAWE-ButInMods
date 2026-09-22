package com.fawebutinmods.core.brush;

import com.fawebutinmods.core.pattern.Pattern;

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
