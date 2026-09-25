package com.maxlananas.fawebim.core.session;

import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.history.History;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.region.RegionSelector;
import com.maxlananas.fawebim.core.region.Selectors;
import com.maxlananas.fawebim.core.transform.Transforms;
import com.maxlananas.fawebim.core.world.World;

/**
 * Per-player WorldEdit session: selection, clipboard, history, brushes, tool
 * bindings, global mask/pattern/transform and the various limits — the same
 * state FAWE keeps in its {@code LocalSession}.
 */
public final class LocalSession {

    private RegionSelector selector;
    private String defaultSelectorType = "cuboid";
    private TransformSet transformSet = new TransformSet();
    private ClipboardHolder clipboard;
    private final History history;
    private Mask mask;
    private Pattern pattern;
    private boolean placeAtPos1 = true;
    private boolean fastMode = false;
    private boolean superPickaxeEnabled = true;
    private int superPickaxeMode = 1; // 0 = single, 1 = area, 2 = recursive
    private int superPickaxeRadius = 1;
    private int maxBlocksChanged = com.maxlananas.fawebim.core.platform.Config.get().defaultChangeLimit;
    private int timeout = com.maxlananas.fawebim.core.platform.Config.get().timeout;
    private long maxBrushRadius = com.maxlananas.fawebim.core.platform.Config.get().defaultMaxBrushRadius;
    private double maxBrushRange = com.maxlananas.fawebim.core.platform.Config.get().maxBrushRange;
    private int changeLimit = -1;
    private SideEffectSet sideEffectSet = SideEffectSet.defaults();
    private int wandItemId = -1;
    private String lastFailedMessage;
    private boolean includeAir = false;
    private boolean tracing = false;
    private boolean cuiEnabled = true;
    private boolean drawSelection = false;
    private final java.util.Map<String, Object> bindings = new java.util.HashMap<>();
    private String toolBindingName;
    private Mask sourceMask;
    private int placementMode = PLACEMENT_FIRST;
    private boolean cancelled;
    private boolean tips;
    private boolean watchdog = true;
    private java.time.ZoneId timezone = java.time.ZoneId.systemDefault();
    private String ownerName = "console";
    private java.nio.file.Path activeSnapshot;
    private com.maxlananas.fawebim.core.clipboard.ListFilter listFilter =
            com.maxlananas.fawebim.core.clipboard.ListFilter.ALL;
    private Runnable pendingCommand;
    private String pendingDescription;
    private BlockVector3 lastClickedPosition;
    private com.maxlananas.fawebim.core.world.Direction lastClickedFace = com.maxlananas.fawebim.core.world.Direction.NORTH;

    /** Placement modes of {@code //placement}, matching WorldEdit's names. */
    public static final int PLACEMENT_FIRST = 0;
    public static final int PLACEMENT_LAST = 1;
    public static final int PLACEMENT_ORIGIN = 2;

    /**
     * The reorder mode of {@code //reorder}, which upstream deprecated the setter
     * of and FAWE always answers with {@code fast}: an edit is written in the
     * order it was generated whatever the command printed.
     */
    public static final String REORDER_NAME = "fast";

    public LocalSession() {
        this(new History(com.maxlananas.fawebim.core.platform.Config.get().historySize));
    }

    /** A session recording into the given history, shared when the config says so. */
    public LocalSession(History history) {
        this.history = history;
    }

