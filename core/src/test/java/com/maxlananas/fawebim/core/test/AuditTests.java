package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.command.Ctx;
import com.maxlananas.fawebim.core.command.Parsers;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.math.BlockVector3;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The defects of the audit, one check each, so that a fix that is undone is
 * caught by the run rather than by a player.
 *
 * <p>Each of these failed before its fix: the biome walk of
 * {@code //setbiome} stopped one cell short of the top of the selection, and
 * the angle masks of {@code #angle} and {@code #surfaceangle} dropped the sign
 * of a negative limit, so {@code #angle[-45d][45d]} selected what
 * {@code #angle[45d][45d]} selects.</p>
 */
final class AuditTests {

    private AuditTests() {
    }

    static void run() {
        section("audit: biomes and angles");
        CommandManager.get().initialise();
        theTopBiomeCellOfASelectionIsWritten();
        aNegativeAngleLimitKeepsItsSign();
        aRangeWrittenWithADashIsTwoLimits();
        aSurfaceAngleIsInDegrees();
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }

    /**
     * A biome cell covers four blocks on each axis, and the walk of
     * {@code //setbiome} stepped by four from the lowest block of the selection
     * instead of from the first cell boundary: over 70..80 on Y it reached the
     * cells at 68, 72 and 76 and never 80, so the top four blocks of every
     * column kept the biome they had.
     */
    private static void theTopBiomeCellOfASelectionIsWritten() {
        TestWorld world = new TestWorld("audit-biome");
        world.fillFlat(64);
        TestActor actor = new TestActor("AuditBiome", world, new BlockVector3(0, 70, 0));
        answer(actor, "//pos1 0,70,0");
        answer(actor, "//pos2 3,80,3");
        String report = answer(actor, "//setbiome minecraft:desert");
        // One cell on X and on Z (0..3 is inside the cell that starts at 0), and
        // the four cells the height covers: 68, 72, 76 and 80.
        check("//setbiome writes the top biome cell of the selection, said: " + report,
                report.contains("4 biome cells"));
    }

    /** {@code #angle[-45d][45d]} is a tangent of -1 to 1, not 45 to 45. */
    private static void aNegativeAngleLimitKeepsItsSign() {
        TestWorld world = new TestWorld("audit-angle");
        world.fillFlat(64);
        TestActor actor = new TestActor("AuditAngle", world, new BlockVector3(0, 70, 0));
        Ctx ctx = CommandManager.get().registry().context(actor, "/gmask");
        double[] limits = limits(Parsers.mask("#angle[-45d][45d]", ctx));
        check("a negative degree limit is a negative tangent, got " + limits[0],
                Math.abs(limits[0] + 1.0) < 1e-9);
        check("a positive degree limit is a positive tangent, got " + limits[1],
                Math.abs(limits[1] - 1.0) < 1e-9);
    }

    /** {@code #angle[0d-90d]} is the older range form and is still two limits. */
    private static void aRangeWrittenWithADashIsTwoLimits() {
        TestWorld world = new TestWorld("audit-range");
        world.fillFlat(64);
        TestActor actor = new TestActor("AuditRange", world, new BlockVector3(0, 70, 0));
        Ctx ctx = CommandManager.get().registry().context(actor, "/gmask");
        double[] limits = limits(Parsers.mask("#angle[0d-90d]", ctx));
        check("the range form starts at a tangent of 0, got " + limits[0], Math.abs(limits[0]) < 1e-9);
        check("the range form covers every slope, got " + limits[1], limits[1] > 1e15);
    }

    /** {@code #surfaceangle} counts in degrees and takes a negative limit. */
    private static void aSurfaceAngleIsInDegrees() {
        TestWorld world = new TestWorld("audit-surface");
        world.fillFlat(64);
        TestActor actor = new TestActor("AuditSurface", world, new BlockVector3(0, 70, 0));
        Ctx ctx = CommandManager.get().registry().context(actor, "/gmask");
        Mask mask = Parsers.mask("#surfaceangle[-30d][60d]", ctx);
        double[] limits = limitsSurface(mask);
        check("a surface angle keeps its degrees, got " + limits[0], Math.abs(limits[0] + 30) < 1e-9);
        check("a surface angle keeps its top, got " + limits[1], Math.abs(limits[1] - 60) < 1e-9);
    }

    private static double[] limits(Mask mask) {
        return read(mask, Masks.AngleMask.class, "min", "max");
    }

    private static double[] limitsSurface(Mask mask) {
        return read(mask, Masks.SurfaceAngleMask.class, "min", "max");
    }

    /** The two limits of a mask, which no command reports and the shape depends on. */
    private static double[] read(Mask mask, Class<?> type, String lower, String upper) {
        try {
            java.lang.reflect.Field min = type.getDeclaredField(lower);
            java.lang.reflect.Field max = type.getDeclaredField(upper);
            min.setAccessible(true);
            max.setAccessible(true);
            return new double[]{min.getDouble(mask), max.getDouble(mask)};
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(type.getSimpleName() + " has no " + lower + "/" + upper, failure);
        }
    }
}
