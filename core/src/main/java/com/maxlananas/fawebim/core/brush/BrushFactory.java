package com.maxlananas.fawebim.core.brush;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.Images;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Creates the brushes of the {@code /brush} command.
 *
 * <p>Names, aliases and argument shapes follow FAWE's {@code BrushCommands}:
 * {@code sphere}, {@code sphere2d}/{@code cylinder}, {@code smooth} <i>[cycles]</i>,
 * {@code blendball} <i>[radius]</i>, {@code flatten} <i>[radius] [height]</i>,
 * {@code height} <i>[radius]</i>, {@code rotate} ...</p>
 */
public final class BrushFactory {

    private BrushFactory() {
    }

    /** The brush bound to the player's held item, if any. */
    public static Brush current(LocalSession session) {
        Object bound = session.getBindings().get("brush");
        return bound instanceof Brush brush ? brush : null;
    }

    public static void bind(LocalSession session, Brush brush, Actor actor) {
        session.getBindings().put("brush", brush);
        session.getBindings().put("brush-item", actor.heldItem());
    }

    /** The brush bound to the left click of the held item, if any. */
    public static Brush currentSecondary(LocalSession session) {
        Object bound = session.getBindings().get("secondary-brush");
        return bound instanceof Brush brush ? brush : null;
    }

    /** Binds a brush to the left click, which is what {@code /tool secondary} does. */
    public static void bindSecondary(LocalSession session, Brush brush, Actor actor) {
        session.getBindings().put("secondary-brush", brush);
        session.getBindings().put("secondary-brush-item", actor.heldItem());
    }

    public static void unbind(LocalSession session) {
        session.getBindings().remove("brush");
        session.getBindings().remove("brush-item");
    }

