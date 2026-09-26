package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.brush.BrushFactory;
import com.maxlananas.fawebim.core.brush.BrushSettings;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.tool.ToolTarget;
import com.maxlananas.fawebim.core.transform.Transform;
import com.maxlananas.fawebim.core.transform.Transforms;
import com.maxlananas.fawebim.core.util.Msg;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /tool} options, mirroring WorldEdit's {@code ToolUtilCommands}:
 * {@code mask}, {@code material}, {@code range}, {@code size}, {@code tracemask},
 * {@code transform}, {@code target}, {@code targetoffset}, {@code primary} and
 * {@code secondary}.
 *
 * <p>Most of them edit the settings of the brush bound to the held item, which is
 * where FAWE keeps them too, and report the same "no brush bound" message when
 * there is none. Their {@code -h} switch addresses the second brush, the one
 * {@code /tool secondary} binds to the offhand: FAWE keeps a separate settings
 * object per hand, and so does this port.</p>
 */
final class ToolUtilCommands {

    /** {@code /tool target} mode names, in FAWE's order. */
    private static final List<String> TARGET_MODES =
            Arrays.stream(ToolTarget.Mode.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT)).toList();

    private final CommandRegistry registry;

    ToolUtilCommands(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        mask();
        material();
        range();
        size();
        traceMask();
        transform();
        target();
        targetOffset();
        scroll();
        primary();
        secondary();
        sourceMask();
        inspect();
        featurePlacer();
        structurePlacer();
    }

    /**
     * The tool a {@code /tool} sub-command acts on: the one in the player's main
     * hand, or with {@code -h} the offhand one, which is FAWE's second set of
     * brush settings and the tool the left click fires here.
     */
    private static Brush targetBrush(Ctx ctx) {
        if (!ctx.hasFlag("h")) {
            return requireBrush(ctx);
        }
        Brush offhand = BrushFactory.currentSecondary(ctx.session());
        if (offhand == null) {
            throw CommandRegistry.error("No tool in the offhand: bind one with /tool secondary <type> first");
        }
        return offhand;
    }

    /** {@code /tool mask [mask] [-h]} — the mask the brush writes through. */
    private void mask() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool mask");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the brush destination mask";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.booleanFlags.add("h");
        entry.arguments.add("[mask]");
        entry.arguments.add("[-h]");
        entry.handler = ctx -> {
            Brush brush = targetBrush(ctx);
            if (ctx.args().isEmpty()) {
                brush.setMask(null);
                ctx.actor().message(Msg.result("Brush mask", "cleared"));
                return;
            }
            brush.setMask(Parsers.mask(ctx.joined(0), ctx));
            ctx.actor().message(Msg.result("Brush mask", "set to " + Msg.value(ctx.joined(0)).raw()));
        };
    }

    /** {@code /tool material <pattern>} — what the brush places. */
    private void material() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool material");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the brush material";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.booleanFlags.add("h");
        entry.arguments.add("pattern");
        entry.arguments.add("[-h]");
        entry.handler = ctx -> {
            Brush brush = targetBrush(ctx);
            Pattern pattern = ctx.pattern(0);
            brush.setFill(pattern);
            ctx.actor().message(Msg.result("Brush material", "set to " + Msg.value(ctx.joined(0)).raw()));
        };
    }

    /** {@code /tool range <blocks>} — how far the brush reaches. */
    private void range() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool range");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the brush range";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.arguments.add("range");
        entry.handler = ctx -> {
            BrushSettings settings = requireBrush(ctx).settings();
            int range = ctx.intArg(0, settings.getRange());
            if (range < 1 || range > Config.get().maxBrushRange) {
                throw CommandRegistry.error("Range must be between 1 and " + Config.get().maxBrushRange);
            }
            settings.setRange(range);
            ctx.actor().message(Msg.result("Brush range", "set to " + Msg.count(range) + "\u00a77 block(s)"));
        };
    }

    /** {@code /tool size <radius>} — the brush radius. */
    private void size() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool size");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the brush size";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.arguments.add("size");
        entry.handler = ctx -> {
            Brush brush = requireBrush(ctx);
            int size = ctx.intArg(0, brush.settings().getSize());
            // The session limit is the one /brush answers to, so both commands
            // agree on how large a brush may be made.
            long limit = Math.min(ctx.session().getMaxBrushRadius(), Config.get().maxBrushRadius);
            if (size < 1 || size > limit) {
                throw CommandRegistry.error("Size must be between 1 and " + limit);
            }
            brush.setRadius(size);
            ctx.actor().message(Msg.result("Brush size", "set to " + Msg.count(size)));
        };
    }

    /** {@code /tool tracemask [mask]} — what a trace stops at. */
    private void traceMask() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool tracemask",
                "/tool targetmask", "/tool tarmask", "/tool tm");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the mask used to stop tool traces";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.arguments.add("[mask]");
        entry.handler = ctx -> {
            BrushSettings settings = requireBrush(ctx).settings();
            if (ctx.args().isEmpty()) {
                settings.setTraceMask(null);
                ctx.actor().message(Msg.result("Trace mask", "cleared; traces stop at solid blocks"));
                return;
            }
            settings.setTraceMask(Parsers.mask(ctx.joined(0), ctx));
            ctx.actor().message(Msg.result("Trace mask", "set to " + Msg.value(ctx.joined(0)).raw()));
        };
    }

    /** {@code /tool transform [transform]} — applied to what the brush places. */
    private void transform() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool transform");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the transform applied to what the brush places";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.booleanFlags.add("h");
        entry.arguments.add("[transform]");
        entry.arguments.add("[args...]");
        entry.arguments.add("[-h]");
        entry.handler = ctx -> {
            BrushSettings settings = targetBrush(ctx).settings();
            if (ctx.args().isEmpty()) {
                settings.setTransform(null);
                ctx.session().getTransformSet().clear();
                ctx.actor().message(Msg.result("Brush transform", "cleared"));
                return;
            }
            Transform transform = parseTransform(ctx);
            settings.setTransform(transform);
            Transforms.Set set = new Transforms.Set();
            set.add(transform);
            ctx.session().getTransformSet().setTransforms(set);
            ctx.actor().message(Msg.result("Brush transform", "set to " + Msg.value(ctx.joined(0)).raw()));
        };
    }

    /** Parses the same transform syntax {@code //gtransform} accepts. */
    static Transform parseTransform(Ctx ctx) {
        String action = ctx.arg(0).toLowerCase(Locale.ROOT);
        return switch (action) {
            case "rotate", "rot" -> Transforms.rotate(com.maxlananas.fawebim.core.math.BlockVector3.ZERO,
                    ctx.doubleArg(1, 90));
            case "flip", "mirror" -> Transforms.flip(com.maxlananas.fawebim.core.math.BlockVector3.ZERO,
                    switch (ctx.arg(1, "north").toLowerCase(Locale.ROOT)) {
                        case "x", "east", "west" -> com.maxlananas.fawebim.core.transform.Axis.X;
                        case "y", "up", "down" -> com.maxlananas.fawebim.core.transform.Axis.Y;
                        default -> com.maxlananas.fawebim.core.transform.Axis.Z;
                    });
            case "scale" -> Transforms.scale(com.maxlananas.fawebim.core.math.BlockVector3.ZERO,
                    ctx.doubleArg(1, 1), ctx.doubleArg(2, ctx.doubleArg(1, 1)), ctx.doubleArg(3, ctx.doubleArg(1, 1)));
            case "offset" -> Transforms.offset(ctx.doubleArg(1, 0), ctx.doubleArg(2, 0), ctx.doubleArg(3, 0));
            default -> throw CommandRegistry.error(
                    "Unknown transform '" + action + "'. Try rotate, flip, scale or offset");
        };
    }

    /** {@code /tool target [mode]} — how the tool finds its target position. */
    private void target() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool target", "/tool tar");
        if (entry == null) {
            return;
        }
        entry.description =
                "Toggle between the different target modes";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.arguments.add("[mode]");
        entry.handler = ctx -> {
            BrushSettings settings = requireBrush(ctx).settings();
            ToolTarget.Mode mode;
            if (ctx.args().isEmpty()) {
                mode = ToolTarget.mode(settings.getTargetMode() + 1);
            } else if (isIndex(ctx.arg(0))) {
                mode = ToolTarget.mode(ctx.intArg(0));
            } else {
                String name = ctx.arg(0).toUpperCase(Locale.ROOT);
                try {
                    mode = ToolTarget.Mode.valueOf(name);
                } catch (IllegalArgumentException e) {
                    throw CommandRegistry.error("Target mode must be one of: " + String.join(", ", TARGET_MODES));
                }
            }
            settings.setTargetMode(mode.ordinal());
            ctx.actor().message(Msg.result("Target mode", "set to "
                    + Msg.value(mode.name().toLowerCase(Locale.ROOT)).raw()));
        };
    }

    private static boolean isIndex(String input) {
        return input.chars().allMatch(Character::isDigit) && !input.isEmpty();
    }

    /** {@code /tool targetoffset <blocks>} — moves the target towards the player. */
    private void targetOffset() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool targetoffset", "/tool to");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the targeting offset";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.arguments.add("offset");
        entry.handler = ctx -> {
            BrushSettings settings = requireBrush(ctx).settings();
            int offset = ctx.intArg(0, 0);
            settings.setTargetOffset(offset);
            ctx.actor().message(Msg.result("Target offset", "set to " + Msg.value(offset).raw()));
        };
    }

    /**
     * {@code /tool primary <brush...>} — binds a brush to the right click. The
     * second and third arguments are the brush type and its parameters, exactly
     * as {@code /brush} takes them.
     */
    private void primary() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool primary");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the right click brush";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.arguments.add("type");
        entry.arguments.add("[args...]");
        entry.handler = ctx -> bindBrush(ctx, false);
    }

    /**
     * {@code /tool scroll <action> [arguments]} — what the mouse wheel changes.
     *
     * <p>The server only sees the hotbar slot the client moves to, so scrolling is
     * one slot step in either direction, exactly like FAWE's platform listeners
     * turn it into an amount of one.</p>
     */
    private void scroll() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool scroll", "/brush scroll");
        if (entry == null) {
            return;
        }
        entry.description =
                "Toggle between different target modes";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.booleanFlags.add("h");
        entry.arguments.add("[action]");
        entry.arguments.add("[arguments]");
        entry.handler = ctx -> {
            Brush brush = requireBrush(ctx);
            if (ctx.args().isEmpty()) {
                brush.settings().setScrollAction(null);
                brush.settings().setScrollActionName("");
                ctx.actor().message(Msg.result("Scroll action", "cleared"));
                return;
            }
            String name = ctx.arg(0);
            com.maxlananas.fawebim.core.tool.Scroll.Action action = com.maxlananas.fawebim.core.tool.Scroll.action(name);
            if (action == null) {
                throw CommandRegistry.error("Scroll action must be one of: "
                        + com.maxlananas.fawebim.core.tool.Scroll.actions());
            }
            List<String> rest = ctx.rawArgs(1);
            java.util.List<Mask> masks = new java.util.ArrayList<>();
            java.util.List<Pattern> patterns = new java.util.ArrayList<>();
            if (action == com.maxlananas.fawebim.core.tool.Scroll.Action.MASK
                    || action == com.maxlananas.fawebim.core.tool.Scroll.Action.PATTERN) {
                for (String argument : rest) {
                    if (action == com.maxlananas.fawebim.core.tool.Scroll.Action.MASK) {
                        masks.add(Parsers.mask(argument, ctx));
                    } else {
                        patterns.add(Parsers.pattern(argument, ctx));
                    }
                }
            }
            com.maxlananas.fawebim.core.tool.Scroll scroll =
                    com.maxlananas.fawebim.core.tool.Scroll.of(action, brush, ctx.session(), masks, patterns);
            brush.settings().setScrollAction(scroll);
            brush.settings().setScrollActionName((name + " " + String.join(" ", rest)).trim());
            if (action == com.maxlananas.fawebim.core.tool.Scroll.Action.NONE || scroll == null) {
                ctx.actor().message(Msg.result("Scroll action", "cleared"));
            } else {
                ctx.actor().message(Msg.result("Scroll action", "set to "
                    + Msg.value(name.toLowerCase(Locale.ROOT)).raw()));
            }
        };
    }

    /** {@code /tool secondary <brush...>} — binds a brush to the left click. */
    private void secondary() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool secondary");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the left click brush";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.arguments.add("type");
        entry.arguments.add("[args...]");
        entry.handler = ctx -> bindBrush(ctx, true);
    }

    /**
     * Binds a brush like {@code /brush} would, then moves it to the requested
     * mouse button. The brush already bound to the other button is restored, so
     * binding one does not unbind the other.
     */
    private void bindBrush(Ctx ctx, boolean secondary) {
        LocalSession session = ctx.session();
        Brush previousPrimary = BrushFactory.current(session);
        Brush previousSecondary = BrushFactory.currentSecondary(session);
        StringBuilder line = new StringBuilder("brush");
        for (String argument : ctx.args()) {
            line.append(' ').append(argument);
        }
        registry.dispatch(ctx.actor(), line.toString());
        Brush bound = BrushFactory.current(session);
        if (bound == null) {
            return;
        }
        if (secondary) {
            BrushFactory.bindSecondary(session, bound, ctx.actor());
            if (previousPrimary != null) {
                session.getBindings().put("brush", previousPrimary);
            } else {
                BrushFactory.unbind(session);
            }
            ctx.actor().message(Msg.result("Left click brush", Msg.value(bound.describe()).raw()));
            return;
        }
        if (previousSecondary != null) {
            BrushFactory.bindSecondary(session, previousSecondary, ctx.actor());
        }
        ctx.actor().message(Msg.result("Right click brush", Msg.value(bound.describe()).raw()));
    }

    /**
     * {@code /tool smask [mask]} — the blocks the brush reads with. The brush
     * shares the session's source mask, so this is the same setting as
     * {@code //gsmask}.
     */
    private void sourceMask() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool smask", "/tool sourcemask");
        if (entry == null) {
            return;
        }
        entry.description =
                "Set the brush source mask";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.booleanFlags.add("h");
        entry.arguments.add("[mask]");
        entry.arguments.add("[-h]");
        entry.handler = ctx -> {
            if (ctx.args().isEmpty()) {
                ctx.session().setSourceMask(null);
                ctx.actor().message(Msg.result("Brush source mask", "cleared"));
                return;
            }
            Mask mask = Parsers.mask(ctx.joined(0), ctx);
            // A tool's own source mask stays with that tool; the shared session
            // mask is what a tool with no mask of its own reads through.
            targetBrush(ctx).settings().setSourceMask(mask);
            ctx.session().setSourceMask(mask);
            ctx.actor().message(Msg.result("Brush source mask", "set to " + Msg.value(ctx.joined(0)).raw()));
        };
    }

    /** {@code /tool inspect} — the block info tool, WorldEdit registers the same one. */
    private void inspect() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool inspect");
        if (entry == null) {
            return;
        }
        entry.description =
                "Block information tool";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.handler = ctx -> registry.dispatch(ctx.actor(), "tool info");
    }

    private void featurePlacer() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool featureplacer", "/tool featuretool");
        if (entry == null) {
            return;
        }
        entry.description =
                "Bind a tool that places a worldgen feature on click";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.arguments.add("feature");
        entry.handler = ctx -> {
            var tool = new com.maxlananas.fawebim.core.tool.Tools.FeaturePlacerTool(ctx.arg(0));
            com.maxlananas.fawebim.core.tool.Tools.bind(ctx.session(), tool, ctx.actor(), null);
            ctx.actor().message(Msg.success("Feature placer bound to your held item for '" + ctx.arg(0) + "'"));
        };
    }

    private void structurePlacer() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool structureplacer", "/tool structuretool");
        if (entry == null) {
            return;
        }
        entry.description =
                "Bind a tool that generates a structure on click";
        entry.requiresPlayer = true;
        entry.group = "tool";
        entry.arguments.add("structure");
        entry.handler = ctx -> {
            var tool = new com.maxlananas.fawebim.core.tool.Tools.StructurePlacerTool(ctx.arg(0));
            com.maxlananas.fawebim.core.tool.Tools.bind(ctx.session(), tool, ctx.actor(), null);
            ctx.actor().message(Msg.success("Structure placer bound to your held item for '" + ctx.arg(0) + "'"));
        };
    }

    /** The brush bound to the held item, or FAWE's "no brush" error. */
    private static Brush requireBrush(Ctx ctx) {
        Brush brush = BrushFactory.current(ctx.session());
        if (brush == null) {
            throw CommandRegistry.error("No brush bound: use /brush <type> first");
        }
        return brush;
    }
}
