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
import com.maxlananas.fawebim.core.session.ItemBinding;
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
            "none", "tree", "repl", "cycler", "floodfill", "info", "inspect", "farwand", "navwand", "lrbuild",
            "stacker", "deltree", "brush", "selwand", "featureplacer", "structureplacer", "flood", "warwand");

    /**
     * A sub-command of {@code /tool} as FAWE's ToolCommands declares it: its
     * name and other spellings, its arguments, its description, and what the
     * line binding it calls the tool, "Tree tool bound to Stick".
     */
    public record Kind(String name, java.util.List<String> aliases, java.util.List<String> arguments,
                       String description, String label) {
    }

    /** FAWE's tool sub-commands but {@code none}, in the order it declares them. */
    public static final java.util.List<Kind> KINDS = java.util.List.of(
            new Kind("selwand", java.util.List.of(), java.util.List.of(), "Selection wand tool", "Selection wand"),
            new Kind("navwand", java.util.List.of(), java.util.List.of(), "Navigation wand tool", "Navigation wand"),
            new Kind("info", java.util.List.of(), java.util.List.of(), "Block information tool", "Info tool"),
            new Kind("inspect", java.util.List.of(), java.util.List.of(), "Block information tool", "Info tool"),
            new Kind("tree", java.util.List.of(), java.util.List.of("[type]"), "Tree generator tool", "Tree tool"),
            new Kind("featureplacer", java.util.List.of("featuretool"), java.util.List.of("feature"),
                    "Feature placer tool", "Feature placer tool"),
            new Kind("structureplacer", java.util.List.of("structuretool"), java.util.List.of("structure"),
                    "Structure placer tool", "Structure placer tool"),
            new Kind("stacker", java.util.List.of(), java.util.List.of("[range]", "[mask]"), "Block stacker tool",
                    "Stack tool"),
            new Kind("repl", java.util.List.of(), java.util.List.of("pattern"), "Block replacer tool",
                    "Block replacer tool"),
            new Kind("cycler", java.util.List.of(), java.util.List.of(), "Block data cycler tool",
                    "Block data cycler tool"),
            // The pattern and the range have defaults here, the pattern in the
            // hotbar and the super pickaxe's ceiling; FAWE asks for both.
            new Kind("floodfill", java.util.List.of("flood"), java.util.List.of("[pattern]", "[range]"),
                    "Flood fill tool", "Block flood fill tool"),
            new Kind("deltree", java.util.List.of(), java.util.List.of(), "Floating tree remover tool",
                    "Floating tree remover tool"),
            new Kind("farwand", java.util.List.of("warwand"), java.util.List.of(), "Wand at a distance tool",
                    "Far wand tool"),
            new Kind("lrbuild", java.util.List.of(), java.util.List.of("primary", "secondary"),
                    "Long-range building tool", "Long-range building tool"));

    /**
     * The tools {@code /tool} binds, as FAWE lists them under a line that names
     * none or one it does not know.
     */
    public static String options() {
        java.util.StringJoiner options = new java.util.StringJoiner(", ", "none, ", "");
        for (Kind kind : KINDS) {
            options.add(kind.name());
        }
        return options.toString();
    }

    /** The sub-command a name or another spelling of it stands for, or null. */
    public static Kind kind(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        for (Kind kind : KINDS) {
            if (kind.name().equals(key) || kind.aliases().contains(key)) {
                return kind;
            }
        }
        return null;
    }

    /** FAWE's answer to a feature that placed nothing, from its placer and from //feature. */
    public static final String FEATURE_FAILED = "This feature cannot go here. Ensure the area meets the requirements.";

    /** FAWE's answer to a structure that placed nothing, from its placer and from //structure. */
    public static final String STRUCTURE_FAILED = "Failed to generate structure. Is it a valid spot for it?";

    /**
     * The tool {@code /tool <name> ...} binds, built from the arguments the
     * tool takes - WorldEdit's: the pattern of {@code repl}, the two patterns
     * of {@code lrbuild}, the range and mask of {@code stacker} - or null for a
     * name that is not a tool. An argument that does not parse is refused
     * here, before anything is bound.
     *
     * @param first where the tool's own arguments start among the command's:
     *              after the name on {@code /tool <name>}, at the start on a
     *              sub-command of its own
     */
    public static Tool create(String name, Ctx ctx, int first) {
        String key = name.toLowerCase(Locale.ROOT);
        return switch (key) {
            // /tool tree <type> names the tree the tool plants.
            case "tree" -> new TreeTool(Parsers.treeType(ctx.arg(first, "tree")));
            case "repl", "replace" -> new ReplaceTool(Parsers.pattern(required(ctx, first, "repl <pattern>"), ctx));
            case "cycler" -> new CyclerTool();
            case "floodfill", "flood-fill", "flood" -> FloodFillTool.of(ctx, first);
            case "info", "inspect" -> new InfoTool();
            case "farwand", "warwand" -> new FarWandTool();
            case "navwand", "navigation", "navigationwand" -> new NavigationWandTool();
            case "lrbuild", "lr-build" -> new LRBuildTool(
                    Parsers.pattern(required(ctx, first, LRBuildTool.USAGE), ctx),
                    Parsers.pattern(required(ctx, first + 1, LRBuildTool.USAGE), ctx));
            case "stacker" -> StackerTool.of(ctx, first);
            case "deltree" -> new DelTreeTool();
            case "brush" -> new BrushTool();
            case "selwand" -> new SelectWandTool();
            case "featureplacer", "featuretool" -> new FeaturePlacerTool(
                    Parsers.feature(ctx.world(), required(ctx, first, "featureplacer <feature>")));
            case "structureplacer", "structuretool" -> new StructurePlacerTool(
                    Parsers.structure(ctx.world(), required(ctx, first, "structureplacer <structure>")));
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
     * How many arguments a tool takes before the item to bind it to, which
     * comes right after them: {@code /tool repl stone} binds a stone replacer
     * to the held item and {@code /tool repl stone stick} binds it to sticks.
     */
    public static int argumentCount(String name) {
        Kind kind = kind(name);
        return kind == null ? 0 : kind.arguments().size();
    }

    /**
     * The line that says a tool is bound, as FAWE's: "Tree tool bound to
     * Stick". The empty hand, which a tool is bound to as well, is said so.
     */
    public static String boundLine(String name, String item) {
        Kind kind = kind(name);
        String label = kind != null ? kind.label()
                : Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase(Locale.ROOT) + " tool";
        String held = item == null || item.equals("minecraft:air") ? "your empty hand"
                : com.maxlananas.fawebim.core.util.Str.itemName(item);
        return label + " bound to " + held;
    }

    /**
     * Binds a tool to an item - the held one when none is named - where its
     * brushes or another tool were, as FAWE's item holds one tool.
     *
     * @return the item the tool is bound to
     */
    public static String bind(LocalSession session, Tool tool, Actor actor, String item) {
        String target = item == null || item.isEmpty() ? actor.heldItem() : item;
        session.bind(target).setTool(tool);
        return target;
    }

    /** Takes the tool off an item. */
    public static void clear(LocalSession session, String item) {
        ItemBinding binding = session.binding(item);
        if (binding != null && binding.tool() != null) {
            binding.setTool(null);
            session.release(item);
        }
    }

    /** The tool bound to an item, or null. */
    public static Tool current(LocalSession session, String item) {
        ItemBinding binding = session.binding(item);
        return binding == null ? null : binding.tool();
    }

    /** The tool bound to the item the actor holds, or null. */
    public static Tool current(Actor actor) {
        return current(actor.session(), actor.heldItem());
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
        ItemBinding binding = session.binding(item);
        if (binding != null) {
            return binding.tool();
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
            // WorldEdit's tree planter: the tree goes through the edit, so it
            // can be undone, and a shape that does not fit is tried again, up
            // to ten times; only a tree that cannot go there is reported.
            EditSession session = open(context, "tool tree");
            boolean planted = false;
            try {
                Random random = new Random();
                for (int attempt = 0; attempt < 10 && !planted; attempt++) {
                    planted = context.actor.world().generateTree(session, context.position.add(0, 1, 0), treeType,
                            random);
                }
            } finally {
                finish(context, session);
            }
            if (!planted) {
                context.message(Msg.error("A tree can't go there."));
            }
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
     * {@code /tool repl <pattern>}, WorldEdit's block replacer: a left click
     * turns the clicked block into the pattern, whatever it was and whatever
     * the held item is, and a right click makes the clicked block the pattern,
     * with its data - the items of a chest, the text of a sign - which every
     * later left click writes again.
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
        public boolean onLeftClick(ToolContext context) {
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
        public boolean onRightClick(ToolContext context) {
            if (!context.aimsAtBlock()) {
                context.message(Msg.error("No block in sight"));
                return true;
            }
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
        static FloodFillTool of(Ctx ctx, int first) {
            FloodFillTool tool = new FloodFillTool();
            if (ctx.args().size() > first) {
                tool.pattern = com.maxlananas.fawebim.core.command.Parsers.pattern(ctx.arg(first), ctx);
            }
            tool.range = ctx.args().size() > first + 1 ? ctx.intArg(first + 1) : SuperPickaxe.ceiling();
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
            try {
                for (int i = 0; i < targets.length; i += 3) {
                    int x = targets[i];
                    int y = targets[i + 1];
                    int z = targets[i + 2];
                    session.setBlock(x, y, z, fill.apply(x, y, z));
                }
            } finally {
                finish(context, session);
            }
            // A fill says nothing, as upstream's: a tool is clicked many times.
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
        select(context.actor, context.position, primary, false);
        return true;
    }

    /**
     * Gives a position to the selection, as the wand, {@code //pos1} and
     * {@code //pos2} do: the primary one for a left click, the secondary for a
     * right one, answered with the line the shape has for it - the corner of a
     * cuboid, the centre or the radius of a sphere, the point of a polygon.
     *
     * <p>A position that changes nothing is answered only when a command asked
     * for it, as in WorldEdit: clicking the same block twice with the wand says
     * nothing.</p>
     *
     * @return whether the selection changed
     */
    public static boolean select(Actor actor, BlockVector3 position, boolean primary, boolean command) {
        com.maxlananas.fawebim.core.region.RegionSelector selector = actor.session().getSelector(actor.world());
        com.maxlananas.fawebim.core.region.SelectorLimits limits =
                com.maxlananas.fawebim.core.region.SelectorLimits.player();
        boolean changed = primary ? selector.selectPrimary(position, limits)
                : selector.selectSecondary(position, limits);
        if (changed) {
            actor.message(primary ? selector.explainPrimary(position) : selector.explainSecondary(position));
            actor.updateSelectionOutline();
        } else if (command) {
            actor.message(Msg.warn(unchanged(selector, primary)));
        }
        return changed;
    }

    /** Why a position changed nothing. */
    private static String unchanged(com.maxlananas.fawebim.core.region.RegionSelector selector, boolean primary) {
        boolean centred = selector instanceof com.maxlananas.fawebim.core.region.Selectors.EllipsoidSelector
                || selector instanceof com.maxlananas.fawebim.core.region.Selectors.CylinderSelector;
        if (!primary && centred && selector.primaryPoints().isEmpty()) {
            return "Select the center with a left click or //pos1 first";
        }
        if (!primary && selector.vertexCount() > com.maxlananas.fawebim.core.region.SelectorLimits.PLAYER_VERTEX_LIMIT) {
            return "The selection already has the most points it may have";
        }
        return "Position already set";
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

        static StackerTool of(Ctx ctx, int first) {
            int range = ctx.intArg(first, 10, 1, com.maxlananas.fawebim.core.platform.Config.get().maxBrushRange,
                    "stack range");
            Mask mask = ctx.args().size() > first + 1 ? Parsers.mask(ctx.arg(first + 1), ctx) : null;
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

    /**
     * {@code /tool deltree}, WorldEdit's floating tree remover: a right click
     * on a tree that stands on nothing - the top a felled trunk left in the air
     * - takes its logs and leaves away. A tree standing on the ground is not
     * floating and stays, and the click says so; a removal says nothing, as
     * upstream's.
     *
     * <p>It took away every log and leaf joined to the click, twenty thousand
     * blocks of a forest's canopy included, whatever they stood on, and said
     * how many on every click.</p>
     */
    public static final class DelTreeTool implements Tool {

        @Override
        public String name() {
            return "deltree";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            if (!context.aimsAtBlock()) {
                context.message(Msg.error("No block in sight"));
                return true;
            }
            com.maxlananas.fawebim.core.world.World world = context.actor.world();
            BlockVector3 at = context.position;
            if (!com.maxlananas.fawebim.core.function.Operations.isTreeBlock(world.getBlock(at.x(), at.y(), at.z()))) {
                context.message(Msg.error("That's not a tree."));
                return true;
            }
            long[] tree = com.maxlananas.fawebim.core.function.Operations.floatingTree(world, at);
            if (tree == null) {
                context.message(Msg.error("That's not a floating tree."));
                return true;
            }
            int air = BlockState.registry().air();
            EditSession session = open(context, "tool deltree");
            try {
                for (long key : tree) {
                    int x = com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard.keyX(key);
                    int y = com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard.keyY(key);
                    int z = com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard.keyZ(key);
                    if (com.maxlananas.fawebim.core.function.Operations.isTreeBlock(session.getBlock(x, y, z))) {
                        session.setBlock(x, y, z, air);
                    }
                }
            } finally {
                finish(context, session);
            }
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

        /**
         * Fires the brush bound last, whatever its item, and says nothing when
         * it went well: a stroke of a brush answers nothing, whichever item
         * fires it.
         */
        @Override
        public boolean onRightClick(ToolContext context) {
            var brush = com.maxlananas.fawebim.core.brush.BrushFactory.latest(context.actor.session());
            if (brush == null) {
                context.message(Msg.error("No brush bound. Use /brush sphere stone 5"));
                return false;
            }
            EditSession session = new EditSession(context.actor.world(), context.actor.session(), "brush");
            try {
                com.maxlananas.fawebim.core.brush.Brushes.apply(brush, session, context.position, context.actor);
            } finally {
                session.close();
            }
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
     * {@code /tool featureplacer <feature>}, FAWE's feature placer: a right
     * click generates the feature in the clicked block, or against the
     * clicked face for the features that grow on a block - a tree, a flower -
     * trying ten times, and says how many blocks it placed.
     *
     * <p>It placed in the clicked block whatever the feature, so a tree met
     * the ground and did not grow, tried once, and answered "Unknown feature"
     * for one that did not fit.</p>
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
            if (!context.aimsAtBlock()) {
                context.message(Msg.error("No block in sight"));
                return true;
            }
            com.maxlananas.fawebim.core.world.World world = context.actor.world();
            BlockVector3 at = world.placesFeatureOnFace(feature) && context.face != null
                    ? context.position.add(context.face.toVector()) : context.position;
            EditSession session = open(context, "tool featureplacer");
            long placed = 0;
            try {
                Random random = new Random();
                for (int attempt = 0; attempt < 10 && placed == 0; attempt++) {
                    long before = session.getBlocksChanged();
                    world.generateFeature(session, at, feature, random);
                    placed = session.getBlocksChanged() - before;
                }
            } finally {
                finish(context, session);
            }
            context.message(placed == 0 ? Msg.error(FEATURE_FAILED)
                    : Msg.result("Feature created", Msg.blocks(placed) + " placed"));
            return true;
        }

        @Override
        public String describe() {
            return "feature placer (" + feature + ")";
        }
    }

    /**
     * {@code /tool structureplacer <structure>}, FAWE's structure placer: a
     * right click generates the structure at the clicked block, trying ten
     * times, and says how many blocks it placed.
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
            if (!context.aimsAtBlock()) {
                context.message(Msg.error("No block in sight"));
                return true;
            }
            com.maxlananas.fawebim.core.world.World world = context.actor.world();
            EditSession session = open(context, "tool structureplacer");
            long placed = 0;
            try {
                Random random = new Random();
                for (int attempt = 0; attempt < 10 && placed == 0; attempt++) {
                    long before = session.getBlocksChanged();
                    world.generateStructure(session, structure, context.position, random);
                    placed = session.getBlocksChanged() - before;
                }
            } finally {
                finish(context, session);
            }
            context.message(placed == 0 ? Msg.error(STRUCTURE_FAILED)
                    : Msg.result("Structure created", Msg.blocks(placed) + " placed"));
            return true;
        }

        @Override
        public String describe() {
            return "structure placer (" + structure + ")";
        }
    }
}
