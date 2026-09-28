package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.region.EllipsoidRegion;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.region.RegionSelector;
import com.maxlananas.fawebim.core.region.Selectors;
import com.maxlananas.fawebim.core.tool.Tools;
import com.maxlananas.fawebim.core.util.Cui;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.List;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Every shape of {@code //sel} answers the wand the way WorldEdit's does, the
 * ones FAWE adds included, switching shapes keeps the selection, and the
 * preview draws the shape rather than the box around it.
 *
 * <p>The wand and {@code //pos1}/{@code //pos2} answered "Position 1/2 set"
 * whatever the shape; the extending cuboid forgot every right click but the
 * last; a sphere clicked at its centre stayed undefined; {@code //sel} offered
 * the brush shapes for completion and neither FAWE's polyhedral nor its fuzzy
 * selection existed; and the preview outlined the bounding box of every
 * shape.</p>
 */
final class SelectionTests {

    private SelectionTests() {
    }

    static void run() {
        section("selections");
        theWandAnswersForEachShape();
        theExtendingCuboidGrowsWithEachClick();
        centredShapesNeedTheirCentre();
        theFaweShapesSelect();
        polygonsStopAtTheVertexLimit();
        switchingShapesKeepsTheSelection();
        thePreviewFollowsTheShape();
        theSelectorsAreSuggested();
    }

    private static TestActor actor(String name, String type) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(70);
        TestActor actor = new TestActor(name, world, new BlockVector3(0, 71, 0));
        CommandManager.get().dispatch(actor, "//sel " + type);
        actor.clearMessages();
        return actor;
    }

    /** A click of the wand, and the line it was answered with. */
    private static String click(TestActor actor, int x, int y, int z, boolean primary) {
        actor.clearMessages();
        Tools.select(actor, new BlockVector3(x, y, z), primary, false);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }

    private static Region region(TestActor actor) {
        return actor.session().getSelector(actor.world()).getRegion();
    }

    private static void theWandAnswersForEachShape() {
        TestActor cuboid = actor("sel-cuboid", "cuboid");
        check("a cuboid's first click is position 1", click(cuboid, 0, 70, 0, true).contains("Position 1: set to (0, 70, 0)"));
        check("its second says how many blocks it holds",
                click(cuboid, 3, 72, 4, false).contains("Position 2: set to (3, 72, 4) (60 blocks)"));
        check("the same click again says nothing", click(cuboid, 3, 72, 4, false).isEmpty());

        TestActor poly = actor("sel-poly", "poly");
        check("a polygon starts at the first click", click(poly, 0, 70, 0, true).contains("Polygon: started at (0, 70, 0)"));
        check("each right click adds a point", click(poly, 6, 70, 0, false).contains("point #2 added at (6, 70, 0)"));
        String third = click(poly, 6, 73, 6, false);
        check("the third defines it and counts its blocks", third.contains("point #3 added") && third.contains("blocks"));
        Region triangle = region(poly);
        check("the polygon holds what is inside its points", triangle.contains(5, 72, 1));
        check("and not what is outside", !triangle.contains(0, 72, 6));
        checkEquals("its height runs through the clicks", 73, triangle.getMaximumPoint().y());
        checkEquals("from the lowest one", 70, triangle.getMinimumPoint().y());
        click(poly, 0, 90, 0, true);
        click(poly, 6, 90, 0, false);
        click(poly, 6, 91, 6, false);
        checkEquals("a polygon started above the last one starts at its own height", 90,
                region(poly).getMinimumPoint().y());

        TestActor sphere = actor("sel-sphere", "sphere");
        check("a sphere's first click is its centre", click(sphere, 20, 80, 20, true).contains("Center: set to (20, 80, 20)"));
        check("its second sets the radius to the distance, rounded up",
                click(sphere, 23, 84, 20, false).contains("Radius: set to 5"));
        check("the sphere reaches the radius", region(sphere).contains(20, 85, 20));
        check("and not further", !region(sphere).contains(20, 86, 20));

        TestActor cylinder = actor("sel-cyl", "cyl");
        check("a cylinder starts at its centre", click(cylinder, 30, 70, 30, true).contains("Cylinder: started at (30, 70, 30)"));
        click(cylinder, 33, 70, 30, false);
        String radius = click(cylinder, 30, 75, 33, false);
        check("each right click extends the radii and the height", radius.contains("Radius: set to 3/3"));
        check("the cylinder holds its middle", region(cylinder).contains(31, 74, 31));
        checkEquals("its height runs to the last click", 75, region(cylinder).getMaximumPoint().y());

        TestActor convex = actor("sel-convex", "convex");
        check("a convex hull starts with a vertex",
                click(convex, 0, 90, 0, true).contains("Convex: started with the vertex (0, 90, 0)"));
        click(convex, 8, 90, 0, false);
        click(convex, 0, 90, 8, false);
        check("and adds more", click(convex, 0, 98, 0, false).contains("vertex (0, 98, 0) added"));
        check("the hull holds its inside", region(convex).contains(1, 91, 1));
        check("and not what is past its faces", !region(convex).contains(6, 96, 6));
    }

    private static void theExtendingCuboidGrowsWithEachClick() {
        TestActor actor = actor("sel-extend", "extend");
        String start = click(actor, 5, 80, 5, true);
        check("the first click starts a box of one block", start.contains("Selection: started at (5, 80, 5) (1 block)"));
        click(actor, 8, 82, 5, false);
        click(actor, 5, 80, 9, false);
        Region box = region(actor);
        checkEquals("each right click grows the box: its lowest corner", new BlockVector3(5, 80, 5),
                box.getMinimumPoint());
        checkEquals("and its highest", new BlockVector3(8, 82, 9), box.getMaximumPoint());
        check("a click inside the box changes nothing", click(actor, 6, 81, 6, false).isEmpty());
        click(actor, 0, 80, 0, true);
        checkEquals("a left click starts over", 1L, region(actor).getVolume());
    }

    private static void centredShapesNeedTheirCentre() {
        TestActor actor = actor("sel-centre", "ellipsoid");
        actor.clearMessages();
        Tools.select(actor, new BlockVector3(3, 80, 3), false, true);
        check("a radius before the centre is refused, and said", actor.messages().stream()
                .anyMatch(message -> message.replaceAll("\u00a7.", "").contains("Select the center")));
        click(actor, 10, 80, 10, true);
        click(actor, 10, 80, 10, false);
        check("a sphere clicked at its centre is a selection of its centre block, as in WorldEdit",
                actor.session().getSelector(actor.world()).isDefined() && region(actor).contains(10, 80, 10));
    }

    private static void theFaweShapesSelect() {
        TestActor hollow = actor("sel-polyhedral", "polyhedral");
        click(hollow, 0, 90, 0, true);
        click(hollow, 8, 90, 0, false);
        click(hollow, 0, 90, 8, false);
        click(hollow, 0, 98, 0, false);
        Region skin = region(hollow);
        check("a polyhedral selection holds the blocks its faces cross", skin.contains(1, 90, 1)
                && skin.contains(0, 91, 0));
        check("and not the inside of the shape", !skin.contains(1, 91, 1));
        long counted = skin.forEachPosition((x, y, z) -> true);
        checkEquals("it walks each of its blocks once", skin.getVolume(), counted);

        TestActor fuzzy = actor("sel-fuzzy", "fuzzy");
        TestWorld world = (TestWorld) fuzzy.world();
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                world.loadChunk(cx, cz);
            }
        }
        int gold = BlockState.registry().defaultState("minecraft:gold_block");
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                world.setBlock(x, 80, z, gold);
            }
        }
        world.setBlock(9, 80, 9, gold);
        String picked = click(fuzzy, 1, 80, 1, true);
        check("the magic wand takes the joined blocks of the clicked type", picked.contains("(9 blocks)"));
        check("and only those", !region(fuzzy).contains(9, 80, 9) && !region(fuzzy).contains(1, 79, 1));
        check("a right click adds to it", click(fuzzy, 9, 80, 9, false).contains("(10 blocks)"));
        checkEquals("its box holds both", new BlockVector3(9, 80, 9), region(fuzzy).getMaximumPoint());
        CommandManager.get().dispatch(fuzzy, "//set stone");
        checkEquals("an edit of it writes its blocks", BlockState.registry().defaultState("minecraft:stone"),
                world.getBlock(9, 80, 9));
        check("and nothing between them", BlockState.registry().isAirLike(world.getBlock(5, 80, 5)));
    }

    private static void polygonsStopAtTheVertexLimit() {
        TestActor actor = actor("sel-limit", "poly");
        click(actor, 0, 70, 0, true);
        for (int i = 1; i < 30; i++) {
            click(actor, i, 70, i % 2 == 0 ? 0 : 5, false);
        }
        RegionSelector selector = actor.session().getSelector(actor.world());
        checkEquals("a polygon takes WorldEdit's number of points", 21, selector.vertexCount());
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//pos2 40,70,3");
        check("and //pos2 says why a point is refused", actor.messages().stream()
                .anyMatch(message -> message.replaceAll("\u00a7.", "").contains("most points")));
    }

    private static void switchingShapesKeepsTheSelection() {
        TestActor actor = actor("sel-switch", "cuboid");
        CommandManager.get().dispatch(actor, "//pos1 0,70,0");
        CommandManager.get().dispatch(actor, "//pos2 8,74,6");
        CommandManager.get().dispatch(actor, "//sel poly");
        Region polygon = region(actor);
        check("a cuboid becomes the polygon of its corners", actor.session().getSelector(actor.world()).isDefined()
                && polygon.contains(8, 74, 6) && polygon.contains(0, 70, 0) && polygon.getVolume() == 9 * 5 * 7);
        CommandManager.get().dispatch(actor, "//sel cuboid");
        checkEquals("and back into the cuboid", 9L * 5 * 7, region(actor).getVolume());

        CommandManager.get().dispatch(actor, "//sel ellipsoid");
        Region ellipsoid = region(actor);
        check("an ellipsoid fits inside the box", ellipsoid instanceof EllipsoidRegion shape
                && shape.getRadii().equals(new Vector3(4, 2, 3)) && ellipsoid.contains(4, 72, 3));
        CommandManager.get().dispatch(actor, "//sel sphere");
        check("a sphere takes the largest radius", region(actor) instanceof EllipsoidRegion shape
                && shape.getRadii().equals(new Vector3(4, 4, 4)));

        CommandManager.get().dispatch(actor, "//sel cuboid");
        CommandManager.get().dispatch(actor, "//pos1 0,70,0");
        CommandManager.get().dispatch(actor, "//pos2 8,74,6");
        CommandManager.get().dispatch(actor, "//sel convex");
        Region hull = region(actor);
        check("a convex hull takes the box's corners", hull.contains(0, 70, 0) && hull.contains(8, 74, 6)
                && hull.getVolume() == 9 * 5 * 7);
        CommandManager.get().dispatch(actor, "//sel cyl");
        check("a cylinder fits inside the box", actor.session().getSelector(actor.world()).isDefined()
                && region(actor).contains(4, 74, 3));
        CommandManager.get().dispatch(actor, "//sel extend");
        check("the extending cuboid takes the box", actor.session().getSelector(actor.world()).isDefined());
    }

    private static void thePreviewFollowsTheShape() {
        TestActor cuboid = actor("preview-cuboid", "cuboid");
        click(cuboid, 0, 70, 0, true);
        click(cuboid, 3, 72, 4, false);
        Cui.Outline box = Cui.outline(cuboid.session().getSelector(cuboid.world()));
        checkEquals("a cuboid is drawn as its twelve edges", 12, box.segmentCount());
        check("along the outside of its blocks", onBox(box.segments(), 0, 70, 0, 4, 73, 5));
        checkEquals("its first corner is marked", List.of(new BlockVector3(0, 70, 0)), box.primary());
        checkEquals("and its second", List.of(new BlockVector3(3, 72, 4)), box.secondary());

        TestActor poly = actor("preview-poly", "poly");
        click(poly, 0, 70, 0, true);
        click(poly, 6, 70, 0, false);
        checkEquals("two points of a polygon are a path: two uprights and a side at each end", 4,
                Cui.outline(poly.session().getSelector(poly.world())).segmentCount());
        click(poly, 6, 73, 6, false);
        Cui.Outline triangle = Cui.outline(poly.session().getSelector(poly.world()));
        checkEquals("three are a closed outline at the floor and the ceiling, with uprights", 9,
                triangle.segmentCount());
        checkEquals("its other points are marked", 2, triangle.secondary().size());

        TestActor sphere = actor("preview-sphere", "sphere");
        click(sphere, 20, 80, 20, true);
        click(sphere, 24, 80, 20, false);
        Cui.Outline rings = Cui.outline(sphere.session().getSelector(sphere.world()));
        double[] segments = rings.segments();
        boolean onSurface = segments.length > 0;
        for (int i = 0; i < segments.length; i += 3) {
            double dx = segments[i] - 20.5;
            double dy = segments[i + 1] - 80.5;
            double dz = segments[i + 2] - 20.5;
            onSurface &= Math.abs(Math.sqrt(dx * dx + dy * dy + dz * dz) - 4.5) < 1e-9;
        }
        check("a sphere is drawn as rings on the surface its blocks fill", onSurface
                && rings.segmentCount() % 3 == 0);

        TestActor convex = actor("preview-convex", "convex");
        click(convex, 0, 90, 0, true);
        click(convex, 8, 90, 0, false);
        click(convex, 0, 90, 8, false);
        click(convex, 0, 98, 0, false);
        checkEquals("a tetrahedron is drawn as its six edges", 6,
                Cui.outline(convex.session().getSelector(convex.world())).segmentCount());

        TestActor cylinder = actor("preview-cyl", "cyl");
        click(cylinder, 30, 70, 30, true);
        click(cylinder, 33, 74, 33, false);
        Cui.Outline cyl = Cui.outline(cylinder.session().getSelector(cylinder.world()));
        check("a cylinder is drawn as two rings and four uprights", (cyl.segmentCount() - 4) % 2 == 0
                && cyl.segmentCount() > 4);

        TestActor empty = actor("preview-empty", "cuboid");
        click(empty, 1, 70, 1, true);
        Cui.Outline half = Cui.outline(empty.session().getSelector(empty.world()));
        check("half a selection marks its point and draws nothing", half.segmentCount() == 0
                && half.primary().size() == 1);
    }

    /** Whether every end of the segments lies on the surface of the box. */
    private static boolean onBox(double[] segments, double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int i = 0; i < segments.length; i += 3) {
            double x = segments[i];
            double y = segments[i + 1];
            double z = segments[i + 2];
            boolean inside = x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
            int onFaces = (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
            if (!inside || onFaces < 2) {
                return false;
            }
        }
        return true;
    }

    private static void theSelectorsAreSuggested() {
        List<String> offered = com.maxlananas.fawebim.core.command.Suggestions.forArgument("[selector]", "");
        check("//sel offers the selection types", offered.containsAll(Selectors.NAMES));
        check("and not the brush shapes", !offered.contains("hsphere"));
        List<String> directions = com.maxlananas.fawebim.core.command.Suggestions.forArgument("[direction]", "l");
        checkEquals("a direction offers the relative words", List.of("left"), directions);
        check("an offset offers the diagonals", com.maxlananas.fawebim.core.command.Suggestions
                .forArgument("[offset]", "north").contains("northeast"));
    }
}
