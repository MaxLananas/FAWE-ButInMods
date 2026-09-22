package com.fawebutinmods.core.session;

import com.fawebutinmods.core.clipboard.BlockArrayClipboard;
import com.fawebutinmods.core.history.History;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.mask.Mask;
import com.fawebutinmods.core.pattern.Pattern;
import com.fawebutinmods.core.region.Region;
import com.fawebutinmods.core.region.RegionSelector;
import com.fawebutinmods.core.region.Selectors;
import com.fawebutinmods.core.transform.Transforms;
import com.fawebutinmods.core.world.World;

/**
 * Per-player WorldEdit session: selection, clipboard, history, brushes, tool
 * bindings, global mask/pattern/transform and the various limits — the same
 * state FAWE keeps in its {@code LocalSession}.
 */
public final class LocalSession {

    private RegionSelector selector;
    private TransformSet transformSet = new TransformSet();
    private ClipboardHolder clipboard;
    private final History history;
    private Mask mask;
    private Pattern pattern;
    private boolean placeAtPos1 = true;
    private boolean fastMode = true;
    private boolean superPickaxeEnabled = true;
    private int superPickaxeMode = 1; // 0 = single, 1 = area, 2 = recursive
    private int superPickaxeRadius = 1;
    private int maxBlocksChanged = -1;
    private int timeout = 20;
    private long maxBrushRadius = 10;
    private double maxBrushRange = 100;
    private int changeLimit = -1;
    private boolean sideEffectsLighting = true;
    private boolean sideEffectsNeighbors = true;
    private boolean sideEffectsEntities = true;
    private boolean disableOtherSideEffects = false;
    private int wandItemId = -1;
    private String lastFailedMessage;
    private boolean includeAir = false;
    private boolean tracing = false;
    private boolean cuiEnabled = true;
    private boolean drawSelection = false;
    private final java.util.Map<String, Object> bindings = new java.util.HashMap<>();
    private String toolBindingName;
    private BlockVector3 lastClickedPosition;
    private com.fawebutinmods.core.world.Direction lastClickedFace = com.fawebutinmods.core.world.Direction.NORTH;

    public LocalSession() {
        this.history = new History(20);
    }

    public RegionSelector getSelector(World world) {
        if (selector == null) {
            selector = newSelectors(world, "cuboid");
        }
        return selector;
    }

    public void setSelector(RegionSelector selector) {
        this.selector = selector;
    }

