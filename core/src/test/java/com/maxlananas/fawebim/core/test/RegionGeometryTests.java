package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector2;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion;
import com.maxlananas.fawebim.core.region.CuboidRegion;
import com.maxlananas.fawebim.core.region.CylinderRegion;
import com.maxlananas.fawebim.core.region.EllipsoidRegion;
import com.maxlananas.fawebim.core.region.Polygonal2DRegion;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Every shape against its own definition: the walk, the iterator, the volume
 * and {@code contains} must describe the same set of blocks, in every case the
 * random generator comes up with.
 */
final class RegionGeometryTests {

    private RegionGeometryTests() {
    }

    static void run() {
        section("region geometry");
        Random random = new Random(7);
        for (int round = 0; round < 40; round++) {
            consistent("random cuboid " + round, randomCuboid(random), true);
            consistent("random ellipsoid " + round, randomEllipsoid(random), false);
            consistent("random cylinder " + round, randomCylinder(random), false);
            consistent("random polygon " + round, randomPolygon(random), true);
            consistent("random convex " + round, randomConvex(random), true);
        }
        polygonBoundaries();
        convexHull();
        shifts();
        cuboidContractPastTheOtherSide();
        selectionCommands();
    }

    /**
     * The walk visits each block the shape contains exactly once, the iterator
     * visits the same blocks in the same order, and the exact shapes count them
     * as their volume.
     */
    private static void consistent(String name, Region region, boolean exactVolume) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        Set<Long> expected = new HashSet<>();
        // One block of margin: a block outside the bounding box that contains
        // accepts is a bounding box that is too small.
        for (int y = min.y() - 1; y <= max.y() + 1; y++) {
            for (int z = min.z() - 1; z <= max.z() + 1; z++) {
                for (int x = min.x() - 1; x <= max.x() + 1; x++) {
                    if (region.contains(x, y, z)) {
                        expected.add(key(x, y, z));
                    }
                }
            }
        }
        boolean insideBox = true;
        for (long key : expected) {
            int x = (int) (key >> 42);
            int y = (int) (key << 22 >> 43);
            int z = (int) (key << 43 >> 43);
            if (x < min.x() || x > max.x() || y < min.y() || y > max.y() || z < min.z() || z > max.z()) {
                insideBox = false;
            }
        }
        List<Long> walked = new ArrayList<>();
        long counted = region.forEachPosition((x, y, z) -> walked.add(key(x, y, z)));
        List<Long> iterated = new ArrayList<>();
        for (Iterator<BlockVector3> it = region.iterator(); it.hasNext(); ) {
            BlockVector3 position = it.next();
            iterated.add(key(position.x(), position.y(), position.z()));
        }
        boolean ok = insideBox && walked.size() == expected.size() && new HashSet<>(walked).equals(expected)
                && counted == walked.size() && iterated.equals(walked)
                && (!exactVolume || region.getVolume() == expected.size());
        if (!ok) {
            System.out.println("    " + name + ": " + region.describe() + " box " + min + ".." + max
                    + " contains " + expected.size() + ", walked " + walked.size() + " (" + new HashSet<>(walked).size()
                    + " distinct), iterated " + iterated.size() + ", volume " + region.getVolume()
                    + ", inside box " + insideBox);
        }
        check(name + ": walk, iterator, volume and contains agree", ok);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
    }

    private static Region randomCuboid(Random random) {
        BlockVector3 a = new BlockVector3(random.nextInt(60) - 30, random.nextInt(40) - 20, random.nextInt(60) - 30);
        BlockVector3 b = a.add(random.nextInt(25) - 12, random.nextInt(12) - 6, random.nextInt(25) - 12);
        return new CuboidRegion(a, b);
    }

    private static Region randomEllipsoid(Random random) {
        Vector3 center = new Vector3(random.nextInt(40) - 20 + (random.nextBoolean() ? 0.5 : 0),
                random.nextInt(20) + 0.5, random.nextInt(40) - 20 + 0.5);
        Vector3 radii = new Vector3(random.nextInt(9), random.nextInt(6), random.nextDouble() * 8);
        return random.nextBoolean() ? new EllipsoidRegion(center, radii)
                : new EllipsoidRegion(center, radii, 2, 14);
    }

    private static Region randomCylinder(Random random) {
        Vector2 center = new Vector2(random.nextInt(40) - 20 + 0.5, random.nextInt(40) - 20 + (random.nextBoolean() ? 0.5 : 0));
        return new CylinderRegion(center, random.nextInt(10), random.nextDouble() * 9, random.nextInt(10),
                random.nextInt(10) + 5);
    }

    /** Convex, concave and self-crossing outlines alike. */
    private static Region randomPolygon(Random random) {
        int n = 3 + random.nextInt(7);
        List<BlockVector2> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            points.add(new BlockVector2(random.nextInt(41) - 20, random.nextInt(41) - 20));
        }
        return new Polygonal2DRegion(points, random.nextInt(5), 3 + random.nextInt(5));
    }

    private static Region randomConvex(Random random) {
        ConvexPolyhedralRegion convex = new ConvexPolyhedralRegion();
        int n = 3 + random.nextInt(8);
        for (int i = 0; i < n; i++) {
            convex.addVertex(new BlockVector3(random.nextInt(21) - 10, random.nextInt(21) - 10,
                    random.nextInt(21) - 10));
        }
        return convex;
    }

    /** WorldEdit's polygon: the outline belongs to the selection. */
    private static void polygonBoundaries() {
        Polygonal2DRegion square = new Polygonal2DRegion(List.of(new BlockVector2(0, 0), new BlockVector2(10, 0),
                new BlockVector2(10, 10), new BlockVector2(0, 10)), 0, 0);
        check("a square polygon holds its x = 10 side", square.contains(10, 0, 5));
        check("a square polygon holds its z = 10 side", square.contains(5, 0, 10));
        check("a square polygon holds its far corner", square.contains(10, 0, 10));
        check("a square polygon stops at its side", !square.contains(11, 0, 5));
        checkEquals("a square polygon from 0 to 10 holds 11 x 11 columns", 121L, square.getVolume());

        Polygonal2DRegion triangle = new Polygonal2DRegion(List.of(new BlockVector2(0, 0), new BlockVector2(4, 0),
                new BlockVector2(0, 4)), 0, 0);
        check("a triangle holds its diagonal", triangle.contains(2, 0, 2));
        check("a triangle stops past its diagonal", !triangle.contains(3, 0, 2));
        checkEquals("a right triangle of side 4 holds 15 columns", 15L, triangle.getVolume());

        boolean refused = false;
        try {
            square.expand(new BlockVector3(2, 0, 0));
        } catch (RuntimeException e) {
            refused = true;
        }
        check("a polygon only grows vertically, as in WorldEdit", refused);
    }

    /** The hull of eight cube corners is the cube, whatever order they come in. */
    private static void convexHull() {
        List<BlockVector3> corners = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            corners.add(new BlockVector3((i & 1) * 6, (i >> 1 & 1) * 6, (i >> 2 & 1) * 6));
        }
        Random random = new Random(3);
        for (int round = 0; round < 5; round++) {
            java.util.Collections.shuffle(corners, random);
            ConvexPolyhedralRegion cube = new ConvexPolyhedralRegion();
            for (BlockVector3 corner : corners) {
                cube.addVertex(corner);
            }
            cube.addVertex(new BlockVector3(3, 3, 3));
            checkEquals("the hull of a cube's corners holds the cube (order " + round + ")", 343L,
                    cube.getVolume());
            check("the hull of a cube holds a point of a face", cube.contains(6, 3, 3));
            check("the hull of a cube stops at its faces", !cube.contains(7, 3, 3));
        }
        ConvexPolyhedralRegion tetrahedron = new ConvexPolyhedralRegion();
        tetrahedron.addVertex(new BlockVector3(0, 0, 0));
        tetrahedron.addVertex(new BlockVector3(4, 0, 0));
        tetrahedron.addVertex(new BlockVector3(0, 4, 0));
        tetrahedron.addVertex(new BlockVector3(0, 0, 4));
        check("a tetrahedron holds its corner", tetrahedron.contains(0, 0, 0));
        check("a tetrahedron holds a point of its slanted face", tetrahedron.contains(2, 1, 1));
        check("a tetrahedron stops past its slanted face", !tetrahedron.contains(2, 2, 1));
        checkEquals("a tetrahedron of side 4 holds 35 blocks", 35L, tetrahedron.getVolume());
    }

    /** Every shape moves by the amount and keeps its shape. */
    private static void shifts() {
        BlockVector3 by = new BlockVector3(7, -3, 11);
        List<Region> shapes = List.of(
                new CuboidRegion(new BlockVector3(0, 0, 0), new BlockVector3(4, 2, 4)),
                new EllipsoidRegion(new Vector3(0.5, 5.5, 0.5), new Vector3(3, 2, 4)),
                new CylinderRegion(new Vector2(0.5, 0.5), 3, 2, 0, 4),
                new Polygonal2DRegion(List.of(new BlockVector2(0, 0), new BlockVector2(6, 1),
                        new BlockVector2(2, 5)), 0, 3),
                randomConvex(new Random(11)));
        for (Region shape : shapes) {
            Set<Long> before = new HashSet<>();
            shape.forEachPosition((x, y, z) -> before.add(key(x + by.x(), y + by.y(), z + by.z())));
            check(shape.describe() + " moves", shape.shift(by));
            Set<Long> after = new HashSet<>();
            shape.forEachPosition((x, y, z) -> after.add(key(x, y, z)));
            check(shape.describe() + " is the same set of blocks, moved", !before.isEmpty() && before.equals(after));
        }
    }

    private static void cuboidContractPastTheOtherSide() {
        CuboidRegion box = new CuboidRegion(new BlockVector3(0, 0, 0), new BlockVector3(4, 4, 4));
        box.contract(new BlockVector3(10, 0, 0));
        check("a box contracted past its other side stays a box",
                box.getMinimumPoint().x() <= box.getMaximumPoint().x());
        checkEquals("the contracted box flips, as in WorldEdit", 4, box.getMinimumPoint().x());
        checkEquals("the contracted box flips to the moved side", 10, box.getMaximumPoint().x());
        checkEquals("its volume counts the blocks it walks", box.forEachPosition((x, y, z) -> true),
                box.getVolume());
    }

    /** The selection commands on a live session. */
    private static void selectionCommands() {
        TestWorld world = new TestWorld("selection-commands");
        world.fillFlat(70);
        TestActor player = new TestActor("Shifter", world, new BlockVector3(0, 71, 0));
        CommandManager.get().dispatch(player, "//pos1 0,64,0");
        CommandManager.get().dispatch(player, "//pos2 4,66,4");
        CommandManager.get().dispatch(player, "//shift 5 east");
        Region moved = player.session().getSelection(world);
        checkEquals("//shift moves the box", new BlockVector3(5, 64, 0), moved.getMinimumPoint());
        checkEquals("//shift keeps its size", new BlockVector3(9, 66, 4), moved.getMaximumPoint());

        // //expand changes the region; the next click has to start from it.
        CommandManager.get().dispatch(player, "//expand 5 up");
        CommandManager.get().dispatch(player, "//pos1 6,64,0");
        Region clicked = player.session().getSelection(world);
        checkEquals("a click after //expand keeps the expansion", 71, clicked.getMaximumPoint().y());
        checkEquals("the click moves the corner it names", 6, clicked.getMinimumPoint().x());

        // WorldEdit reads a word where the reverse amount would be as the direction.
        CommandManager.get().dispatch(player, "//pos1 0,64,0");
        CommandManager.get().dispatch(player, "//pos2 4,66,4");
        CommandManager.get().dispatch(player, "//expand 3 north");
        checkEquals("//expand <amount> <direction> takes the direction", -3,
                player.session().getSelection(world).getMinimumPoint().z());
        // Contracting towards a direction moves the opposite side, as in WorldEdit.
        CommandManager.get().dispatch(player, "//contract 2 north");
        checkEquals("//contract <amount> <direction> takes the direction", 2,
                player.session().getSelection(world).getMaximumPoint().z());

        // //stack steps by the size of the selection along the direction.
        int stone = BlockState.registry().defaultState("minecraft:stone");
        TestActor stacker = new TestActor("Stacker", world, new BlockVector3(0, 90, 0));
        CommandManager.get().dispatch(stacker, "//pos1 20,80,20");
        CommandManager.get().dispatch(stacker, "//pos2 24,82,24");
        CommandManager.get().dispatch(stacker, "//set stone");
        CommandManager.get().dispatch(stacker, "//stack 1 east");
        checkEquals("//stack east copies next to the selection", stone, world.getBlock(29, 80, 20));
        checkEquals("//stack east leaves no overlap and no gap", stone, world.getBlock(25, 80, 20));
        checkEquals("//stack east stops after one copy", BlockState.registry().air(), world.getBlock(30, 80, 20));
        CommandManager.get().dispatch(stacker, "//stack 1 up -s");
        checkEquals("//stack up copies above the selection", stone, world.getBlock(20, 83, 20));
        checkEquals("//stack -s moves the selection onto the copy", 83,
                stacker.session().getSelection(world).getMinimumPoint().y());
    }
}
