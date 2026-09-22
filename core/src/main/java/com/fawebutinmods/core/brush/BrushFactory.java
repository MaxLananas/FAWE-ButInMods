package com.fawebutinmods.core.brush;

import com.fawebutinmods.core.actor.Actor;
import com.fawebutinmods.core.command.Ctx;
import com.fawebutinmods.core.mask.Mask;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.pattern.Pattern;
import com.fawebutinmods.core.pattern.Patterns;
import com.fawebutinmods.core.session.LocalSession;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.world.BlockState;

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
            case "height" -> new Brushes.HeightBrush(radius, pattern, mask);
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
            case "clipboard", "copypaste" -> new Brushes.ClipboardBrush(radius, mask);
            case "biome" -> new Brushes.BiomeBrush(radius, mask);
            case "butcher" -> new Brushes.ButcherBrush(radius);
            case "forest" -> new Brushes.ForestBrush(radius, mask);
            case "command" -> new Brushes.CommandBrush(radius, ctx == null ? "" : ctx.joined(2));
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
            case "cliff", "set" -> new Brushes.SphereBrush(radius, pattern, mask);
            default -> null;
        };
        return brush;
    }

    /** Creates a brush from a name only, used by saved brush presets. */
    public static Brush preset(String name, double radius, Pattern fill) {
        return create(name, radius, fill, null);
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