    public static RegionSelector newSelectors(World world, String type) {
        int minY = world == null ? -64 : world.minY();
        int maxY = world == null ? 319 : world.maxY();
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "cuboid" -> new Selectors.CuboidSelector(minY, maxY);
            case "extend" -> new Selectors.ExtendingCuboidSelector(minY, maxY);
            case "poly" -> new Selectors.Polygonal2DSelector(minY, maxY);
            case "ellipsoid" -> new Selectors.EllipsoidSelector(minY, maxY, false);
            case "sphere" -> new Selectors.EllipsoidSelector(minY, maxY, true);
            case "cyl", "cylinder" -> new Selectors.CylinderSelector(minY, maxY);
            case "convex" -> new Selectors.ConvexSelector(minY, maxY);
            default -> null;
        };
    }

    public Region getSelection(World world) {
        RegionSelector sel = getSelector(world);
        return sel.isDefined() ? sel.getRegion() : null;
    }

    public boolean isSelectionDefined(World world) {
        return getSelector(world).isDefined();
    }

    public History getHistory() {
        return history;
    }

    public TransformSet getTransformSet() {
        return transformSet;
    }

    public ClipboardHolder getClipboard() {
        return clipboard;
    }

    public void setClipboard(BlockArrayClipboard clipboard) {
        this.clipboard = new ClipboardHolder(clipboard);
    }

    public boolean hasClipboard() {
        return clipboard != null;
    }

    public Mask getMask() {
        return mask;
    }

    public void setMask(Mask mask) {
        this.mask = mask;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public void setPattern(Pattern pattern) {
        this.pattern = pattern;
    }

    public boolean shouldPlaceAtPos1() {
        return placeAtPos1;
    }

    public void togglePlace() {
        placeAtPos1 = !placeAtPos1;
    }

    public boolean isFastMode() {
        return fastMode;
    }

    public void setFastMode(boolean fastMode) {
        this.fastMode = fastMode;
    }

    public boolean isSuperPickaxeEnabled() {
        return superPickaxeEnabled;
    }

    public void setSuperPickaxeEnabled(boolean enabled) {
        this.superPickaxeEnabled = enabled;
    }

    public int getSuperPickaxeMode() {
        return superPickaxeMode;
    }

    public void setSuperPickaxeMode(int mode) {
        this.superPickaxeMode = mode;
    }

    public int getSuperPickaxeRadius() {
        return superPickaxeRadius;
    }

    public void setSuperPickaxeRadius(int radius) {
        this.superPickaxeRadius = radius;
    }

    public int getMaxBlocksChanged() {
        return maxBlocksChanged;
    }

    public void setMaxBlocksChanged(int maxBlocksChanged) {
        this.maxBlocksChanged = maxBlocksChanged;
    }

    public boolean hasBlockChangeLimit() {
        return maxBlocksChanged > 0;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public long getMaxBrushRadius() {
        return maxBrushRadius;
    }

    public void setMaxBrushRadius(long maxBrushRadius) {
        this.maxBrushRadius = maxBrushRadius;
    }

    public double getMaxBrushRange() {
        return maxBrushRange;
    }

    public void setMaxBrushRange(double maxBrushRange) {
        this.maxBrushRange = maxBrushRange;
    }

    public int getChangeLimit() {
        return changeLimit;
    }

    public void setChangeLimit(int changeLimit) {
        this.changeLimit = changeLimit;
    }

    public boolean hasChangeLimit() {
        return changeLimit > 0;
    }

    public boolean isSideEffectsLighting() {
        return sideEffectsLighting;
    }

    public boolean isSideEffectsNeighbors() {
        return sideEffectsNeighbors;
    }

    public boolean isSideEffectsEntities() {
        return sideEffectsEntities;
    }

    public void setSideEffects(boolean lighting, boolean neighbors, boolean entities) {
        this.sideEffectsLighting = lighting;
        this.sideEffectsNeighbors = neighbors;
        this.sideEffectsEntities = entities;
    }

    public void setDisableOtherSideEffects(boolean value) {
        this.disableOtherSideEffects = value;
    }

    public boolean isDisableOtherSideEffects() {
        return disableOtherSideEffects;
    }

    public int getWandItemId() {
        return wandItemId;
    }

    public void setWandItemId(int wandItemId) {
        this.wandItemId = wandItemId;
    }

    public boolean isIncludeAir() {
        return includeAir;
    }

    public void setIncludeAir(boolean includeAir) {
        this.includeAir = includeAir;
    }

    public boolean isTracing() {
        return tracing;
    }

    public void setTracing(boolean tracing) {
        this.tracing = tracing;
    }

    public boolean isCuiEnabled() {
        return cuiEnabled;
    }

    public void setCuiEnabled(boolean cuiEnabled) {
        this.cuiEnabled = cuiEnabled;
    }

    public boolean isDrawSelection() {
        return drawSelection;
    }

    public void setDrawSelection(boolean drawSelection) {
        this.drawSelection = drawSelection;
    }

    public java.util.Map<String, Object> getBindings() {
        return bindings;
    }

    public String getToolBindingName() {
        return toolBindingName;
    }

    public void setToolBindingName(String name) {
        this.toolBindingName = name;
    }

    public BlockVector3 getLastClickedPosition() {
        return lastClickedPosition;
    }

    public void setLastClickedPosition(BlockVector3 position) {
        this.lastClickedPosition = position;
    }

    public com.fawebutinmods.core.world.Direction getLastClickedFace() {
        return lastClickedFace;
    }

    public void setLastClickedFace(com.fawebutinmods.core.world.Direction face) {
        this.lastClickedFace = face;
    }

    public String getLastFailedMessage() {
        return lastFailedMessage;
    }

    public void setLastFailedMessage(String message) {
        this.lastFailedMessage = message;
    }

    /** Holds the transform chain applied by {@code /gtransform} and {@code /transform}. */
    public static final class TransformSet {

        private Transforms.Set transforms = new Transforms.Set();

        public Transforms.Set getTransforms() {
            return transforms;
        }

        public void setTransforms(Transforms.Set transforms) {
            this.transforms = transforms;
        }

        public void clear() {
            transforms = new Transforms.Set();
        }
    }
}