    /**
     * Builds a brush from the values of a {@code /brush} command line.
     *
     * <p>Every FAWE brush name and alias maps onto exactly one construction here,
     * so the argument list and the switches of the generated {@code BrushTable}
     * are the whole surface of the command.</p>
     */
    public static Brush create(BrushParameters parameters) {
        String key = canonical(parameters.name());
        return switch (key) {
            case "sphere" -> {
                Brushes.SphereBrush brush = parameters.flag("f")
                        ? new Brushes.FallingSphereBrush(parameters.radius(), parameters.pattern(), parameters.mask())
                        : new Brushes.SphereBrush(parameters.radius(), parameters.pattern(), parameters.mask());
                brush.setHollow(parameters.flag("h"));
                yield brush;
            }
            case "cylinder" -> {
                Brushes.CylinderBrush brush = new Brushes.CylinderBrush(parameters.radius(), parameters.pattern(),
                        parameters.mask(), parameters.integer("height", 1), parameters.number("thickness", 0));
                brush.setHollow(parameters.flag("h"));
                yield brush;
            }
            case "smooth" -> {
                Brushes.SmoothBrush brush = new Brushes.SmoothBrush(parameters.radius(), parameters.flagMask());
                brush.setIterations(parameters.integer("iterations", 4));
                yield brush;
            }
            case "blendball" -> new Brushes.BlendBallBrush(parameters.radius(), parameters.mask(),
                    parameters.flag("a"), parameters.integer("minFreqDiff", 1), parameters.flagMask());
            case "height" -> terrain(parameters, false, false);
            case "cliff" -> terrain(parameters, true, false);
            case "flatten" -> terrain(parameters, false, true);
            case "heightmap" -> createHeightmapBrush(parameters);
            case "circle" -> new Brushes.CircleBrush(parameters.radius(), parameters.pattern(), parameters.mask(),
                    !parameters.string("filled", "false").equals("false"));
            case "raise", "lower" -> new Brushes.RaiseLowerBrush(parameters.radius(), parameters.pattern(),
                    key.equals("lower"), parameters.mask());
            case "layer" -> new Brushes.LayerBrush(parameters.radius(), parameters.pattern(), parameters.mask());
            case "line" -> {
                Brushes.LineBrush brush = new Brushes.LineBrush(parameters.radius(), parameters.pattern(),
                        parameters.mask());
                brush.setShell(parameters.flag("h"));
                brush.setSelect(parameters.flag("s"));
                brush.setFlat(parameters.flag("f"));
                yield brush;
            }
            case "spline" -> new Brushes.SplineBrush(parameters.radius(), parameters.pattern(), parameters.mask(),
                    false);
            case "surfacespline" -> {
                Brushes.SurfaceSplineBrush brush = new Brushes.SurfaceSplineBrush(parameters.radius(),
                        parameters.pattern(), parameters.mask());
                brush.setCurve(parameters.number("tension", 0), parameters.number("bias", 0),
                        parameters.number("continuity", 0), parameters.integer("quality", 10));
                yield brush;
            }
            case "catenary" -> {
                Brushes.CatenaryBrush brush = new Brushes.CatenaryBrush(parameters.radius(), parameters.pattern(),
                        parameters.mask());
                brush.setLengthFactor(parameters.number("lengthFactor", 1.2));
                brush.setShell(parameters.flag("h"));
                brush.setSelect(parameters.flag("s"));
                brush.setFacingDirection(parameters.flag("d"));
                yield brush;
            }
            case "scatter" -> new Brushes.ScatterBrush(parameters.radius(), parameters.pattern(), parameters.mask(),
                    parameters.integer("points", 5), parameters.integer("distance", 1), parameters.flag("o"));
            case "shatter" -> new Brushes.ShatterBrush(parameters.radius(), parameters.pattern(), parameters.mask());
            case "splatter" -> {
                Brushes.SplatterBrush brush = new Brushes.SplatterBrush(parameters.radius(), parameters.pattern(),
                        parameters.mask());
                brush.setPoints(parameters.integer("points", 1));
                yield brush;
            }
            case "rock" -> {
                Brushes.RockBrush brush = new Brushes.RockBrush(parameters.radius(), parameters.pattern(),
                        parameters.mask());
                brush.setShape(parameters.number("sphericity", 100), parameters.number("frequency", 30),
                        parameters.number("amplitude", 50));
                yield brush;
            }
            case "pull" -> {
                Brushes.PullBrush brush = new Brushes.PullBrush(parameters.radius(), parameters.pattern(),
                        parameters.mask());
                brush.setShape(parameters.integer("erodefaces", 6), parameters.integer("erodeRec", 0),
                        parameters.integer("fillFaces", 1), parameters.integer("fillRec", 1));
                yield brush;
            }
            case "stencil" -> {
                Brushes.StencilBrush brush = new Brushes.StencilBrush(parameters.radius(), parameters.pattern(),
                        parameters.mask(), loadImage(parameters.string("image", "")),
                        parameters.integer("rotation", 0), parameters.number("yscale", 1),
                        parameters.flag("w"), parameters.flag("r"));
                yield brush;
            }
            case "gravity" -> {
                Brushes.GravityBrush brush = new Brushes.GravityBrush(parameters.radius(), parameters.mask());
                // WorldEdit carries a height on -h, FAWE makes the same switch a
                // plain flag; a height, when given, is the more specific request.
                String height = parameters.string("height", "");
                if (!height.isEmpty()) {
                    brush.setHeight(parameters.integer("height", 0));
                } else {
                    brush.setFullHeight(parameters.flag("h"));
                }
                yield brush;
            }
            case "clipboard" -> new Brushes.ClipboardBrush(parameters.radius(), parameters.mask(),
                    parameters.flag("o"), parameters.flag("a"), parameters.flag("v"), parameters.flag("e"),
                    parameters.flag("b"), parameters.maskValue("sourceMask"), parameters.flag("r"));
            case "copypaste" -> new Brushes.CopyPastaBrush(parameters.radius(), parameters.flag("r"),
                    parameters.flag("a"));
            case "biome" -> {
                Brushes.BiomeBrush brush = new Brushes.BiomeBrush(parameters.radius(), parameters.mask());
                brush.setFullColumn(parameters.flag("c"));
                yield brush;
            }
            case "butcher" -> new Brushes.ButcherBrush(parameters.radius(), categories(parameters));
            case "forest", "structure", "feature" -> {
                Brushes.FeatureBrush brush = new Brushes.FeatureBrush(parameters.radius(), key, parameters.mask());
                brush.setFeature(parameters.string("type", ""));
                brush.setDensity(parameters.integer("density", 5));
                yield brush;
            }
            case "command" -> new Brushes.CommandBrush(parameters.radius(), parameters.string("input", ""),
                    parameters.flag("h"));
            case "scattercommand" -> new Brushes.ScatterCommandBrush(parameters.radius(),
                    parameters.string("commandStr", ""), parameters.flag("p"));
            case "populateschematic" -> {
                Brushes.PopulateSchematicBrush brush = new Brushes.PopulateSchematicBrush(parameters.radius(),
                        parameters.flagMask());
                brush.setSchematic(parameters.string("clipboardStr", ""));
                brush.setDensity(parameters.integer("density", 50));
                brush.setRandomRotation(parameters.flag("r"));
                yield brush;
            }
            case "surface" -> new Brushes.SurfaceBrush(parameters.radius(), parameters.pattern(), parameters.mask());
            case "sweep" -> new Brushes.SweepBrush(parameters.radius(), parameters.pattern(), parameters.mask());
            case "deform" -> {
                Brushes.DeformBrush brush = new Brushes.DeformBrush(parameters.radius(),
                        parameters.string("expression", ""));
                brush.setGameOrigin(parameters.flag("r"));
                brush.setPlacementOrigin(parameters.flag("o"));
                yield brush;
            }
            case "erode", "dilate", "morph" -> new Brushes.ErodeDilateBrush(parameters.radius(), key,
                    parameters.mask());
            case "extinguish" -> new Brushes.ExtinguishBrush(parameters.radius());
            case "snow" -> {
                Brushes.SnowBrush brush = new Brushes.SnowBrush(parameters.radius(), parameters.mask());
                brush.setStack(parameters.flag("s"));
                yield brush;
            }
            case "snowsmooth" -> new Brushes.SnowSmoothBrush(parameters.radius(),
                    parameters.integer("iterations", 1), parameters.integer("snowBlockCount", 1),
                    parameters.flagMask());
            case "item" -> new Brushes.ItemBrush(parameters.radius(), parameters.string("item", ""),
                    parameters.string("direction", "up"));
            case "recurse", "recursive" -> {
                Brushes.RecurseBrush brush = new Brushes.RecurseBrush(parameters.radius(), parameters.pattern(),
                        parameters.mask());
                brush.setDepthFirst(parameters.flag("d"));
                yield brush;
            }
            case "set", "image" -> new Brushes.SphereBrush(parameters.radius(), parameters.pattern(),
                    parameters.mask());
            default -> null;
        };
    }

