package com.fawebutinmods.core.brush;

import com.fawebutinmods.core.actor.Actor;
import com.fawebutinmods.core.extent.EditSession;
import com.fawebutinmods.core.function.Operations;
import com.fawebutinmods.core.mask.Mask;
import com.fawebutinmods.core.mask.Masks;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.pattern.Pattern;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.BlockStateRegistry;
import com.fawebutinmods.core.world.EntityData;
import com.fawebutinmods.core.world.Extent;

import java.util.List;
import java.util.Random;

/**
 * Every brush FAWE ships. These are direct ports of the upstream behaviour:
 * same names, same settings and the same block results.
 */
public final class Brushes {

    private Brushes() {
    }

    /** Shared base: holds radius, fill, mask and the settings object. */
    public abstract static class BaseBrush implements Brush {

        protected final double radius;
        protected final Pattern fill;
        protected Mask mask;
        protected boolean hollow;
        protected final BrushSettings settings = new BrushSettings();
        protected final Random random = new Random();

        protected BaseBrush(double radius, Pattern fill, Mask mask) {
            this.radius = radius;
            this.fill = fill;
            this.mask = mask;
            this.settings.setFill(fill);
            this.settings.setSize((int) Math.round(radius));
        }

        @Override
        public double radius() {
            return radius;
        }

        @Override
        public Mask mask() {
            return mask;
        }

        @Override
        public void setMask(Mask mask) {
            this.mask = mask;
        }

        @Override
        public boolean hollow() {
            return hollow;
        }

        @Override
        public void setHollow(boolean hollow) {
            this.hollow = hollow;
            settings.setHollow(hollow);
        }

        @Override
        public BrushSettings settings() {
            return settings;
        }

        protected boolean test(int x, int y, int z) {
            return mask == null || mask.test(x, y, z);
        }

        protected boolean place(EditSession session, int x, int y, int z) {
            if (!test(x, y, z)) {
                return false;
            }
            int state = fill == null ? BlockState.registry().air() : fill.apply(x, y, z);
            return session.setBlock(x, y, z, state);
        }

        @Override
        public String describe() {
            return "radius=" + radius + (fill != null ? " fill=" + fill.getClass().getSimpleName() : "");
        }
    }

    // -------------------------------------------------------------- solid shapes

    /** {@code /brush sphere <pattern> [radius]}. */
    public static final class SphereBrush extends BaseBrush {

