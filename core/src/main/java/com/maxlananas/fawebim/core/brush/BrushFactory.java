package com.maxlananas.fawebim.core.brush;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.command.Ctx;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;

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
     * Builds a brush by name. Returns null when the name is unknown.
     *
     * @param pattern the fill pattern (null for the clipboard brush)
     */
    public static Brush create(String name, double radius, Pattern pattern, Ctx ctx) {
        String key = name.toLowerCase(Locale.ROOT);
        BrushSettings settings = new BrushSettings();
        settings.setSize((int) Math.max(1, Math.round(radius)));
        settings.setFill(pattern);
        if (ctx != null) {
            settings.setRange((int) ctx.session().getMaxBrushRange());
        }
        Mask mask = ctx == null ? null : ctx.session().getMask();
        Brush brush = switch (key) {
            case "sphere", "ball" -> new Brushes.SphereBrush(radius, pattern, mask);
            case "cylinder", "sphere2d", "cylinderbrush" -> new Brushes.CylinderBrush(radius, pattern, mask);
            case "smooth" -> new Brushes.SmoothBrush(radius, mask);
            case "blendball" -> new Brushes.BlendBallBrush(radius, mask);
            case "flatten" -> new Brushes.FlattenBrush(radius, pattern, mask);
            case "height" -> new Brushes.HeightmapBrush(radius, pattern, mask, false, 1);
            case "heightmap" -> createHeightmapBrush(radius, pattern, mask, ctx);
            case "cliff" -> new Brushes.HeightmapBrush(radius, pattern, mask, true, 1);
            case "circle" -> new Brushes.CircleBrush(radius, pattern, mask, true);
            case "raise", "lower" -> new Brushes.RaiseLowerBrush(radius, pattern, key.equals("lower"), mask);
            case "layer" -> new Brushes.LayerBrush(radius, pattern, mask);
            case "line" -> new Brushes.LineBrush(radius, pattern, mask);
            case "spline" -> new Brushes.SplineBrush(radius, pattern, mask, false);
            case "surfacespline" -> new Brushes.SplineBrush(radius, pattern, mask, true);
            case "catenary" -> new Brushes.CatenaryBrush(radius, pattern, mask);
            case "scatter" -> new Brushes.ScatterBrush(radius, pattern, mask);
            case "shatter" -> new Brushes.ShatterBrush(radius, pattern, mask);
            case "splatter" -> new Brushes.SplatterBrush(radius, pattern, mask);
            case "rock" -> new Brushes.RockBrush(radius, pattern, mask);
            case "blob" -> new Brushes.BlobBrush(radius, pattern, mask);
            case "pull" -> new Brushes.PullBrush(radius, pattern, mask);
            case "stencil" -> new Brushes.StencilBrush(radius, pattern, mask);
            case "gravity" -> new Brushes.GravityBrush(radius, mask);
            case "clipboard", "copypaste" -> new Brushes.ClipboardBrush(radius, mask,
                    ctx != null && ctx.hasFlag("o"));
            case "biome" -> new Brushes.BiomeBrush(radius, mask);
            case "butcher" -> new Brushes.ButcherBrush(radius);
            case "forest" -> new Brushes.ForestBrush(radius, mask);
            case "command" -> new Brushes.CommandBrush(radius, ctx == null ? "" : ctx.joined(2));
            case "scattercommand" -> new Brushes.ScatterCommandBrush(radius, ctx == null ? "" : ctx.joined(2));
            case "populateschematic" -> new Brushes.PopulateSchematicBrush(radius);
            case "surface" -> new Brushes.SurfaceBrush(radius, pattern, mask);
            case "sweep" -> new Brushes.SweepBrush(radius, pattern, mask);
            case "deform" -> new Brushes.DeformBrush(radius, ctx == null ? "" : ctx.joined(2));
            case "erode", "dilate", "morph" -> new Brushes.ErodeDilateBrush(radius, key, mask);
            case "extinguish" -> new Brushes.ExtinguishBrush(radius);
            case "snow", "snowsmooth" -> new Brushes.SnowBrush(radius, key.equals("snowsmooth"), mask);
            case "item" -> new Brushes.SphereBrush(radius, pattern, mask);
            case "recurse" -> new Brushes.RecurseBrush(radius, pattern, mask);
            case "feature", "structure" -> new Brushes.FeatureBrush(radius, key, mask);
            case "set" -> new Brushes.SphereBrush(radius, pattern, mask);
            case "image" -> new Brushes.SphereBrush(radius, pattern, mask);
            default -> null;
        };
        return brush;
    }

    /** {@code /brush heightmap <image> [yscale]} needs the image file to exist. */
    private static Brush createHeightmapBrush(double radius, Pattern pattern, Mask mask, Ctx ctx) {
        if (ctx == null) {
            return null;
        }
        // /brush heightmap <radius> <pattern> <image> [yscale].
        String file = ctx.arg(2, "");
        com.maxlananas.fawebim.core.util.Images.PixelSource image =
                com.maxlananas.fawebim.core.util.Images.load(
                        com.maxlananas.fawebim.core.clipboard.Schematics.directory().resolve(file));
        if (image == null) {
            throw com.maxlananas.fawebim.core.command.CommandRegistry.error(
                    "Image '" + file + "' not found in " + com.maxlananas.fawebim.core.clipboard.Schematics.directory());
        }
        return new Brushes.ImageHeightmapBrush(radius, pattern, mask, image, ctx.doubleArg(3, 1));
    }

    /** Creates a brush from a name only, used by saved brush presets. */
    public static Brush preset(String name, double radius, Pattern fill) {
        return create(name, radius, fill, null);
    }

    /**
     * Every brush {@code /brush <name>} accepts, paired with the key the factory
     * uses. The command registration iterates this list, so a brush cannot exist
     * without being reachable from the command line.
     */
    public static final List<String[]> COMMANDS = List.of(
            new String[]{"sphere", "sphere"},
            new String[]{"ball", "sphere"},
            new String[]{"smooth", "smooth"},
            new String[]{"blendball", "blendball"},
            new String[]{"flatten", "flatten"},
            new String[]{"height", "height"},
            new String[]{"heightmap", "heightmap"},
            new String[]{"raise", "raise"},
            new String[]{"lower", "lower"},
            new String[]{"layer", "layer"},
            new String[]{"line", "line"},
            new String[]{"spline", "spline"},
            new String[]{"surfacespline", "surfacespline"},
            new String[]{"catenary", "catenary"},
            new String[]{"scatter", "scatter"},
            new String[]{"shatter", "shatter"},
            new String[]{"splatter", "splatter"},
            new String[]{"rock", "rock"},
            new String[]{"blob", "blob"},
            new String[]{"pull", "pull"},
            new String[]{"stencil", "stencil"},
            new String[]{"gravity", "gravity"},
            new String[]{"cylinder", "cylinder"},
            new String[]{"circle", "circle"},
            new String[]{"clipboard", "clipboard"},
            new String[]{"copypaste", "copypaste"},
            new String[]{"biome", "biome"},
            new String[]{"butcher", "butcher"},
            new String[]{"forest", "forest"},
            new String[]{"command", "command"},
            new String[]{"scattercommand", "scattercommand"},
            new String[]{"populateschematic", "populateschematic"},
            new String[]{"surface", "surface"},
            new String[]{"sweep", "sweep"},
            new String[]{"deform", "deform"},
            new String[]{"erode", "erode"},
            new String[]{"dilate", "dilate"},
            new String[]{"morph", "morph"},
            new String[]{"extinguish", "extinguish"},
            new String[]{"snow", "snow"},
            new String[]{"snowsmooth", "snowsmooth"},
            new String[]{"item", "item"},
            new String[]{"recurse", "recurse"},
            new String[]{"recursive", "recurse"},
            new String[]{"feature", "feature"},
            new String[]{"structure", "structure"},
            new String[]{"cliff", "cliff"},
            new String[]{"flatcylinder", "cliff"},
            new String[]{"set", "set"},
            new String[]{"image", "image"},
            new String[]{"stencil", "stencil"});

    /** The pattern used when a brush command omits it. */
    public static Pattern defaultPattern() {
        return new Patterns.Single(BlockState.registry().defaultState("minecraft:stone"));
    }

    /** Feedback message describing a bound brush. */
    public static Msg describe(Brush brush) {
        return Msg.info("Brush: " + brush.getClass().getSimpleName() + " (" + brush.describe() + ")");
    }
}