    /** {@code /brush height|cliff|flatten <radius> [image] [rotation] [yscale]}. */
    private static Brush terrain(BrushParameters parameters, boolean cylinder, boolean flatten) {
        Brushes.HeightmapBrush brush = new Brushes.HeightmapBrush(parameters.radius(), parameters.pattern(),
                parameters.mask(), cylinder, parameters.number("yscale", 1));
        brush.setFlatten(flatten);
        brush.setImage(loadImage(parameters.string("image", "")));
        brush.setRotation(parameters.integer("rotation", 0));
        brush.setRandomRotation(parameters.flag("r"));
        brush.setLayers(parameters.flag("l"));
        // -s disables the smoothing pass FAWE runs on the raised terrain.
        brush.setSmooth(!parameters.flag("s"));
        return brush;
    }

    /** {@code /brush heightmap <image> [radius] [intensity] [-e] [-f] [-r]}. */
    private static Brush createHeightmapBrush(BrushParameters parameters) {
        String file = parameters.string("imageName", "");
        Images.PixelSource image = loadImage(file);
        if (image == null) {
            throw CommandRegistry.error("Image '" + file + "' not found in "
                    + com.maxlananas.fawebim.core.clipboard.Schematics.directory());
        }
        // -e erases instead of filling, so the brush places air.
        Pattern fill = parameters.flag("e")
                ? new com.maxlananas.fawebim.core.pattern.Patterns.Single(
                        com.maxlananas.fawebim.core.world.BlockState.registry().air())
                : parameters.pattern();
        Brushes.ImageHeightmapBrush brush = new Brushes.ImageHeightmapBrush(parameters.radius(), fill,
                parameters.mask(), image, 1);
        brush.setIntensity(parameters.integer("intensity", 5));
        brush.setFlatten(parameters.flag("f"));
        brush.setRandomize(parameters.flag("r"));
        return brush;
    }