    /** Name of the player this session belongs to, used for snapshots. */
    public String ownerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName == null || ownerName.isBlank() ? "console" : ownerName;
    }

    /**
     * Writes finished edits to the snapshot folder so they survive a restart.
     * Enabled by the session manager once the owner name is known.
     */
    public void enableSnapshots() {
        history.setRecordListener(record -> {
            // Every finished edit goes into the shared log, which is what
            // /history find, rollback and restore search.
            com.maxlananas.fawebim.core.history.EditLog.add(ownerName,
                    record.world == null ? lastWorldName : record.world, record);
            if (com.maxlananas.fawebim.core.platform.Config.get().snapshotsEnabled) {
                com.maxlananas.fawebim.core.history.Snapshots.saveAsync(record, ownerName);
            }
        });
    }

    /** Name of the world the session is editing, kept for the history log. */
    public String getLastWorldName() {
        return lastWorldName;
    }

    public void setLastWorldName(String value) {
        this.lastWorldName = value;
    }

    /** Which schematics {@code //schem list} shows; set by {@code /list}. */
    public com.maxlananas.fawebim.core.clipboard.ListFilter getListFilter() {
        return listFilter;
    }

    public void setListFilter(com.maxlananas.fawebim.core.clipboard.ListFilter listFilter) {
        this.listFilter = listFilter == null ? com.maxlananas.fawebim.core.clipboard.ListFilter.ALL : listFilter;
    }

    /** Snapshot the {@code /snapshot} sub-commands act on when none is named. */
    public java.nio.file.Path getActiveSnapshot() {
        return activeSnapshot;
    }

    public void setActiveSnapshot(java.nio.file.Path activeSnapshot) {
        this.activeSnapshot = activeSnapshot;
    }

    /** The mask {@code //replace} and friends apply to the blocks they read. */
    public Mask getSourceMask() {
        return sourceMask;
    }

    public void setSourceMask(Mask sourceMask) {
        this.sourceMask = sourceMask;
    }

    public int getPlacementMode() {
        return placementMode;
    }

    public void setPlacementMode(int placementMode) {
        this.placementMode = placementMode;
    }

    public String placementModeName() {
        return switch (placementMode) {
            case PLACEMENT_LAST -> "last";
            case PLACEMENT_ORIGIN -> "origin";
            default -> "first";
        };
    }

    /** {@code /cancel}: abort the next edit that checks the session state. */
    public void cancel() {
        cancelled = true;
    }

    public void clearCancel() {
        cancelled = false;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** The timezone used when snapshot dates are displayed. */
    public java.time.ZoneId getTimezone() {
        return timezone;
    }

    public void setTimezone(java.time.ZoneId timezone) {
        this.timezone = timezone == null ? java.time.ZoneId.systemDefault() : timezone;
    }

    public boolean isTips() {
        return tips;
    }

    public void setTips(boolean tips) {
        this.tips = tips;
    }

    /** {@code /watchdog}: whether edits are stopped when they run too long. */
    public boolean isWatchdogEnabled() {
        return watchdog;
    }

    public void setWatchdogEnabled(boolean watchdog) {
        this.watchdog = watchdog;
    }

    /** Commands that need {@code /confirm} before running. */
    public void setPendingCommand(Runnable command, String description) {
        this.pendingCommand = command;
        this.pendingDescription = description;
    }

    public boolean hasPendingCommand() {
        return pendingCommand != null;
    }

    public String pendingDescription() {
        return pendingDescription;
    }

    /** Runs the pending command, or returns false when there is none. */
    public boolean confirmPending() {
        Runnable command = pendingCommand;
        pendingCommand = null;
        pendingDescription = null;
        if (command == null) {
            return false;
        }
        command.run();
        return true;
    }

    public void clearPending() {
        pendingCommand = null;
        pendingDescription = null;
    }

    public RegionSelector getSelector(World world) {
        if (selector == null) {
            selector = newSelectors(world, defaultSelectorType);
        }
        return selector;
    }

    /** The selection shape a fresh session starts with, set by {@code //sel -d}. */
    public String getDefaultSelectorType() {
        return defaultSelectorType;
    }

    public void setDefaultSelectorType(String type) {
        this.defaultSelectorType = type == null || type.isEmpty() ? "cuboid" : type;
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

    /** The chunk clipboard {@code /anvil copy} fills; not the normal clipboard. */
    public com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard getAnvilClipboard() {
        return anvilClipboard;
    }

    public void setAnvilClipboard(com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard clipboard) {
        this.anvilClipboard = clipboard;
    }

    public ClipboardHolder getClipboard() {
        return clipboard;
    }

    public void setClipboard(BlockArrayClipboard clipboard) {
        // A null clipboard has to clear the holder: wrapping null would leave
        // hasClipboard() true while every read of the clipboard throws.
        this.clipboard = clipboard == null ? null : new ClipboardHolder(clipboard);
    }

    private com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard anvilClipboard;
    private boolean clipboardRandomRotation;
    private String lastWorldName = "world";
    private boolean clipboardDynamicRotation;

    /**
     * The clipboards {@code //schem loadall} collected. While it holds more than
     * one, pasting picks one at random, which is how FAWE's multi clipboard works.
     */
    public java.util.List<com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard> getClipboardPool() {
        return clipboardPool;
    }

    public void setClipboardPool(java.util.List<com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard> pool) {
        this.clipboardPool.clear();
        this.clipboardPool.addAll(pool);
    }

    public void addToClipboardPool(java.util.List<com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard> pool) {
        this.clipboardPool.addAll(pool);
    }

    public void clearClipboardPool() {
        this.clipboardPool.clear();
    }

    /** True when {@code //schem load -r} asked for a random rotation. */
    public boolean isClipboardRandomRotation() {
        return clipboardRandomRotation;
    }

    public void setClipboardRandomRotation(boolean random) {
        this.clipboardRandomRotation = random;
    }

    /** True when {@code //schem load -d} wants the rotation re-rolled per paste. */
    public boolean isClipboardDynamicRotation() {
        return clipboardDynamicRotation;
    }

    public void setClipboardDynamicRotation(boolean dynamic) {
        this.clipboardDynamicRotation = dynamic;
    }

    /** True when {@code loadall -r} asked for a fresh random rotation per paste. */
    public boolean isClipboardPoolDynamicRotation() {
        return poolDynamicRotation;
    }

    public void setClipboardPoolDynamicRotation(boolean dynamic) {
        this.poolDynamicRotation = dynamic;
    }

    public boolean isClipboardPoolRandomRotation() {
        return poolRandomRotation;
    }

    public void setClipboardPoolRandomRotation(boolean random) {
        this.poolRandomRotation = random;
    }

    /** The CraftScript {@code //.s} re-runs. */
    public String getLastScript() {
        return lastScript;
    }

    public void setLastScript(String script) {
        this.lastScript = script;
    }

    private String lastScript;

    private final java.util.List<com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard> clipboardPool =
            new java.util.ArrayList<>();
    private boolean poolRandomRotation;
    private boolean poolDynamicRotation;

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

    /** The side effects every edit of this session applies. */
    public SideEffectSet getSideEffectSet() {
        return sideEffectSet;
    }

    public void setSideEffectSet(SideEffectSet sideEffectSet) {
        this.sideEffectSet = sideEffectSet == null ? SideEffectSet.defaults() : sideEffectSet;
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

    public com.maxlananas.fawebim.core.world.Direction getLastClickedFace() {
        return lastClickedFace;
    }

    public void setLastClickedFace(com.maxlananas.fawebim.core.world.Direction face) {
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
