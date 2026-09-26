package com.maxlananas.fawebim.core.tool;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.actor.Navigation;
import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.command.Ctx;
import com.maxlananas.fawebim.core.command.Parsers;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The WorldEdit/FAWE tools.
 *
 * <p>Each tool is bound to the item the player is holding when {@code /tool}
 * runs, exactly like upstream: the binding is stored per session and the Fabric
 * adapter calls {@link #onLeftClick} / {@link #onRightClick} from the item and
 * block callbacks.</p>
 */
public final class Tools {

    private Tools() {
    }

    /**
     * The names {@code /tool} accepts, in the order they are offered. A name is
     * only listed when {@link #create} can build it.
     */
    public static final java.util.List<String> NAMES = java.util.List.of(
            "none", "tree", "repl", "cycler", "floodfill", "info", "farwand", "navwand", "lrbuild",
            "stacker", "deltree", "brush", "selwand", "featureplacer", "structureplacer", "flood", "warwand");

    /**
     * The tool {@code /tool <name> ...} binds, built from the arguments the
     * tool takes - WorldEdit's: the pattern of {@code repl}, the two patterns
     * of {@code lrbuild}, the range and mask of {@code stacker} - or null for a
     * name that is not a tool. An argument that does not parse is refused
     * here, before anything is bound.
     */
    public static Tool create(String name, Ctx ctx) {
        String key = name.toLowerCase(Locale.ROOT);
        return switch (key) {
            // /tool tree <type> names the tree the tool plants.
            case "tree" -> new TreeTool(ctx.arg(1, "tree"));
            case "repl", "replace" -> new ReplaceTool(Parsers.pattern(required(ctx, 1, "repl <pattern>"), ctx));
            case "cycler" -> new CyclerTool();
            case "floodfill", "flood-fill", "flood" -> FloodFillTool.of(ctx);
            case "info", "inspect" -> new InfoTool();
            case "farwand", "warwand" -> new FarWandTool();
            case "navwand", "navigation", "navigationwand" -> new NavigationWandTool();
            case "lrbuild", "lr-build" -> new LRBuildTool(
                    Parsers.pattern(required(ctx, 1, LRBuildTool.USAGE), ctx),
                    Parsers.pattern(required(ctx, 2, LRBuildTool.USAGE), ctx));
            case "stacker" -> StackerTool.of(ctx);
            case "deltree" -> new DelTreeTool();
            case "brush" -> new BrushTool();
            case "selwand" -> new SelectWandTool();
            case "featureplacer", "featuretool" ->
                    new FeaturePlacerTool(required(ctx, 1, "featureplacer <feature>"));
            case "structureplacer", "structuretool" ->
                    new StructurePlacerTool(required(ctx, 1, "structureplacer <structure>"));
            default -> null;
        };
    }

    private static String required(Ctx ctx, int index, String usage) {
        String value = ctx.arg(index, null);
        if (value == null) {
            throw CommandRegistry.error("Usage: /tool " + usage);
        }
        return value;
    }

    /**
     * Where the item to bind to sits among the arguments of {@code /tool}: right
     * after the arguments the tool takes, so {@code /tool repl stone} binds a
     * stone replacer to the held item and {@code /tool repl stone stick} binds
     * it to sticks.
     */
    public static int targetArgument(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "tree", "repl", "replace", "featureplacer", "featuretool", "structureplacer", "structuretool" -> 2;
            case "floodfill", "flood-fill", "flood", "lrbuild", "lr-build", "stacker" -> 3;
            default -> 1;
        };
    }

    public static void bind(LocalSession session, Tool tool, Actor actor, String item) {
        session.getBindings().put("tool", tool);
        session.getBindings().put("tool-item", item == null || item.isEmpty() ? actor.heldItem() : item);
    }

    public static void clear(LocalSession session) {
        session.getBindings().remove("tool");
        session.getBindings().remove("tool-item");
    }

    public static Tool current(LocalSession session) {
        Object tool = session.getBindings().get("tool");
        return tool instanceof Tool value ? value : null;
    }

    private static final Tool NAVIGATION_WAND = new NavigationWandTool();

    /**
     * The tool a click with {@code item} uses: the one {@code /tool} bound to
     * that item, else the navigation wand when the item is the configured
     * navigation wand - WorldEdit's compass - else none.
     */
    public static Tool forItem(LocalSession session, String item) {
        if (item == null) {
            return null;
        }
        Tool tool = current(session);
        if (tool != null && item.equals(session.getBindings().get("tool-item"))) {
            return tool;
        }
        return item.equals(com.maxlananas.fawebim.core.platform.Config.get().navigationWandItem)
                ? NAVIGATION_WAND : null;
    }

    /** The pattern argument of a tool, defaulting to the global pattern. */
    static Pattern pattern(Actor actor, Pattern fallback) {
        Pattern global = actor.session().getPattern();
        return global == null ? fallback : global;
    }

    static Pattern stonePattern() {
        return new Patterns.Single(BlockState.registry().defaultState("minecraft:stone"));
    }

    // ------------------------------------------------------------------- tools

    /**
     * {@code /tool tree [type]}, WorldEdit's tree planter: a right click grows
     * a tree on the clicked block, from the block above it.
     */
    public static final class TreeTool implements Tool {

        private final String treeType;

        TreeTool(String treeType) {
            this.treeType = treeType == null || treeType.isEmpty() ? "tree" : treeType;
        }

        @Override
        public String name() {
            return "tree";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            if (!context.aimsAtBlock()) {
                context.message(Msg.error("No block in sight"));
                return true;
            }
            boolean ok = context.actor.world().generateTree(context.position.add(0, 1, 0), treeType, new Random());
            context.message(ok ? Msg.success("Planted a " + treeType)
                    : Msg.error("A " + treeType + " does not fit there"));
            return true;
        }

        @Override
        public String describe() {
            return "tree (" + treeType + ")";
        }
    }

    /**
     * The session a tool edits through: the one the caller handed over, which the
     * caller closes, or a session of the tool's own.
     */
    private static EditSession open(Tool.ToolContext context, String description) {
        return context.hasSession() ? context.session
                : new EditSession(context.actor.world(), context.actor.session(), description);
    }

    /** Writes the tool's edit out, and ends it when the session is the tool's own. */
    private static void finish(Tool.ToolContext context, EditSession session) {
        if (session == context.session) {
            session.flushQueue();
        } else {
            session.close();
        }
    }

    /**
     * {@code /tool repl <pattern>}, WorldEdit's block replacer: a right click
     * turns the clicked block into the pattern, a left click makes the clicked
     * block the pattern, with its data - the items of a chest, the text of a
     * sign - which every later right click writes again.
     */
    public static final class ReplaceTool implements Tool {

        private Pattern pattern;
        /** The picked block and its data; the data is written only where that block is. */
        private int pickedState = -1;
        private com.maxlananas.fawebim.core.util.NbtCompound pickedData;

        ReplaceTool(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public String name() {
            return "repl";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            if (!context.aimsAtBlock()) {
                context.message(Msg.error("No block in sight"));
                return true;
            }
            BlockVector3 at = context.position;
            int state = pattern.apply(at.x(), at.y(), at.z());
            EditSession session = open(context, "tool repl");
            try {
                session.setBlock(at.x(), at.y(), at.z(), state);
                if (pickedData != null && state == pickedState) {
                    session.setBlockEntity(at.x(), at.y(), at.z(), pickedData.clone());
                }
            } finally {
                finish(context, session);
            }
            return true;
        }

        @Override
        public boolean onLeftClick(ToolContext context) {
            BlockVector3 at = context.position;
            com.maxlananas.fawebim.core.world.World world = context.actor.world();
            pickedState = world.getBlock(at.x(), at.y(), at.z());
            pickedData = world.getBlockEntity(at.x(), at.y(), at.z());
            pattern = new Patterns.Single(pickedState);
            context.message(Msg.result("Replacer", "now places "
                    + Msg.value(BlockState.registry().describe(pickedState)).raw()));
            return true;
        }

        @Override
        public String describe() {
            return "replacer";
        }
    }

    /**
     * {@code /tool cycler}, WorldEdit's data cycler: a right click moves the
     * clicked block's selected property to its next value, a left click
     * selects the next property of the block.
     */
    public static final class CyclerTool implements Tool {

        private String property;

        @Override
        public String name() {
            return "cycler";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            BlockStateRegistry registry = BlockState.registry();
            BlockVector3 at = context.position;
            int current = context.actor.world().getBlock(at.x(), at.y(), at.z());
            java.util.Map<String, String> properties = registry.properties(current);
            if (properties.isEmpty()) {
                context.message(Msg.error("That block has no property to cycle"));
                return true;
            }
            if (property == null || !properties.containsKey(property)) {
                property = properties.keySet().iterator().next();
            }
            List<String> values = registry.propertyDefs(current).get(property);
            if (values == null || values.isEmpty()) {
                context.message(Msg.error("That block has no property to cycle"));
                return true;
            }
            String value = values.get((values.indexOf(properties.get(property)) + 1) % values.size());
            int next = registry.withProperty(current, property, value);
            if (next < 0) {
                context.message(Msg.error("That block has no property to cycle"));
                return true;
            }
            EditSession session = open(context, "tool cycler");
            try {
                session.setBlock(at.x(), at.y(), at.z(), next);
            } finally {
                finish(context, session);
            }
            context.message(Msg.keyValue(property, value));
            return true;
        }

        @Override
        public boolean onLeftClick(ToolContext context) {
            BlockVector3 at = context.position;
            List<String> names = new java.util.ArrayList<>(BlockState.registry().properties(
                    context.actor.world().getBlock(at.x(), at.y(), at.z())).keySet());
            if (names.isEmpty()) {
                context.message(Msg.error("That block has no property to cycle"));
                return true;
            }
            property = names.get((names.indexOf(property) + 1) % names.size());
            context.message(Msg.result("Cycler", "now cycles " + Msg.value(property).raw()));
            return true;
        }

        @Override
        public String describe() {
            return "cycler";
        }
    }

    /**
     * {@code /tool floodfill [pattern] [range]}: WorldEdit's flood fill tool,
     * which turns the clicked block and the blocks of its type joined to it,
     * within the range, into the pattern - the walk of the recursive super
     * pickaxe, under the same ceiling. A click on air fills nothing.
     */
    public static final class FloodFillTool implements Tool {

        private Pattern pattern;
        private double range = 5;

        /** The tool {@code /tool floodfill} describes; the range is refused above the ceiling. */
        static FloodFillTool of(Ctx ctx) {
            FloodFillTool tool = new FloodFillTool();
            if (ctx.args().size() > 1) {
                tool.pattern = com.maxlananas.fawebim.core.command.Parsers.pattern(ctx.arg(1), ctx);
            }
            tool.range = ctx.args().size() > 2 ? ctx.intArg(2) : SuperPickaxe.ceiling();
            SuperPickaxe.checkRange(tool.range);
            return tool;
        }

        public void setPattern(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public String name() {
            return "floodfill";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            Pattern fill = pattern != null ? pattern : pattern(context.actor, stonePattern());
            BlockVector3 origin = context.position;
            // The ceiling is read again at the click: it may have been lowered
            // since the tool was bound.
            double reach = Math.min(range, SuperPickaxe.ceiling());
            int[] targets = SuperPickaxe.targets(context.actor.world(), SuperPickaxe.RECURSIVE, Math.max(0, reach),
                    origin.x(), origin.y(), origin.z());
            if (targets.length == 0) {
                return true;
            }
            EditSession session = open(context, "tool floodfill");
            int changed = 0;
            try {
                for (int i = 0; i < targets.length; i += 3) {
                    int x = targets[i];
                    int y = targets[i + 1];
                    int z = targets[i + 2];
                    if (session.setBlock(x, y, z, fill.apply(x, y, z))) {
                        changed++;
                    }
                }
            } finally {
                finish(context, session);
            }
            context.message(Msg.success("Filled " + Msg.formatNumber(changed) + " block(s)"));
            return true;
        }

        @Override
        public String describe() {
            return "flood fill";
        }
    }

    /** {@code /tool info} — prints information about the clicked block. */
    public static final class InfoTool implements Tool {

        @Override
        public String name() {
            return "info";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            BlockStateRegistry registry = BlockState.registry();
            int state = context.actor.world().getBlock(context.position.x(), context.position.y(), context.position.z());
            context.message(Msg.keyValue("Block", registry.describe(state)));
            context.message(Msg.keyValue("Position", context.position.toString()));
            Mask mask = context.actor.session().getMask();
            if (mask != null) {
                context.message(Msg.keyValue("Mask match", mask.test(context.position)));
            }
            return true;
        }

        @Override
        public String describe() {
            return "info";
        }
    }

    /**
     * Sets a corner of the selection to the block a click is about, the way
     * the selection wand does. A click with nothing in sight selects nothing.
     */
    private static boolean selectCorner(Tool.ToolContext context, boolean primary) {
        if (!context.aimsAtBlock()) {
            context.message(Msg.error("No block in sight"));
            return true;
        }
        com.maxlananas.fawebim.core.region.RegionSelector selector =
                context.actor.session().getSelector(context.actor.world());
        if (primary) {
            selector.selectPrimary(context.position, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
        } else {
            selector.selectSecondary(context.position, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
        }
        context.message(Msg.result(primary ? "Position 1" : "Position 2",
                "set to " + Msg.value(context.position).raw()));
        context.actor.updateSelectionOutline();
        return true;
    }

    /**
     * {@code /tool farwand}, WorldEdit's long range wand: the corners are the
     * blocks in sight, as far as the brush range, a left click setting the
     * first and a right click the second.
     */
    public static final class FarWandTool implements Tool {

        @Override
        public String name() {
            return "farwand";
        }

        @Override
        public boolean onLeftClick(ToolContext context) {
            return selectCorner(context, true);
        }

        @Override
        public boolean onSwing(ToolContext context) {
            return selectCorner(context, true);
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            return selectCorner(context, false);
        }

        @Override
        public String describe() {
            return "far wand";
        }
    }

    /**
     * {@code /tool navwand}, WorldEdit's navigation wand: a left click is
     * {@code /jumpto} on the block in sight, a right click is {@code /thru}.
     */
    public static final class NavigationWandTool implements Tool {

        /** WorldEdit's navigation wand passes through 40 blocks of wall. */
        private static final int THRU_RANGE = 40;

        @Override
        public String name() {
            return "navwand";
        }

        @Override
        public boolean onLeftClick(ToolContext context) {
            return jump(context);
        }

        @Override
        public boolean onSwing(ToolContext context) {
            return jump(context);
        }

        private static boolean jump(ToolContext context) {
            if (!context.aimsAtBlock()) {
                context.message(Msg.error("No block in sight"));
            } else if (!Navigation.findFreePosition(context.actor, context.position)) {
                context.message(Msg.error("No free space above " + Msg.value(context.position).raw()));
            }
            return true;
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            if (!Navigation.passThroughForwardWall(context.actor, THRU_RANGE)) {
                context.message(Msg.error("No free space through the wall in front of you"));
            }
            return true;
        }

        @Override
        public String describe() {
            return "navigation wand";
        }
    }

    /**
     * {@code /tool lrbuild <left> <right>}, WorldEdit's long range building
     * tool: a click puts its pattern against the face of the block in sight,
     * or, when the pattern gives air there, clears that block.
     */
    public static final class LRBuildTool implements Tool {

        static final String USAGE = "lrbuild <left-click pattern> <right-click pattern>";

        private final Pattern left;
        private final Pattern right;

        LRBuildTool(Pattern left, Pattern right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public String name() {
            return "lrbuild";
        }

        @Override
        public boolean onLeftClick(ToolContext context) {
            return build(context, left);
        }

        @Override
        public boolean onSwing(ToolContext context) {
            return build(context, left);
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            return build(context, right);
        }

        private static boolean build(ToolContext context, Pattern pattern) {
            if (!context.aimsAtBlock()) {
                context.message(Msg.error("No block in sight"));
                return true;
            }
            BlockVector3 target = context.position;
            BlockVector3 at = BlockState.registry().isAir(pattern.apply(target.x(), target.y(), target.z()))
                    ? target : target.add(context.face.toVector());
            EditSession session = open(context, "tool lrbuild");
            try {
                session.setBlock(at.x(), at.y(), at.z(), pattern.apply(at.x(), at.y(), at.z()));
            } finally {
                finish(context, session);
            }
            return true;
        }

        @Override
        public String describe() {
            return "long range builder";
        }
    }

    /**
     * {@code /tool stacker [range] [mask]}, WorldEdit's block stacker: a right
     * click repeats the clicked block, its data included, away from the
     * clicked face, as long as the next block matches the mask - by default
     * while it is air - and at most {@code range} times.
     */
    public static final class StackerTool implements Tool {

        private final int range;
        /** Null for WorldEdit's default, {@code !#existing}: stack into air only. */
        private final Mask mask;

        private StackerTool(int range, Mask mask) {
            this.range = range;
            this.mask = mask;
        }

        static StackerTool of(Ctx ctx) {
            int range = ctx.intArg(1, 10, 1, com.maxlananas.fawebim.core.platform.Config.get().maxBrushRange,
                    "stack range");
            Mask mask = ctx.args().size() > 2 ? Parsers.mask(ctx.arg(2), ctx) : null;
            return new StackerTool(range, mask);
        }

        @Override
        public String name() {
            return "stacker";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            if (!context.aimsAtBlock()) {
                context.message(Msg.error("No block in sight"));
                return true;
            }
            com.maxlananas.fawebim.core.world.World world = context.actor.world();
            BlockVector3 from = context.position;
            int state = world.getBlock(from.x(), from.y(), from.z());
            com.maxlananas.fawebim.core.util.NbtCompound data = world.getBlockEntity(from.x(), from.y(), from.z());
            BlockVector3 step = context.face.toVector();
            int x = from.x();
            int y = from.y();
            int z = from.z();
            EditSession session = open(context, "tool stacker");
            try {
                for (int i = 0; i < range; i++) {
                    x += step.x();
                    y += step.y();
                    z += step.z();
                    boolean open = mask == null ? BlockState.registry().isAir(session.getBlock(x, y, z))
                            : mask.test(x, y, z);
                    if (!open) {
                        break;
                    }
                    session.setBlock(x, y, z, state);
                    if (data != null) {
                        session.setBlockEntity(x, y, z, data.clone());
                    }
                }
            } finally {
                finish(context, session);
            }
            return true;
        }

        @Override
        public String describe() {
            return "stacker";
        }
    }

    /** {@code /tool deltree}. */
    public static final class DelTreeTool implements Tool {

        @Override
        public String name() {
            return "deltree";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            EditSession session = new EditSession(context.actor.world(), context.actor.session(), "tool deltree");
            int changed;
            try {
                changed = com.maxlananas.fawebim.core.function.Operations.removeTree(
                        context.actor.world(), session, context.position);
            } finally {
                session.close();
            }
            context.message(Msg.success("Removed " + Msg.formatNumber(changed) + " block(s)"));
            return true;
        }

        @Override
        public String describe() {
            return "delete tree";
        }
    }

    /** {@code /tool brush} — uses the session's brush on right-click. */
    public static final class BrushTool implements Tool {

        @Override
        public String name() {
            return "brush";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            var brush = com.maxlananas.fawebim.core.brush.BrushFactory.current(context.actor.session());
            if (brush == null) {
                context.message(Msg.error("No brush bound. Use /brush sphere 5 stone"));
                return false;
            }
            EditSession session = new EditSession(context.actor.world(), context.actor.session(), "brush");
            int changed;
            try {
                changed = com.maxlananas.fawebim.core.brush.Brushes.apply(brush, session, context.position,
                        context.actor);
            } finally {
                session.close();
            }
            context.message(Msg.success("Brush changed " + Msg.formatNumber(changed) + " block(s)"));
            return true;
        }

        @Override
        public String describe() {
            return "brush";
        }
    }

    /** {@code /tool selwand}: the selection wand bound to another item. */
    public static final class SelectWandTool implements Tool {

        @Override
        public String name() {
            return "selwand";
        }

        @Override
        public boolean onLeftClick(ToolContext context) {
            return selectCorner(context, true);
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            return selectCorner(context, false);
        }

        @Override
        public String describe() {
            return "selection wand";
        }
    }

    /**
     * {@code /tool featureplacer} — places a worldgen feature (a tree, an ore
     * vein, a geode...) where the player clicks.
     */
    public static final class FeaturePlacerTool implements Tool {

        private final String feature;

        public FeaturePlacerTool(String feature) {
            this.feature = feature;
        }

        @Override
        public String name() {
            return "featureplacer";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            if (feature == null || feature.isEmpty()) {
                context.message(Msg.error("No feature set: use /tool featureplacer <feature>"));
                return false;
            }
            if (!context.actor.world().generateFeature(context.position, feature, new java.util.Random())) {
                context.message(Msg.error("Unknown feature '" + feature + "'"));
                return false;
            }
            context.message(Msg.success("Placed feature " + feature));
            return true;
        }

        @Override
        public String describe() {
            return "feature placer (" + (feature == null ? "unset" : feature) + ")";
        }
    }

    /**
     * {@code /tool structureplacer} — generates a worldgen structure (a village,
     * a shipwreck...) where the player clicks.
     */
    public static final class StructurePlacerTool implements Tool {

        private final String structure;

        public StructurePlacerTool(String structure) {
            this.structure = structure;
        }

        @Override
        public String name() {
            return "structureplacer";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            if (structure == null || structure.isEmpty()) {
                context.message(Msg.error("No structure set: use /tool structureplacer <structure>"));
                return false;
            }
            if (!context.actor.world().generateStructure(structure, context.position, new java.util.Random())) {
                context.message(Msg.error("Unknown structure '" + structure + "'"));
                return false;
            }
            context.message(Msg.success("Generated structure " + structure));
            return true;
        }

        @Override
        public String describe() {
            return "structure placer (" + (structure == null ? "unset" : structure) + ")";
        }
    }
}