    /** Loads a brush image from the schematic directory, or null when unnamed. */
    private static Images.PixelSource loadImage(String file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        Images.PixelSource image = Images.load(
                com.maxlananas.fawebim.core.clipboard.Schematics.directory().resolve(file));
        if (image == null) {
            throw CommandRegistry.error("Image '" + file + "' not found in "
                    + com.maxlananas.fawebim.core.clipboard.Schematics.directory());
        }
        return image;
    }

    /** The creature categories {@code /brush butcher} flags selected. */
    private static java.util.Set<Creatures.Category> categories(BrushParameters parameters) {
        if (parameters.flag("f")) {
            // -f is the shortcut for "-abgnpt": every category but the armor
            // stands, which upstream keeps behind -r so they survive.
            return Creatures.of(true, true, true, true, true, true, parameters.flag("r"), true);
        }
        return Creatures.of(parameters.flag("p"), parameters.flag("n"), parameters.flag("g"), parameters.flag("a"),
                parameters.flag("b"), parameters.flag("t"), parameters.flag("r"), parameters.flag("w"));
    }

    /** Maps every FAWE brush name or alias onto the key this factory switches on. */
    public static String canonical(String name) {
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "ball" -> "sphere";
            case "sphere2d", "cylinderbrush" -> "cylinder";
            case "flatcylinder" -> "cliff";
            case "blob" -> "rock";
            case "kill" -> "butcher";
            case "cat", "gravityline", "saggedline" -> "catenary";
            case "bb", "blend" -> "blendball";
            case "flatmap", "flat" -> "flatten";
            case "cyl" -> "cylinder";
            case "grav" -> "gravity";
            case "cp", "copypasta" -> "copypaste";
            case "splat" -> "splatter";
            case "spl", "curve" -> "spline";
            case "sspline", "sspl" -> "surfacespline";
            case "surf" -> "surface";
            case "sw", "vaesweep" -> "sweep";
            case "ex" -> "extinguish";
            case "partition", "split" -> "shatter";
            case "cmd" -> "command";
            case "scattercmd", "scmd", "scommand" -> "scattercommand";
            case "popschem", "populateschem", "pschem", "ps" -> "populateschematic";
            case "recursive" -> "recurse";
            default -> key;
        };
    }

    /** Creates a brush from a name and a radius only, used by the saved presets. */
    public static Brush preset(String name, double radius, Pattern fill) {
        String[] row = com.maxlananas.fawebim.core.command.BrushTable.byName(canonical(name));
        if (row == null) {
            return null;
        }
        return create(BrushParameters.of(row, radius, fill, BrushOptions.empty()));
    }

    /**
     * Every brush {@code /brush <name>} accepts, paired with the name the
     * factory is asked for. Derived from the generated signatures, so a brush
     * cannot exist without a command line.
     */
    public static List<String[]> commands() {
        List<String[]> commands = new ArrayList<>();
        for (String[] row : com.maxlananas.fawebim.core.command.BrushTable.BRUSHES) {
            commands.add(new String[]{row[0], row[0]});
            for (String alias : BrushParameters.aliases(row)) {
                if (canonical(alias).equals(canonical(row[0]))) {
                    commands.add(new String[]{alias, row[0]});
                }
            }
        }
        return commands;
    }

    /** The pattern used when a brush command omits it. */
    public static Pattern defaultPattern() {
        return new Patterns.Single(BlockState.registry().defaultState("minecraft:stone"));
    }

    /** Feedback message describing a bound brush. */
    public static Msg describe(Brush brush) {
        return Msg.info("Brush: " + brush.getClass().getSimpleName() + " (" + brush.describe() + ")");
    }
}