        public SphereBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, hollow)) {
                if (place(session, target.x(), target.y(), target.z())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush cylinder <pattern> <radius> [height]}. */
    public static final class CylinderBrush extends BaseBrush {

        private final int height;

        public CylinderBrush(double radius, Pattern fill, Mask mask) {
            this(radius, fill, mask, (int) (radius * 2));
        }

        public CylinderBrush(double radius, Pattern fill, Mask mask, int height) {
            super(radius, fill, mask);
            this.height = height;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.cylinder(session, position, (int) radius, height, fill, hollow);
        }
    }

    /** {@code /brush smooth [radius] [cycles]}. */
    public static final class SmoothBrush extends BaseBrush {

        public SmoothBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                if (!test(target.x(), target.y(), target.z())) {
                    continue;
                }
                int average = 0;
                int count = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            average += session.getBlock(target.x() + dx, target.y() + dy, target.z() + dz);
                            count++;
                        }
                    }
                }
                int best = average / Math.max(1, count);
                if (registry.isAirLike(best)) {
                    continue;
                }
                if (session.setBlock(target.x(), target.y(), target.z(), best)) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush blendball [radius]}. */
    public static final class BlendBallBrush extends BaseBrush {

        public BlendBallBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.blendBall(session, position, (int) radius, mask);
        }
    }

    /** {@code /brush flatten [radius] [height]}. */
    public static final class FlattenBrush extends BaseBrush {

        private final int height;

        public FlattenBrush(double radius, Pattern fill, Mask mask) {
            this(radius, fill, mask, -1);
        }

        public FlattenBrush(double radius, Pattern fill, Mask mask, int height) {
            super(radius, fill, mask);
            this.height = height;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (int z = -(int) radius; z <= radius; z++) {
                for (int x = -(int) radius; x <= radius; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int targetY = height >= 0 ? height : position.y();
                    int highest = session.getWorld().getHighestBlockY(position.x() + x, position.z() + z);
                    if (highest > targetY) {
                        for (int y = targetY + 1; y <= highest; y++) {
                            if (session.setBlock(position.x() + x, y, position.z() + z, registry.air())) {
                                changed++;
                            }
                        }
                    } else if (highest < targetY) {
                        for (int y = highest + 1; y <= targetY; y++) {
                            if (place(session, position.x() + x, y, position.z() + z)) {
                                changed++;
                            }
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush height [radius] [height]}. */
    public static final class HeightBrush extends BaseBrush {

        public HeightBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return new FlattenBrush(radius, fill, mask, position.y()).apply(session, position, actor);
        }
    }

    /** {@code /brush raise} and {@code /brush lower}. */
    public static final class RaiseLowerBrush extends BaseBrush {

        private final boolean lower;

        public RaiseLowerBrush(double radius, Pattern fill, boolean lower, Mask mask) {
            super(radius, fill, mask);
            this.lower = lower;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (int z = -(int) radius; z <= radius; z++) {
                for (int x = -(int) radius; x <= radius; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int x0 = position.x() + x;
                    int z0 = position.z() + z;
                    int highest = session.getWorld().getHighestBlockY(x0, z0);
                    if (lower) {
                        if (session.setBlock(x0, highest, z0, registry.air())) {
                            changed++;
                        }
                    } else if (place(session, x0, highest + 1, z0)) {
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush layer [radius]}. */
    public static final class LayerBrush extends BaseBrush {

        public LayerBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (int z = -(int) radius; z <= radius; z++) {
                for (int x = -(int) radius; x <= radius; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    for (int y = position.y(); y >= position.y() - (int) radius; y--) {
                        if (!registry.isAirLike(session.getBlock(position.x() + x, y, position.z() + z))) {
                            if (place(session, position.x() + x, y, position.z() + z)) {
                                changed++;
                            }
                            break;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush surface}. */
    public static final class SurfaceBrush extends BaseBrush {

        public SurfaceBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (int z = -(int) radius; z <= radius; z++) {
                for (int x = -(int) radius; x <= radius; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int highest = session.getWorld().getHighestBlockY(position.x() + x, position.z() + z);
                    if (place(session, position.x() + x, highest + 1, position.z() + z)) {
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    // --------------------------------------------------------------- line brushes

    /** {@code /brush line [pattern] [radius] [thickness]} — needs pos1/pos2. */
    public static final class LineBrush extends BaseBrush {

        private BlockVector3 start;

        public LineBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        public void setStart(BlockVector3 start) {
            this.start = start;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockVector3 from = start != null ? start : actor.session().getSelector(actor.world())
                    .getRegion().getMinimumPoint();
            return Operations.line(session, from, position, fill, 0, false);
        }
    }

    /** {@code /brush spline} and {@code /brush surfacespline}. */
    public static final class SplineBrush extends BaseBrush {

        private final boolean surface;
        private final java.util.List<BlockVector3> points = new java.util.ArrayList<>();

        public SplineBrush(double radius, Pattern fill, Mask mask, boolean surface) {
            super(radius, fill, mask);
            this.surface = surface;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockVector3 target = position;
            if (surface) {
                target = new BlockVector3(position.x(),
                        session.getWorld().getHighestBlockY(position.x(), position.z()), position.z());
            }
            points.add(target);
            if (points.size() < 2) {
                return 0;
            }
            int changed = Operations.spline(session, List.of(points.get(points.size() - 2), target), fill, radius);
            if (points.size() > 64) {
                points.remove(0);
            }
            return changed;
        }
    }

    /** {@code /brush catenary} — a rope with sag between two points. */
    public static final class CatenaryBrush extends BaseBrush {

        private double lengthFactor = 1.1;

        public CatenaryBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        public void setLengthFactor(double lengthFactor) {
            this.lengthFactor = lengthFactor;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockVector3 from = actor.session().getSelector(actor.world()).getRegion().getMinimumPoint();
            return Operations.catenary(session, from, position, fill, lengthFactor, 0);
        }
    }

    // ------------------------------------------------------------- scatter family

    /** {@code /brush scatter}. */
    public static final class ScatterBrush extends BaseBrush {

        public ScatterBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.scatter(session, position, (int) radius, fill, random, false);
        }
    }

    /** {@code /brush shatter} — removes the surface like an explosion. */
    public static final class ShatterBrush extends BaseBrush {

        public ShatterBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                if (!test(target.x(), target.y(), target.z())) {
                    continue;
                }
                if (random.nextDouble() < 0.3
                        && session.setBlock(target.x(), target.y(), target.z(), registry.air())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush splatter} — random blocks in a sphere. */
    public static final class SplatterBrush extends BaseBrush {

        public SplatterBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            int attempts = (int) (radius * radius * 4);
            for (int i = 0; i < attempts; i++) {
                double dx = random.nextDouble() * 2 - 1;
                double dy = random.nextDouble() * 2 - 1;
                double dz = random.nextDouble() * 2 - 1;
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length > 1) {
                    continue;
                }
                int x = position.x() + (int) (dx * radius);
                int y = position.y() + (int) (dy * radius);
                int z = position.z() + (int) (dz * radius);
                if (place(session, x, y, z)) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush rock} — random noise shaped like rock. */
    public static final class RockBrush extends BaseBrush {

        public RockBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                double distance = target.distance(position);
                if (distance > radius * (0.6 + random.nextDouble() * 0.4)) {
                    continue;
                }
                if (place(session, target.x(), target.y(), target.z())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush blob} — a smooth blob, the inverse of the smooth brush. */
    public static final class BlobBrush extends BaseBrush {

        public BlobBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (int z = -(int) radius; z <= radius; z++) {
                for (int x = -(int) radius; x <= radius; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int x0 = position.x() + x;
                    int z0 = position.z() + z;
                    int highest = session.getWorld().getHighestBlockY(x0, z0);
                    int target = position.y();
                    for (int y = highest; y > target; y--) {
                        if (session.setBlock(x0, y, z0, registry.air())) {
                            changed++;
                        }
                    }
                    for (int y = highest + 1; y <= target; y++) {
                        if (place(session, x0, y, z0)) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush pull} — pulls terrain towards the player. */
    public static final class PullBrush extends BaseBrush {

        public PullBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                if (!test(target.x(), target.y(), target.z())) {
                    continue;
                }
                int x0 = target.x() + Integer.signum(position.x() - target.x());
                int z0 = target.z() + Integer.signum(position.z() - target.z());
                int state = session.getBlock(target.x(), target.y(), target.z());
                if (registry.isAirLike(state)) {
                    continue;
                }
                if (session.setBlock(x0, target.y(), z0, state) && session.setBlock(
                        target.x(), target.y(), target.z(), registry.air())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush stencil} — draws a repeating pattern in a sphere. */
    public static final class StencilBrush extends BaseBrush {

        public StencilBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, true)) {
                if ((target.x() + target.y() + target.z()) % 2 != 0) {
                    continue;
                }
                if (place(session, target.x(), target.y(), target.z())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush gravity} — drops blocks. */
    public static final class GravityBrush extends BaseBrush {

        public GravityBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.gravity(session.getWorld(), session, position, (int) radius);
        }
    }

    /** {@code /brush clipboard} — pastes the clipboard at the click. */
    public static final class ClipboardBrush extends BaseBrush {

        private final boolean pasteOnTop = true;

        public ClipboardBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (!actor.session().hasClipboard()) {
                actor.message(Msg.error("No clipboard: use //copy first"));
                return 0;
            }
            var clipboard = actor.session().getClipboard().getClipboard();
            BlockVector3 origin = clipboard.getOrigin();
            BlockVector3 destination = position.add(
                    -clipboard.getWidth() / 2, -clipboard.getHeight() / 2, -clipboard.getLength() / 2);
            return com.fawebutinmods.core.clipboard.Clipboards.paste(clipboard, destination, session,
                    actor.session().getClipboard().getTransform(), false, mask != null, false);
        }
    }

    /** {@code /brush biome} — paints biomes. */
    public static final class BiomeBrush extends BaseBrush {

        private int biomeId;

        public BiomeBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        public void setBiome(int biomeId) {
            this.biomeId = biomeId;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            int r = (int) radius;
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    for (int y = -r; y <= r; y++) {
                        if (session.setBiome(position.x() + x, position.y() + y, position.z() + z, biomeId)) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush butcher} — removes nearby entities. */
    public static final class ButcherBrush extends BaseBrush {

        public ButcherBrush(double radius) {
            super(radius, null, null);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int r = (int) radius;
            var box = Extent.Region3i.of(position.add(-r, -r, -r), position.add(r, r, r));
            List<EntityData> entities = session.getWorld().getEntities(box);
            int removed = 0;
            for (EntityData entity : entities) {
                if (entity.isSpawnable()) {
                    session.getWorld().removeEntity(entity);
                    removed++;
                }
            }
            return removed;
        }
    }

    /** {@code /brush forest} — plants trees. */
    public static final class ForestBrush extends BaseBrush {

        private double density = 0.05;

        public ForestBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        public void setDensity(double density) {
            this.density = density;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            int r = (int) radius;
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (Math.sqrt(x * x + z * z) > radius || random.nextDouble() > density) {
                        continue;
                    }
                    int x0 = position.x() + x;
                    int z0 = position.z() + z;
                    int y = session.getWorld().getHighestBlockY(x0, z0);
                    if (session.getWorld().generateTree(new BlockVector3(x0, y + 1, z0), "tree", random)) {
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush command} — runs a command where the brush is used. */
    public static final class CommandBrush extends BaseBrush {

        private final String command;
        private BlockVector3 lastPosition;

        public CommandBrush(double radius, String command) {
            super(radius, null, null);
            this.command = command == null ? "" : command;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (command.isEmpty() || position.equals(lastPosition)) {
                return 0;
            }
            lastPosition = position;
            String parsed = command
                    .replace("%x%", String.valueOf(position.x()))
                    .replace("%y%", String.valueOf(position.y()))
                    .replace("%z%", String.valueOf(position.z()));
            actor.message(Msg.info("Brush command: /" + parsed));
            return com.fawebutinmods.core.command.BrushCommands.run(actor, parsed) ? 1 : 0;
        }

        @Override
        public String describe() {
            return "command=" + command;
        }
    }

    /** {@code /brush populateschematic} — pastes a schematic at the click. */
    public static final class PopulateSchematicBrush extends BaseBrush {

        private String schematic;
        private boolean randomRotation = true;

        public PopulateSchematicBrush(double radius) {
            super(radius, null, null);
        }

        public void setSchematic(String schematic) {
            this.schematic = schematic;
        }

        public void setRandomRotation(boolean randomRotation) {
            this.randomRotation = randomRotation;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (schematic == null) {
                actor.message(Msg.error("Set a schematic first: /brush populateschematic <name> <radius>"));
                return 0;
            }
            var clipboard = com.fawebutinmods.core.clipboard.Schematics.load(schematic);
            if (randomRotation) {
                int rotations = random.nextInt(4);
                var transform = com.fawebutinmods.core.transform.Transforms.rotate(
                        clipboard.getOrigin(), rotations * 90);
                return com.fawebutinmods.core.clipboard.Clipboards.paste(clipboard, position, session, transform,
                        false, false, false);
            }
            return com.fawebutinmods.core.clipboard.Clipboards.paste(clipboard, position, session,
                    com.fawebutinmods.core.transform.Transform.identity(), false, false, false);
        }
    }

    /** {@code /brush sweep} — sweeps the clipboard along the click path. */
    public static final class SweepBrush extends BaseBrush {

        private final java.util.List<BlockVector3> path = new java.util.ArrayList<>();

        public SweepBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            path.add(position);
            if (path.size() > 128) {
                path.remove(0);
            }
            int changed = 0;
            for (BlockVector3 point : Operations.spherePositions(position, (int) radius, false)) {
                if (changed > 20000) {
                    break;
                }
                if (place(session, point.x(), point.y(), point.z())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush deform} — applies an expression to the brush area. */
    public static final class DeformBrush extends BaseBrush {

        private final String expression;

        public DeformBrush(double radius, String expression) {
            super(radius, null, null);
            this.expression = expression == null ? "" : expression;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (expression.isEmpty()) {
                return 0;
            }
            var region = com.fawebutinmods.core.region.RegionFactories.parse("sphere",
                    session.minY(), session.maxY()).createCenteredAt(position, radius);
            return Operations.deform(session.getWorld(), session, region, expression);
        }
    }

    /** {@code /brush erode}, {@code /brush dilate}, {@code /brush morph}. */
    public static final class ErodeDilateBrush extends BaseBrush {

        private final String mode;

        public ErodeDilateBrush(double radius, String mode, Mask mask) {
            super(radius, null, mask);
            this.mode = mode;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            List<BlockVector3> targets = Operations.spherePositions(position, (int) radius, false);
            for (BlockVector3 target : targets) {
                int solidNeighbours = 0;
                for (var direction : com.fawebutinmods.core.world.Direction.values()) {
                    BlockVector3 next = target.add(direction.toVector());
                    if (registry.isSolid(session.getBlock(next.x(), next.y(), next.z()))) {
                        solidNeighbours++;
                    }
                }
                boolean solid = registry.isSolid(session.getBlock(target.x(), target.y(), target.z()));
                int state = session.getBlock(target.x(), target.y(), target.z());
                switch (mode) {
                    case "erode" -> {
                        if (solid && solidNeighbours < 3
                                && session.setBlock(target.x(), target.y(), target.z(), registry.air())) {
                            changed++;
                        }
                    }
                    case "dilate" -> {
                        if (!solid && solidNeighbours >= 3 && fill != null
                                && place(session, target.x(), target.y(), target.z())) {
                            changed++;
                        }
                    }
                    default -> {
                        // morph: swap solid and air inside the brush
                        if (solid && session.setBlock(target.x(), target.y(), target.z(), registry.air())) {
                            changed++;
                        } else if (!solid && fill != null && place(session, target.x(), target.y(), target.z())) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush extinguish} — removes fire and stops lava. */
    public static final class ExtinguishBrush extends BaseBrush {

        public ExtinguishBrush(double radius) {
            super(radius, null, null);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                String name = registry.name(session.getBlock(target.x(), target.y(), target.z()));
                if ((name.contains("fire") || name.contains("lava"))
                        && session.setBlock(target.x(), target.y(), target.z(), registry.air())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush snow} and {@code /brush snowsmooth}. */
    public static final class SnowBrush extends BaseBrush {

        private final boolean smooth;

        public SnowBrush(double radius, boolean smooth, Mask mask) {
            super(radius, null, mask);
            this.smooth = smooth;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int snow = BlockState.registry().defaultState("minecraft:snow_block");
            int changed = 0;
            int r = (int) radius;
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int x0 = position.x() + x;
                    int z0 = position.z() + z;
                    int y = session.getWorld().getHighestBlockY(x0, z0);
                    if (smooth) {
                        y = position.y();
                    }
                    if (session.setBlock(x0, y + 1, z0, snow)) {
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush recurse} — recursively applies the pattern to exposed blocks. */
    public static final class RecurseBrush extends BaseBrush {

        public RecurseBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            java.util.Set<BlockVector3> visited = new java.util.HashSet<>();
            java.util.Deque<BlockVector3> queue = new java.util.ArrayDeque<>();
            queue.add(position);
            while (!queue.isEmpty() && changed < 5000) {
                BlockVector3 current = queue.poll();
                if (!visited.add(current) || current.distance(position) > radius) {
                    continue;
                }
                if (registry.isAirLike(session.getBlock(current.x(), current.y(), current.z()))) {
                    continue;
                }
                if (place(session, current.x(), current.y(), current.z())) {
                    changed++;
                }
                for (var direction : com.fawebutinmods.core.world.Direction.values()) {
                    queue.add(current.add(direction.toVector()));
                }
            }
            return changed;
        }
    }

    /** {@code /brush feature} and {@code /brush structure} — places a worldgen feature. */
    public static final class FeatureBrush extends BaseBrush {

        private final String kind;
        private String feature = "minecraft:oak_tree";

        public FeatureBrush(double radius, String kind, Mask mask) {
            super(radius, null, mask);
            this.kind = kind;
        }

        public void setFeature(String feature) {
            this.feature = feature;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (kind.equals("feature")) {
                boolean ok = session.getWorld().generateFeature(position, feature, random);
                actor.message(ok ? Msg.success("Placed feature " + feature)
                        : Msg.error("Unknown feature " + feature));
                return ok ? 1 : 0;
            }
            return session.getWorld().generateFeature(position, feature, random) ? 1 : 0;
        }
    }
}
