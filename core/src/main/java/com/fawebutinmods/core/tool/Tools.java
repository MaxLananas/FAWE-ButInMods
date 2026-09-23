package com.fawebutinmods.core.tool;

import com.fawebutinmods.core.actor.Actor;
import com.fawebutinmods.core.command.BrushCommands;
import com.fawebutinmods.core.command.Ctx;
import com.fawebutinmods.core.extent.EditSession;
import com.fawebutinmods.core.mask.Mask;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.pattern.Pattern;
import com.fawebutinmods.core.pattern.Patterns;
import com.fawebutinmods.core.session.LocalSession;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.BlockStateRegistry;

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
            "stacker", "deltree", "brush", "selwand", "featureplacer", "structureplacer", "command",
            "flood", "warwand");

    public static Tool create(String name, Ctx ctx) {
        String key = name.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "tree" -> new TreeTool();
            case "repl", "replace" -> new ReplaceTool();
            case "cycler" -> new CyclerTool();
            case "floodfill", "flood-fill", "flood" -> new FloodFillTool();
            case "info", "inspect" -> new InfoTool();
            case "farwand", "warwand" -> new FarWandTool();
            case "navwand", "navigation" -> new NavigationWandTool();
            case "lrbuild", "lr-build" -> new LRBuildTool();
            case "stacker" -> new StackerTool();
            case "deltree" -> new DelTreeTool();
            case "brush" -> new BrushTool();
            case "selwand" -> new SelectWandTool(false);
            case "navigationwand" -> new SelectWandTool(true);
            case "featureplacer", "featuretool" -> new FeaturePlacerTool(null);
            case "structureplacer", "structuretool" -> new StructurePlacerTool(null);
            default -> null;
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

    /** The pattern argument of a tool, defaulting to the global pattern. */
    static Pattern pattern(Actor actor, Pattern fallback) {
        Pattern global = actor.session().getPattern();
        return global == null ? fallback : global;
    }

    static Pattern stonePattern() {
        return new Patterns.Single(BlockState.registry().defaultState("minecraft:stone"));
    }

    // ------------------------------------------------------------------- tools

    /** {@code /tool tree [tree-type]}. */
    public static final class TreeTool implements Tool {

        private String treeType = "tree";

        public void setTreeType(String treeType) {
            this.treeType = treeType;
        }

        @Override
        public String name() {
            return "tree";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            boolean ok = context.actor.world().generateTree(
                    context.position.add(context.face.toVector()), treeType, new Random());
            context.message(ok ? Msg.success("Planted a " + treeType)
                    : Msg.error("Could not place tree '" + treeType + "'"));
            return ok;
        }

        @Override
        public String describe() {
            return "tree (" + treeType + ")";
        }
    }

    /** {@code /tool repl [pattern]} — replaces the block you click. */
    public static final class ReplaceTool implements Tool {

        private Pattern pattern;

        public void setPattern(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public String name() {
            return "repl";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            Pattern fill = pattern != null ? pattern : pattern(context.actor, stonePattern());
            EditSession session = context.hasSession() ? context.session
                    : new EditSession(context.actor.world(), context.actor.session(), "tool repl");
            session.setBlock(context.position.x(), context.position.y(), context.position.z(),
                    fill.apply(context.position.x(), context.position.y(), context.position.z()));
            session.flushQueue();
            return true;
        }

        @Override
        public String describe() {
            return "replace with " + (pattern == null ? "stone" : pattern.getClass().getSimpleName());
        }
    }

    /** {@code /tool cycler} — cycles a block's properties. */
    public static final class CyclerTool implements Tool {

        @Override
        public String name() {
            return "cycler";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            BlockStateRegistry registry = BlockState.registry();
            int current = context.actor.world().getBlock(context.position.x(), context.position.y(), context.position.z());
            var properties = registry.properties(current);
            if (properties.isEmpty()) {
                return false;
            }
            String key = properties.keySet().iterator().next();
            List<String> values = registry.propertyDefs(current).get(key);
            if (values == null || values.isEmpty()) {
                return false;
            }
            String value = properties.get(key);
            int index = (values.indexOf(value) + 1) % values.size();
            int next = registry.withProperty(current, key, values.get(index));
            if (next < 0) {
                return false;
            }
            EditSession session = context.hasSession() ? context.session
                    : new EditSession(context.actor.world(), context.actor.session(), "tool cycler");
            session.setBlock(context.position.x(), context.position.y(), context.position.z(), next);
            session.flushQueue();
            context.message(Msg.info(key + " = " + values.get(index)));
            return true;
        }

        @Override
        public String describe() {
            return "cycler";
        }
    }

    /** {@code /tool floodfill <pattern> [range] [radius]}. */
    public static final class FloodFillTool implements Tool {

        private Pattern pattern;

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
            EditSession session = context.hasSession() ? context.session
                    : new EditSession(context.actor.world(), context.actor.session(), "tool floodfill");
            int changed = com.fawebutinmods.core.function.Operations.floodFill(
                    context.actor.world(), session, context.position.add(context.face.toVector()), fill, 256, false);
            session.flushQueue();
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

    /** {@code /tool farwand} — the wand with an extended reach. */
    public static final class FarWandTool implements Tool {

        @Override
        public String name() {
            return "farwand";
        }

        @Override
        public boolean onLeftClick(ToolContext context) {
            context.actor.session().getSelector(context.actor.world())
                    .selectPrimary(context.position, com.fawebutinmods.core.region.SelectorLimits.unlimited());
            context.message(Msg.success("Position 1 set to " + context.position));
            return true;
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            context.actor.session().getSelector(context.actor.world())
                    .selectSecondary(context.position, com.fawebutinmods.core.region.SelectorLimits.unlimited());
            context.message(Msg.success("Position 2 set to " + context.position));
            return true;
        }

        @Override
        public String describe() {
            return "far wand";
        }
    }

    /** {@code /tool navwand} — jump to the clicked block. */
    public static final class NavigationWandTool implements Tool {

        @Override
        public String name() {
            return "navwand";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            context.actor.teleport(context.position.x() + 0.5, context.position.y() + 1,
                    context.position.z() + 0.5);
            return true;
        }

        @Override
        public String describe() {
            return "navigation wand";
        }
    }

    /** {@code /tool lrbuild} — left-click removes, right-click places. */
    public static final class LRBuildTool implements Tool {

        private final java.util.Deque<BlockVector3> leftClicks = new java.util.ArrayDeque<>();

        @Override
        public String name() {
            return "lrbuild";
        }

        @Override
        public boolean onLeftClick(ToolContext context) {
            leftClicks.push(context.position);
            if (leftClicks.size() > 2) {
                leftClicks.removeLast();
            }
            context.message(Msg.info("Left click stored: " + context.position));
            if (leftClicks.size() == 2) {
                EditSession session = new EditSession(context.actor.world(), context.actor.session(), "tool lrbuild");
                for (BlockVector3 position : com.fawebutinmods.core.function.Operations.spherePositions(
                        context.position, 3, false)) {
                    session.setBlock(position.x(), position.y(), position.z(), BlockState.registry().air());
                }
                session.flushQueue();
            }
            return true;
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            EditSession session = new EditSession(context.actor.world(), context.actor.session(), "tool lrbuild");
            Pattern fill = pattern(context.actor, stonePattern());
            for (BlockVector3 position : com.fawebutinmods.core.function.Operations.spherePositions(
                    context.position, 3, false)) {
                session.setBlock(position.x(), position.y(), position.z(),
                        fill.apply(position.x(), position.y(), position.z()));
            }
            session.flushQueue();
            return true;
        }

        @Override
        public String describe() {
            return "left/right build";
        }
    }

    /** {@code /tool stacker} — repeats the last selection's contents. */
    public static final class StackerTool implements Tool {

        @Override
        public String name() {
            return "stacker";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            LocalSession session = context.actor.session();
            if (!session.hasClipboard()) {
                context.message(Msg.error("Copy something first with //copy"));
                return false;
            }
            var clipboard = session.getClipboard().getClipboard();
            EditSession edit = new EditSession(context.actor.world(), session, "tool stacker");
            int changed = com.fawebutinmods.core.clipboard.Clipboards.paste(clipboard,
                    context.position.add(context.face.toVector()), edit,
                    com.fawebutinmods.core.transform.Transform.identity(), true, false, false);
            context.message(Msg.success("Pasted " + Msg.formatNumber(changed) + " block(s)"));
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
            int changed = com.fawebutinmods.core.function.Operations.removeTree(
                    context.actor.world(), session, context.position);
            session.flushQueue();
            context.message(Msg.success("Removed " + changed + " block(s)"));
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
            var brush = com.fawebutinmods.core.brush.BrushFactory.current(context.actor.session());
            if (brush == null) {
                context.message(Msg.error("No brush bound. Use /brush sphere 5 stone"));
                return false;
            }
            EditSession session = new EditSession(context.actor.world(), context.actor.session(), "brush");
            int changed = brush.apply(session, context.position, context.actor);
            session.flushQueue();
            context.message(Msg.success("Brush changed " + Msg.formatNumber(changed) + " block(s)"));
            return true;
        }

        @Override
        public String describe() {
            return "brush";
        }
    }

    /** {@code /tool selwand} / {@code /tool navwand}. */
    public static final class SelectWandTool implements Tool {

        private final boolean navigation;

        public SelectWandTool(boolean navigation) {
            this.navigation = navigation;
        }

        @Override
        public String name() {
            return navigation ? "navwand" : "selwand";
        }

        @Override
        public boolean onLeftClick(ToolContext context) {
            context.actor.session().getSelector(context.actor.world())
                    .selectPrimary(context.position, com.fawebutinmods.core.region.SelectorLimits.unlimited());
            context.message(Msg.success("Position 1 set to " + context.position));
            return true;
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            if (navigation) {
                context.actor.teleport(context.position.x() + 0.5, context.position.y() + 1,
                        context.position.z() + 0.5);
                return true;
            }
            context.actor.session().getSelector(context.actor.world())
                    .selectSecondary(context.position, com.fawebutinmods.core.region.SelectorLimits.unlimited());
            context.message(Msg.success("Position 2 set to " + context.position));
            return true;
        }

        @Override
        public String describe() {
            return navigation ? "navigation wand" : "selection wand";
        }
    }

    /** Runs a bound command when the tool is used ({@code /tool command}). */
    public static final class CommandTool implements Tool {

        private final String command;

        public CommandTool(String command) {
            this.command = command;
        }

        @Override
        public String name() {
            return "command";
        }

        @Override
        public boolean onRightClick(ToolContext context) {
            String parsed = command
                    .replace("%x%", String.valueOf(context.position.x()))
                    .replace("%y%", String.valueOf(context.position.y()))
                    .replace("%z%", String.valueOf(context.position.z()));
            return BrushCommands.run(context.actor, parsed);
        }

        @Override
        public String describe() {
            return "command";
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
