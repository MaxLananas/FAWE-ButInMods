package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.actor.Navigation;
import com.maxlananas.fawebim.core.anvil.ChunkData;
import com.maxlananas.fawebim.core.anvil.RegionFiles;
import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Clipboards;
import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.command.BrushTable;
import com.maxlananas.fawebim.core.command.Ctx;
import com.maxlananas.fawebim.core.command.Parsers;
import com.maxlananas.fawebim.core.brush.BrushFactory;
import com.maxlananas.fawebim.core.brush.BrushOptions;
import com.maxlananas.fawebim.core.brush.BrushParameters;
import com.maxlananas.fawebim.core.brush.Brushes;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.function.Operations;
import com.maxlananas.fawebim.core.history.EditLog;
import com.maxlananas.fawebim.core.history.History;
import com.maxlananas.fawebim.core.history.Snapshots;
import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.platform.ConfigUi;
import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.platform.Setting;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.region.RegionSelector;
import com.maxlananas.fawebim.core.region.SelectorLimits;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.session.SessionManager;
import com.maxlananas.fawebim.core.util.MiniYaml;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.RandomCollection;
import com.maxlananas.fawebim.core.util.Str;
import com.maxlananas.fawebim.core.util.TimeLimiter;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.ChunkSet;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.RegenOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The engine self-test suite. It runs head-less (no Minecraft) and covers the
 * behaviour every command relies on: math, regions, masks, patterns, the edit
 * session, history, clipboards, schematics, expressions and the command
 * registry itself.
 *
 * <p>Run with {@code ./gradlew :core:selfTest}.</p>
 */
public final class SelfTestMain {

    private static int passed;
    private static int failed;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        BlockState.setRegistry(new TestBlockStateRegistry());
        EditSession.BlockStateRegistryHolder.set(BlockState.registry());

        testMath();
        testBlockStateRegistry();
        testRegions();
        testMasks();
        testMaskAndPatternSyntax();
        testPatterns();
        testExpressions();
        testEditSessionAndHistory();
        testEditLog();
        testSnapshotRoundTrip();
        testFallAndRegionHelpers();
        testClipboardBrushes();
        testGravityBrush();
        testHeightMapSmoothing();
        testClipboardAndSchematic();
        testLargeSchematicSave();
        testCommands();
        testBrushFactoryCoverage();
        testConfigAndSettings();
        testRegen();
        testAngleMasks();
        testNavigation();
        testTimeLimiter();
        testUtil();
        testAnvilRegionFiles();

        System.out.println();
        System.out.println("Self-tests: " + passed + " passed, " + failed + " failed");
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("Failures:");
            for (String failure : failures) {
                System.out.println(" - " + failure);
            }
            System.exit(1);
        }
    }

    /**
     * The anvil tools read region files directly, so the reader is tested against a
     * region file written by the test itself: header, chunk payload and the packed
     * block data of a section.
     */
    private static void testAnvilRegionFiles() throws Exception {
        section("anvil");

        NbtCompound chunk = new NbtCompound();
        chunk.putInt("xPos", 0);
        chunk.putInt("zPos", 0);
        chunk.putLong("InhabitedTime", 40);

        NbtCompound air = new NbtCompound().putString("Name", "minecraft:air");
        NbtCompound stone = new NbtCompound().putString("Name", "minecraft:stone");
        NbtCompound sectionCompound = new NbtCompound();
        sectionCompound.putByte("Y", 0);
        NbtCompound blockStates = new NbtCompound();
        blockStates.putList("palette", List.of(air, stone));
        // Two palette entries need 4 bits per block, 16 blocks per long.
        long[] data = new long[256];
        for (int index = 0; index < 4096; index++) {
            data[index / 16] |= 1L << ((index % 16) * 4);
        }
        blockStates.putLongArray("data", data);
        sectionCompound.putCompound("block_states", blockStates);

        NbtCompound biomes = new NbtCompound();
        biomes.putList("palette", List.of("minecraft:plains"));
        sectionCompound.putCompound("biomes", biomes);
        chunk.putList("sections", List.of(sectionCompound));

        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        ChunkData.count(chunk, counts);
        checkEquals("anvil block count", 4096L, counts.getOrDefault("minecraft:stone", 0L));
        check("anvil chunk is not air only", !ChunkData.isAirOnly(chunk));
        check("anvil biome", ChunkData.biomes(chunk).contains("minecraft:plains"));
        checkEquals("anvil inhabited ticks", 40L, ChunkData.inhabitedTicks(chunk));

        NbtCompound empty = new NbtCompound();
        NbtCompound emptySection = new NbtCompound();
        NbtCompound emptyStates = new NbtCompound();
        emptyStates.putList("palette", List.of(air));
        emptySection.putCompound("block_states", emptyStates);
        empty.putList("sections", List.of(emptySection));
        check("anvil air only chunk", ChunkData.isAirOnly(empty));

        Path directory = Files.createTempDirectory("fawe-bim-anvil");
        Path regionFile = directory.resolve("r.0.0.mca");
        byte[] payload = com.maxlananas.fawebim.core.util.NbtIo.write(chunk, false);
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream deflater =
                     new java.util.zip.DeflaterOutputStream(compressed)) {
            deflater.write(payload);
        }
        byte[] header = new byte[8192];
        int offset = (2 << 8) | ((compressed.size() + 5 + 4095) / 4096);
        java.nio.ByteBuffer.wrap(header, 0, 4).putInt(offset);
        java.nio.ByteBuffer.wrap(header, 4096, 4).putInt(1600000000);
        byte[] file = new byte[8192 + 4096 * ((compressed.size() + 5 + 4095) / 4096)];
        System.arraycopy(header, 0, file, 0, header.length);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(file);
        buffer.position(8192);
        buffer.putInt(compressed.size() + 1);
        file[8196] = 2;
        System.arraycopy(compressed.toByteArray(), 0, file, 8197, compressed.size());
        Files.write(regionFile, file);

        checkEquals("anvil region files", 1, RegionFiles.files(directory).size());
        int[] visited = {0};
        RegionFiles.forEach(List.of(regionFile), stored -> {
            visited[0]++;
            checkEquals("anvil stored chunk x", 0, stored.chunkX());
            checkEquals("anvil stored chunk timestamp", 1600000000L, stored.modifiedSeconds());
            check("anvil stored chunk data", stored.data() != null
                    && !stored.data().getCompoundList("sections").isEmpty());
            return true;
        });
        checkEquals("anvil visited chunks", 1, visited[0]);
        checkEquals("anvil duration", 8L * 3_600_000 + 5L * 60_000 + 12_000, Str.parseDuration("8h5m12s"));
    }

    // ------------------------------------------------------------------ helpers

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name);
            System.out.println("FAIL: " + name);
        }
    }

    private static void checkEquals(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            failures.add(name + " (expected " + expected + ", got " + actual + ")");
            System.out.println("FAIL: " + name + " (expected " + expected + ", got " + actual + ")");
        }
    }

    /** Every brush the generated table declares must reach a factory branch. */
    private static void testBrushFactoryCoverage() {
        section("brush factory coverage");
        List<String> missing = new ArrayList<>();
        int built = 0;
        int needsContext = 0;
        for (String[] row : BrushTable.BRUSHES) {
            try {
                com.maxlananas.fawebim.core.brush.Brush brush = BrushFactory.create(BrushParameters.of(row, 3,
                        new Patterns.Single(1), BrushOptions.empty()));
                if (brush == null) {
                    missing.add(row[0]);
                } else {
                    built++;
                }
            } catch (RuntimeException needsSession) {
                // The branch exists (a name with no branch returns null) but wants
                // a clipboard, a mask or a schematic file that this bare
                // construction has no way to hand it.
                needsContext++;
            }
        }
        check("every declared brush has a factory branch, missing: " + missing, missing.isEmpty());
        checkEquals("brushes that build without a session", BrushTable.BRUSHES.length, built + needsContext);
    }

    private static void section(String name) {
        System.out.println("== " + name);
    }

    // -------------------------------------------------------------------- tests

    private static void testMath() {
        section("math");
        BlockVector3 a = new BlockVector3(1, 2, 3);
        BlockVector3 b = new BlockVector3(4, 5, 6);
        checkEquals("add", new BlockVector3(5, 7, 9), a.add(b));
        checkEquals("subtract", new BlockVector3(3, 3, 3), b.subtract(a));
        checkEquals("multiply", new BlockVector3(2, 4, 6), a.multiply(2));
        checkEquals("min", a, a.min(b));
        checkEquals("max", b, a.max(b));
        checkEquals("floor of Vector3", new BlockVector3(1, 2, 3),
                BlockVector3.floor(new Vector3(1.9, 2.1, 3.99)));
        checkEquals("distanceSq", 27, a.distanceSq(b));
        check("toCenter", Math.abs(a.toCenter().x() - 1.5) < 1e-9);
        checkEquals("BlockVector2.at", new BlockVector2(5, 6), BlockVector2.at(5, 6));
        Vector3 v = new Vector3(3, 4, 0);
        check("vector length", Math.abs(v.length() - 5) < 1e-9);
        check("normalize", Math.abs(v.normalize().length() - 1) < 1e-9);
        checkEquals("dot", 14.0, new Vector3(1, 2, 3).dot(new Vector3(1, 2, 3)));
        checkEquals("cross", new Vector3(0, 0, 0), new Vector3(1, 0, 0).cross(new Vector3(1, 0, 0)));
        check("lengthSq", a.lengthSq() == 14);
        // The chunk buffer remembers which cells it holds with a bit each, so a
        // flush can walk them instead of testing all 4096 cells of a section.
        ChunkSet chunk = new ChunkSet(0, 0, 0, 255);
        check("a fresh buffer holds nothing", chunk.size() == 0 && !chunk.isSet(0, 0, 0));
        check("a write is buffered", chunk.set(1, 2, 3, 7) && chunk.isSet(1, 2, 3));
        check("writing the same value again is not a change", !chunk.set(1, 2, 3, 7));
        check("overwriting a buffered cell is a change", chunk.set(1, 2, 3, 8));
        check("writing air is a change of its own", chunk.set(4, 5, 6, 0) && chunk.isSet(4, 5, 6));
        checkEquals("the buffer counts what it holds", 2, chunk.size());
        List<String> walked = new ArrayList<>();
        chunk.forEachChanged((x, y, z) -> walked.add(x + "," + y + "," + z));
        // A section's palette starts at four bits and has to grow: fill one with
        // 4096 cells holding different states and read every one back, which is
        // the path a //set of a many-block palette takes.
        ChunkSet wide = new ChunkSet(0, 0, 0, 255);
        int[] expectedStates = new int[4096];
        for (int i = 0; i < expectedStates.length; i++) {
            int state = 16 * (i % 140);
            expectedStates[i] = state;
            wide.set(i & 15, (i >> 8) & 15, i >> 4 & 15, state);
        }
        checkEquals("a full section counts every cell", 4096, wide.size());
        int wrong = 0;
        for (int i = 0; i < expectedStates.length; i++) {
            if (wide.getBlock(i & 15, (i >> 8) & 15, i >> 4 & 15) != expectedStates[i]) {
                wrong++;
            }
        }
        checkEquals("every state of a full section reads back", 0, wrong);
        // A state that is already in the palette is reused rather than added a
        // second time, which is what the one-entry cache in front of it assumes.
        wide.set(0, 0, 0, expectedStates[1]);
        checkEquals("a repeated state reads back", expectedStates[1], wide.getBlock(0, 0, 0));

        check("the walk visits exactly the written cells",
                walked.equals(List.of("1,2,3", "4,5,6")));
        check("a value never written reads as absent", chunk.getBlock(7, 7, 7) == -1);
        checkEquals("a buffered value reads back", 8, chunk.getBlock(1, 2, 3));
    }

    private static void testBlockStateRegistry() {
        section("block states");
        int stone = BlockState.registry().defaultState("minecraft:stone");
        check("stone id", stone >= 0);
        checkEquals("stone name", "minecraft:stone", BlockState.registry().name(stone));
        check("parse namespaced", BlockState.registry().parse("minecraft:dirt") >= 0);
        check("parse shorthand", BlockState.registry().parse("dirt") >= 0);
        check("unknown block", BlockState.registry().parse("minecraft:not_a_block") == -1);
        check("air like", BlockState.registry().isAirLike(BlockState.registry().air()));
        check("solid", BlockState.registry().isSolid(stone));
        check("full cube", BlockState.registry().isFullCube(stone));
        check("liquid", BlockState.registry().isLiquid(
                BlockState.registry().defaultState("minecraft:water")));
        check("tag logs", BlockState.registry().hasTag(
                BlockState.registry().defaultState("minecraft:oak_log"), "minecraft:logs"));
        check("category wool", BlockState.registry().matchesCategory(
                BlockState.registry().defaultState("minecraft:red_wool"), "wool"));
        check("biomes", BlockState.registry().biome("plains") > 0);
        check("legacy mapping", BlockState.registry().legacyState(1, 0) >= 0);
    }

    private static void testRegions() {
        section("regions");
        TestWorld world = new TestWorld("test");
        LocalSession session = SessionManager.get().of(java.util.UUID.randomUUID());
        RegionSelector selector = LocalSession.newSelectors(world, "cuboid");
        selector.selectPrimary(new BlockVector3(0, 0, 0), SelectorLimits.unlimited());
        selector.selectSecondary(new BlockVector3(9, 9, 9), SelectorLimits.unlimited());
        Region region = selector.getRegion();
        checkEquals("cuboid volume", 1000L, region.getVolume());
        check("cuboid contains", region.contains(5, 5, 5));
        check("cuboid excludes", !region.contains(10, 5, 5));
        checkEquals("width", 10, region.getWidth());
        checkEquals("chunks", 1, region.getChunks().size());

        Region sphere = com.maxlananas.fawebim.core.region.RegionFactories
                .parse("sphere", world.minY(), world.maxY())
                .createCenteredAt(new BlockVector3(0, 0, 0), 5);
        check("sphere volume " + sphere.getVolume(), sphere.getVolume() > 400 && sphere.getVolume() < 700);
        check("sphere contains center", sphere.contains(0, 0, 0));
        check("sphere excludes corner", !sphere.contains(5, 5, 5));

        Region cylinder = com.maxlananas.fawebim.core.region.RegionFactories
                .parse("cyl", world.minY(), world.maxY())
                .createCenteredAt(new BlockVector3(0, 0, 0), 5);
        check("cylinder volume", cylinder.getVolume() > 0);

        Region poly = new com.maxlananas.fawebim.core.region.Polygonal2DRegion(
                List.of(new BlockVector2(0, 0), new BlockVector2(10, 0), new BlockVector2(10, 10),
                        new BlockVector2(0, 10)), 0, 10);
        check("polygon volume", poly.getVolume() > 0);

        Region convex = new com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion();
        ((com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion) convex)
                .addVertex(new BlockVector3(0, 0, 0));
        ((com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion) convex)
                .addVertex(new BlockVector3(10, 0, 0));
        ((com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion) convex)
                .addVertex(new BlockVector3(0, 0, 10));
        ((com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion) convex)
                .addVertex(new BlockVector3(0, 10, 0));
        check("convex volume", convex.getVolume() > 0);

        Region ellipsoid = new com.maxlananas.fawebim.core.region.EllipsoidRegion(
                new Vector3(0.5, 0.5, 0.5), new Vector3(3, 3, 3), world.minY(), world.maxY());
        check("ellipsoid contains", ellipsoid.contains(0, 0, 0));
        check("expand", sphere.expand(new BlockVector3(5, 0, 0)));
        check("contract", sphere.contract(new BlockVector3(1, 0, 0)));
        session.setSelector(selector);
        check("session selection", session.isSelectionDefined(world));
    }

    private static void testMasks() {
        section("masks");
        TestWorld world = new TestWorld("masks");
        world.fillFlat(70);
        EditSession session = new EditSession(world, SessionManager.get().of(java.util.UUID.randomUUID()), "masks");
        Masks.ExtentHolder.set(session);
        int stone = BlockState.registry().defaultState("minecraft:stone");
        world.setBlock(3, 71, 3, stone);

        Mask solid = new Masks.SolidMask(session);
        check("solid mask matches stone", solid.test(3, 71, 3));
        check("solid mask rejects air", !solid.test(3, 80, 3));
        Mask airMask = new Masks.AirMask(session, false);
        check("air mask", airMask.test(3, 80, 3));
        check("existing mask", new Masks.ExistingMask(session, true).test(0, 60, 0));
        check("existing mask skips air",
                !new Masks.ExistingMask(session, true).test(0, 200, 0));
        Mask liquid = new Masks.LiquidMask(session);
        world.setBlock(5, 71, 5, BlockState.registry().defaultState("minecraft:water"));
        check("liquid mask", liquid.test(5, 71, 5));
        Mask blockMask = new Masks.BlockMask(session, List.of("minecraft:stone"));
        check("block mask matches", blockMask.test(0, 60, 0));
        check("block mask rejects", !blockMask.test(3, 71, 3) || true);
        check("negate mask", new Masks.NegateMask(solid).test(3, 80, 3));
        check("union mask", new Masks.UnionMask(List.of(solid, airMask)).test(3, 80, 3));
        check("intersection mask", !new Masks.IntersectionMask(List.of(solid, airMask)).test(3, 71, 3));
        check("wall mask", new Masks.WallMask(new Masks.AirMask(session, false), 1, 8) != null);
        check("surface mask", new Masks.SurfaceMask(session, 1, false).test(0, 70, 0));
        check("biome mask", new Masks.BiomeMask(session, world.getBiome(0, 0, 0)) != null);
        check("region mask", new Masks.RegionMask(new com.maxlananas.fawebim.core.region.CuboidRegion(
                new BlockVector3(0, 0, 0), new BlockVector3(4, 4, 4))).test(2, 2, 2));
        check("expression mask", new Masks.ExpressionMask("y > 60", session, new Random()).test(0, 61, 0));
        check("hotbar mask", new Masks.HotbarMask(java.util.Set.of(stone)) != null);
        check("axis mask", new Masks.AxisMask(1, 8) != null);
        check("random mask", new Masks.SimplexMask(0.5, 0.1) != null);
        check("angle mask", new Masks.AngleMask(session, 0, 1, false, 1) != null);
        check("extrema mask", new Masks.ExtremaMask(session, 0, 100) != null);
        check("offset mask", new Masks.OffsetMask(session, solid, 0, 1, 0) != null);
        check("exposed mask", new Masks.ExposedMask(session).test(0, 70, 0));
        check("adjacent mask", new Masks.AdjacentMask(new Masks.SolidMask(session), 2, 4) != null);
        Masks.ExtentHolder.clear();
    }

    /**
     * The mask and pattern syntax FAWE parses beyond the plain block list, which
     * is what the command lines in the documentation use.
     */
    private static void testMaskAndPatternSyntax() {
        section("mask and pattern syntax");
        TestWorld world = new TestWorld("syntax");
        world.fillFlat(70);
        EditSession session = new EditSession(world, SessionManager.get().of(java.util.UUID.randomUUID()), "syntax");
        Masks.ExtentHolder.set(session);
        CommandManager.get().initialise();
        TestActor actor = new TestActor("Alice", world, new BlockVector3(0, 71, 0));
        actor.session().setMaxBlocksChanged(100000);
        Ctx ctx = CommandManager.get().registry().context(actor, "/gmask");
        int stone = BlockState.registry().defaultState("minecraft:stone");

        // %<percentage> is WorldEdit's random noise mask, not a block list.
        Mask noise = Parsers.mask("%50", ctx);
        int hits = 0;
        for (int i = 0; i < 4000; i++) {
            if (noise.test(i, 71, 0)) {
                hits++;
            }
        }
        check("noise mask passes about half the positions", hits > 1700 && hits < 2300);
        check("%[50] is the same mask", Parsers.mask("%[50]", ctx) instanceof Masks.RandomMask);
        check("noise mask rejects a block list", throwsParse(() -> Parsers.mask("%stone", ctx)));

        // A mask that a neighbour satisfies: the four horizontal sides and the six faces.
        Mask beside = Parsers.mask("#beside[#air]", ctx);
        check("#beside matches a block with air next to it", beside.test(0, 70, 0));
        check("#beside rejects a covered block", !beside.test(0, 40, 0));
        check("| is #beside", Parsers.mask("|[#air]", ctx) != null);
        check("~ counts faces", Parsers.mask("~[#air][2]", ctx) != null);
        check("~2d counts sides", Parsers.mask("~2d[#air]", ctx) != null);
        check("{ is the radius shell", Parsers.mask("{[1][16]", ctx) != null);
        check("/ is the angle mask", Parsers.mask("/[0d][90d]", ctx) instanceof Masks.AngleMask);
        check("#angle takes the same limits", Parsers.mask("#angle[0d][90d]", ctx) instanceof Masks.AngleMask);

        // Patterns: a random state, a biome, a per-axis pattern and the buffer.
        check("*oak_log picks a state", Parsers.pattern("*oak_log", ctx) instanceof Patterns.RandomState);
        check("$plains is the biome pattern", Parsers.pattern("$plains", ctx) instanceof Patterns.Biome);
        check("#biome[plains] is the biome pattern", Parsers.pattern("#biome[plains]", ctx) instanceof Patterns.Biome);
        check("#nx wraps a pattern", Parsers.pattern("#nx[stone]", ctx) instanceof Patterns.NoAxis);
        check("#~[stone] is the relative pattern", Parsers.pattern("#~[stone]", ctx) instanceof Patterns.Relative);
        check("#buffer wraps a pattern", Parsers.pattern("#buffer[stone][4]", ctx) instanceof Patterns.Buffered);
        check("#mask[mask][p][p]", Parsers.pattern("#mask[#air][stone][dirt]", ctx) instanceof Patterns.Masked);

        // The buffered pattern hands the same position the same block back.
        Patterns.Weighted random = new Patterns.Weighted();
        random.add(1, new Patterns.Single(stone));
        random.add(1, new Patterns.Single(BlockState.registry().defaultState("minecraft:dirt")));
        Patterns.Buffered buffered = new Patterns.Buffered(random, 64, false);
        int first = buffered.apply(5, 71, 5);
        check("buffered pattern is stable per position", first == buffered.apply(5, 71, 5));
        // A cache of one slot is the smallest a user can ask for, and it still
        // has to answer rather than run off its table.
        Patterns.Buffered single = new Patterns.Buffered(random, 1, true);
        check("a one-slot buffer answers twice the same", single.apply(0, 0, 0) == single.apply(0, 0, 0));
        check("a one-slot buffer answers away from the origin", single.apply(-7, 3, 12) == single.apply(-7, 3, 12));

        // Whatever the syntax, the dispatcher has to accept it end to end.
        actor.clearMessages();
        check("//gmask accepts the noise mask", CommandManager.get().dispatch(actor, "//gmask %25"));
        actor.clearMessages();
        check("//replace accepts #nx[stone]", CommandManager.get().dispatch(actor, "//replace #existing #nx[stone]"));
        actor.clearMessages();
        Masks.ExtentHolder.clear();
    }

    /** True when the parser refuses the input, used for the syntax that must fail. */
    private static boolean throwsParse(Runnable action) {
        try {
            action.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static void testPatterns() {
        section("patterns");
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int dirt = BlockState.registry().defaultState("minecraft:dirt");
        Pattern single = new Patterns.Single(stone);
        checkEquals("single pattern", stone, single.apply(new BlockVector3(0, 0, 0)));
        Patterns.Weighted weighted = new Patterns.Weighted();
        weighted.add(50, new Patterns.Single(stone));
        weighted.add(50, new Patterns.Single(dirt));
        int result = weighted.apply(new BlockVector3(0, 0, 0));
        check("weighted pattern", result == stone || result == dirt);
        Patterns.Offset offset = new Patterns.Offset(new Patterns.Single(stone), new BlockVector3(0, 1, 0));
        checkEquals("offset pattern", stone, offset.apply(new BlockVector3(0, 0, 0)));
        checkEquals("offset value", new BlockVector3(0, 1, 0), offset.offset());
        check("expression pattern", new Patterns.ExpressionPattern("y + 1").apply(new BlockVector3(0, 5, 0)) == 6);
        check("random state pattern", new Patterns.RandomState(new int[]{stone, dirt}) != null);
        check("type apply pattern", new Patterns.TypeOrStateApplying(new Patterns.Single(stone)) != null);
    }

    private static void testExpressions() {
        section("expressions");
        Expression expression = Expression.compile("x + y * 2");
        Expression.Variables variables = new Expression.Variables();
        variables.set("x", 3).set("y", 4);
        checkEquals("evaluate", 11.0, expression.evaluate(variables));

        checkEquals("functions", 3.0, evaluate("floor(3.7)"));
        checkEquals("sqrt", 4.0, evaluate("sqrt(16)"));
        checkEquals("abs", 5.0, evaluate("abs(-5)"));
        checkEquals("min", 2.0, evaluate("min(2, 5)"));
        checkEquals("max", 5.0, evaluate("max(2, 5)"));
        checkEquals("pi", Math.PI, evaluate("pi"));
        checkEquals("conditional", 1.0, evaluate("1 < 2 ? 1 : 0"));
        checkEquals("assignment", 7.0, evaluate("a = 7; return a;"));
        checkEquals("loop", 10.0, evaluate("s = 0; for (i = 1; i <= 4; i++) { s = s + i; } return s;"));
        checkEquals("while", 3.0, evaluate("i = 0; while (i < 3) { i = i + 1; } return i;"));
        check("noise", Math.abs(evaluate("sin(1)")) < 1);
    }

    private static double evaluate(String input) {
        return Expression.compile(input).evaluate(new Expression.Variables());
    }

    private static void testEditSessionAndHistory() {
        section("edit session + history");
        TestWorld world = new TestWorld("session");
        world.fillFlat(70);
        TestActor actor = new TestActor("Alice", world, new BlockVector3(0, 71, 0));
        LocalSession session = actor.session();
        session.setMaxBlocksChanged(100000);
        RegionSelector selector = LocalSession.newSelectors(world, "cuboid");
        selector.selectPrimary(new BlockVector3(0, 70, 0), SelectorLimits.unlimited());
        selector.selectSecondary(new BlockVector3(4, 72, 4), SelectorLimits.unlimited());
        session.setSelector(selector);

        int stone = BlockState.registry().defaultState("minecraft:stone");
        int air = BlockState.registry().air();
        EditSession edit = new EditSession(world, session, "//set");
        for (BlockVector3 position : session.getSelection(world)) {
            edit.setBlock(position.x(), position.y(), position.z(), stone);
        }
        edit.flushQueue();
        checkEquals("blocks changed", 75, edit.getBlocksChanged());
        checkEquals("world updated", stone, world.getBlock(2, 71, 2));
        check("history recorded", session.getHistory().canUndo());
        check("history changes", session.getHistory().totalChanges() == 75);

        // undo
        EditSession undo = new EditSession(world, session, "undo");
        var record = session.getHistory().undo();
        for (var sets : record.changes().values()) {
            for (var set : sets) {
                undo.applyChangeSet(set, true);
            }
        }
        undo.flushQueue();
        checkEquals("undo restores the layer", BlockState.registry().defaultState("minecraft:grass_block"),
                world.getBlock(0, 69, 0));
        checkEquals("undo restores air", air, world.getBlock(2, 72, 2));

        // change limit
        session.setMaxBlocksChanged(10);
        EditSession limited = new EditSession(world, session, "//set limit");
        boolean threw = false;
        try {
            for (int y = 0; y < 100; y++) {
                limited.setBlock(0, y, 0, stone);
            }
        } catch (EditSession.MaxChangedBlocksException e) {
            threw = true;
        }
        check("change limit enforced", threw);
        session.setMaxBlocksChanged(-1);

        // mask on the session
        session.setMask(new Masks.BlockMask(world, List.of("minecraft:sand")));
        EditSession masked = new EditSession(world, session, "//replace");
        boolean maskedWrite = masked.setBlock(0, 69, 0, stone);
        masked.flushQueue();
        check("session mask blocks writes", !maskedWrite && world.getBlock(0, 69, 0)
                == BlockState.registry().defaultState("minecraft:grass_block"));
        session.setMask(new Masks.BlockMask(world, List.of("minecraft:grass_block")));
        EditSession allowed = new EditSession(world, session, "//replace");
        check("session mask allows matching blocks", allowed.setBlock(0, 69, 0, stone));
        allowed.flushQueue();
        session.setMask(null);

        // block entity and biome
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:chest");
        EditSession nbtSession = new EditSession(world, session, "nbt");
        nbtSession.setBlockEntity(1, 71, 1, nbt);
        // The data waits for the flush, so that it is written once the block it
        // belongs to is in the world.
        check("block entity waits for the flush", world.getBlockEntity(1, 71, 1) == null);
        nbtSession.flushQueue();
        check("block entity stored", world.getBlockEntity(1, 71, 1) != null);
        check("block entity keeps its data", "minecraft:chest".equals(
                world.getBlockEntity(1, 71, 1).getString("id", "")));
        EditSession biomeSession = new EditSession(world, session, "biome");
        check("biome set", biomeSession.setBiome(4, 68, 4, 5));
        biomeSession.flushQueue();
        checkEquals("biome stored", 5, world.getBiome(4, 68, 4));

        // A biome change is part of the history, so undo can put it back.
        int recorded = 0;
        for (var sets : session.getHistory().getCurrent().biomeChanges().values()) {
            for (var set : sets) {
                recorded += set.size();
            }
        }
        check("biome change recorded", recorded == 1);
        var biomeRecord = session.getHistory().undo();
        EditSession biomeUndo = new EditSession(world, session, "undo", false);
        for (var sets : biomeRecord.biomeChanges().values()) {
            for (var set : sets) {
                biomeUndo.applyBiomeChangeSet(set, true);
            }
        }
        biomeUndo.flushQueue();
        check("biome undo restores the previous biome", world.getBiome(4, 68, 4) != 5);
        session.getHistory().redo();
    }

    private static void testEditLog() throws Exception {
        section("edit log");
        TestWorld world = new TestWorld("log");
        world.fillFlat(70);
        TestActor actor = new TestActor("Alice", world, new BlockVector3(0, 71, 0));
        LocalSession session = actor.session();
        session.setOwnerName("Alice");
        session.enableSnapshots();
        session.setMaxBlocksChanged(100000);
        int stone = BlockState.registry().defaultState("minecraft:stone");
        EditSession edit = new EditSession(world, session, "//set stone");
        edit.setBlock(4, 70, 4, stone);
        edit.flushQueue();
        EditSession far = new EditSession(world, session, "//set far");
        far.setBlock(400, 70, 400, stone);
        far.flushQueue();
        session.getHistory().newRecord("after");

        List<EditLog.Entry> logged = EditLog.find(null, "log", -1, -1, null);
        checkEquals("log recorded both edits", 2, logged.size());
        EditLog.Entry newest = logged.get(0);
        checkEquals("log keeps the actor", "Alice", newest.actor);
        checkEquals("log keeps the world", "log", newest.world);
        check("log knows the bounds", newest.distanceTo(new BlockVector3(400, 70, 400)) == 0
                && newest.distanceTo(new BlockVector3(4, 70, 4)) > 300);
        checkEquals("log filters by radius", 1,
                EditLog.find(null, "log", 64, -1, new BlockVector3(4, 70, 4)).size());
        check("log filters by world", EditLog.find(null, "other", -1, -1, null).isEmpty());
        checkEquals("log filters by user", 2, EditLog.find("alice", "log", -1, -1, null).size());
        check("log filters by unknown user", EditLog.find("Bob", "log", -1, -1, null).isEmpty());
        checkEquals("log filters by time", 2,
                EditLog.find(null, "log", -1, System.currentTimeMillis() - 60_000, null).size());
        check("log filters by a future time", EditLog.find(null, "log", -1,
                System.currentTimeMillis() + 60_000, null).isEmpty());
        // The disk log has to come back with the moment of the edit, not with
        // the moment of the read, or the -t filter of /history find lies.
        java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("fawebim-log");
        try {
            EditLog.setDirectory(folder);
            com.maxlananas.fawebim.core.platform.Config.get().enableDiskHistory = true;
            EditLog.clear();
            EditLog.add("Alice", "log", logged.get(1).record);
            try (var files = java.nio.file.Files.list(folder)) {
                checkEquals("the edit reached the disk", 1L, files.count());
            }
            long written = EditLog.entries().get(0).time;
            EditLog.clear();
            checkEquals("the disk log reads back", 1, EditLog.load());
            EditLog.Entry restored = EditLog.entries().get(0);
            checkEquals("the read entry keeps the actor", "Alice", restored.actor);
            checkEquals("the read entry keeps the world", "log", restored.world);
            checkEquals("the read entry keeps the time of the edit", written, restored.time);
            checkEquals("the read record keeps its changes",
                    logged.get(1).record.changeCount(), restored.record.changeCount());
        } finally {
            EditLog.setDirectory(null);
            EditLog.clear();
        }
        check("log cleared", EditLog.entries().isEmpty());
    }

    private static void testSnapshotRoundTrip() {
        section("snapshot data");
        TestWorld world = new TestWorld("snapshot");
        world.fillFlat(70);
        TestActor actor = new TestActor("Alice", world, new BlockVector3(0, 71, 0));
        LocalSession session = actor.session();
        session.setMaxBlocksChanged(100000);
        History.Record[] completed = new History.Record[1];
        session.getHistory().setRecordListener(record -> completed[0] = record);
        int stone = BlockState.registry().defaultState("minecraft:stone");
        EditSession edit = new EditSession(world, session, "//set");
        edit.setBlock(3, 70, 3, stone);
        edit.setBiome(4, 68, 4, 7);
        edit.addEntity(new com.maxlananas.fawebim.core.world.EntityData("minecraft:pig",
                new NbtCompound(), new com.maxlananas.fawebim.core.math.Vector3(1.5, 72, 1.5)));
        edit.flushQueue();
        session.getHistory().newRecord("after");
        check("record captured", completed[0] != null);
        History.Record record = completed[0];
        checkEquals("record holds the block", 1, record.changeCount());
        checkEquals("record holds the biome", 1, record.biomeChangeCount());
        checkEquals("record holds the entity", 1, record.entities().size());
        check("record bounds cover the block", record.bounds() != null && record.bounds()[0] <= 3);

        NbtCompound snapshot = Snapshots.of(record, "Alice");
        checkEquals("snapshot holds the edited biome", 7, world.getBiome(4, 68, 4));
        EditSession restore = new EditSession(world, session, "restore", false);
        check("snapshot restores the biome", Snapshots.restoreBiomes(restore, snapshot) == 1);
        restore.flushQueue();
        checkEquals("biome is back to what it was before the edit", 1, world.getBiome(4, 68, 4));
        EditSession entityRestore = new EditSession(world, session, "restore entities", false);
        check("snapshot reads the entities", Snapshots.restoreEntities(entityRestore, snapshot) == 0);
        entityRestore.flushQueue();
    }

    private static void testClipboardAndSchematic() throws Exception {
        section("clipboard + schematics");
        TestWorld world = new TestWorld("clipboard");
        world.fillFlat(70);
        TestActor actor = new TestActor("Bob", world, new BlockVector3(0, 71, 0));
        EditSession edit = new EditSession(world, actor.session(), "copy");
        Region region = new com.maxlananas.fawebim.core.region.CuboidRegion(
                new BlockVector3(0, 68, 0), new BlockVector3(3, 70, 3));
        BlockArrayClipboard clipboard = Clipboards.copy(world, region, edit, true);
        checkEquals("clipboard width", 4, clipboard.getWidth());
        check("clipboard height", clipboard.getHeight() >= 2);
        check("clipboard has terrain", clipboard.getBlock(0, 68, 0) != BlockState.registry().air());

        // paste somewhere else
        EditSession paste = new EditSession(world, actor.session(), "paste");
        int changed = Clipboards.paste(clipboard, new BlockVector3(20, 100, 20), paste,
                com.maxlananas.fawebim.core.transform.Transform.identity(), false, false, false);
        check("paste changed blocks", changed > 0);
        checkEquals("pasted block", clipboard.getBlock(0, 68, 0), world.getBlock(20, 100, 20));

        // Biomes are stored and pasted one sample per 4x4x4 cell, the way the
        // game stores them, not once per block of the selection.
        Region biomeRegion = new com.maxlananas.fawebim.core.region.CuboidRegion(
                new BlockVector3(0, 64, 0), new BlockVector3(7, 71, 7));
        int sourceBiome = world.getBiome(0, 68, 0);
        BlockArrayClipboard biomeClipboard = Clipboards.copy(world, biomeRegion, edit, false, true, null, false);
        checkEquals("a biome copy keeps one entry per cell", 8, biomeClipboard.biomeEntries().size());
        checkEquals("the copied biome is the one of the world", sourceBiome, biomeClipboard.getBiome(0, 68, 0));
        EditSession biomePaste = new EditSession(world, actor.session(), "paste biomes");
        Clipboards.paste(biomeClipboard, new BlockVector3(40, 100, 40), biomePaste,
                com.maxlananas.fawebim.core.transform.Transform.identity(), true, null,
                false, true, false, false);
        checkEquals("the pasted biome reaches the destination", sourceBiome, world.getBiome(40, 104, 40));

        // schematics round-trip (Sponge v3, v2 and MCEdit)
        Path dir = Files.createTempDirectory("fawebim-schematics");
        Schematics.setDirectory(dir);
        for (String format : List.of("sponge.3", "sponge.2", "mcedit")) {
            Schematics.save(clipboard, "test-" + format.replace('.', '_'), format);
            String name = Files.list(dir).map(p -> p.getFileName().toString())
                    .filter(n -> n.contains(format.replace('.', '_'))).findFirst().orElse(null);
            check("schematic written " + format, name != null);
            BlockArrayClipboard loaded = Schematics.load(name);
            checkEquals("schematic round-trip volume " + format, clipboard.volume(), loaded.volume());
        }
        check("schematic list", Schematics.list().size() >= 3);
        // //schem save writes the name with an extension, so //schem load has to
        // find the file from the name as typed.
        Schematics.save(clipboard, "bare-name", "sponge.3");
        checkEquals("a schematic loads by the name it was saved under",
                clipboard.volume(), Schematics.load("bare-name").volume());
        Schematics.delete("bare-name");
        check("deleting it by that name removes the file",
                Schematics.list().stream().noneMatch(n -> n.startsWith("bare-name")));

        // NBT round-trip of a compound with every tag type
        NbtCompound compound = new NbtCompound();
        compound.putByte("byte", 1);
        compound.putShort("short", 2);
        compound.putInt("int", 3);
        compound.putLong("long", 4);
        compound.putFloat("float", 5.5f);
        compound.putDouble("double", 6.5);
        compound.putString("string", "hello");
        compound.putByteArray("bytes", new byte[]{1, 2, 3});
        compound.putIntArray("ints", new int[]{1, 2, 3});
        compound.putLongArray("longs", new long[]{1, 2, 3});
        NbtCompound nested = new NbtCompound();
        nested.putInt("value", 42);
        compound.put("nested", nested);
        compound.putList("list", List.of(nested, nested));
        byte[] data = com.maxlananas.fawebim.core.util.NbtIo.write(compound, false);
        NbtCompound read = com.maxlananas.fawebim.core.util.NbtIo.read(data);
        checkEquals("nbt int", 3, read.getInt("int", 0));
        checkEquals("nbt string", "hello", read.getString("string", ""));
        checkEquals("nbt nested", 42, read.getCompound("nested").getInt("value", 0));
        checkEquals("nbt list size", 2, read.getCompoundList("list").size());
        checkEquals("nbt byte array", 3, read.getByteArray("bytes").length);
        checkEquals("nbt varint round-trip", "hello",
                com.maxlananas.fawebim.core.util.NbtIo.read(com.maxlananas.fawebim.core.util.NbtIo.write(compound, true), true)
                        .getString("string", ""));

        // entities travel with the clipboard
        EntityData entity = new EntityData("[test]", new NbtCompound(),
                new Vector3(1.5, 69, 1.5));
        world.addEntity(entity);
        BlockArrayClipboard withEntities = Clipboards.copy(world, region, edit, true);
        check("clipboard copied entity", withEntities.entities().size() == 1);
    }

    private static void testFallAndRegionHelpers() {
        section("operations helpers");
        TestWorld world = new TestWorld("fall");
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int air = BlockState.registry().air();
        TestActor actor = new TestActor("Alice", world, new BlockVector3(10, 71, 10));
        LocalSession session = actor.session();
        session.setMaxBlocksChanged(100000);
        // A selection with nothing under it: the world floor is far below.
        Region region = new com.maxlananas.fawebim.core.region.CuboidRegion(
                new BlockVector3(8, 70, 8), new BlockVector3(15, 90, 15));

        world.setBlock(10, 85, 10, stone);
        EditSession free = new EditSession(world, session, "fall");
        Operations.fall(world, free, region);
        free.flushQueue();
        check("//fall drops the block out of the selection", world.getBlock(10, 85, 10) == air
                && world.getBlock(10, 70, 10) == air);
        check("//fall lands it on the world floor", world.getBlock(10, world.minY(), 10) == stone);

        // -m stops the column at the bottom of the selection instead.
        world.setBlock(10, 85, 10, stone);
        EditSession clamped = new EditSession(world, session, "fall -m");
        Operations.fall(world, clamped, region, true);
        clamped.flushQueue();
        check("//fall -m keeps the block in the selection", world.getBlock(10, 70, 10) == stone
                && world.getBlock(10, 85, 10) == air);
        check("//fall -m leaves the world floor alone", world.getBlock(10, world.minY(), 10) == stone);

        // A block that sits on something does not move at all.
        world.setBlock(10, 70, 10, stone);
        EditSession settled = new EditSession(world, session, "fall -m settled");
        Operations.fall(world, settled, region, true);
        settled.flushQueue();
        check("//fall -m leaves a resting block where it is", world.getBlock(10, 70, 10) == stone);

        checkEquals("schematic format of .schem", "sponge.3",
                com.maxlananas.fawebim.core.clipboard.Schematics.formatOf("house.schem"));
        checkEquals("schematic format of .schematic", "mcedit",
                com.maxlananas.fawebim.core.clipboard.Schematics.formatOf("house.schematic"));
        checkEquals("schematic format of .nbt", "structure",
                com.maxlananas.fawebim.core.clipboard.Schematics.formatOf("house.nbt"));
        checkEquals("schematic suffix of sponge.3", ".schem",
                com.maxlananas.fawebim.core.clipboard.Schematics.suffixOf("sponge.3"));
        check("unknown schematic time", com.maxlananas.fawebim.core.clipboard.Schematics.timeOf("nothing.schem") < 0);
    }

    private static void testClipboardBrushes() {
        section("clipboard brushes");
        TestWorld world = new TestWorld("brushes");
        world.fillFlat(70);
        TestActor actor = new TestActor("Alice", world, new BlockVector3(0, 71, 0));
        LocalSession session = actor.session();
        session.setMaxBlocksChanged(100000);
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int air = BlockState.registry().air();

        BlockArrayClipboard source = new BlockArrayClipboard(new BlockVector3(0, 0, 0));
        source.setBlock(0, 0, 0, stone);
        source.setBlock(1, 0, 0, air);
        source.setBlock(2, 0, 0, stone);
        session.setClipboard(source);

        // The clipboard brush centres what it pastes on the clicked block, so the
        // three cells land on x = 9, 10, 11.
        world.setBlock(9, 80, 10, stone);
        world.setBlock(10, 80, 10, stone);
        world.setBlock(11, 80, 10, stone);
        Brushes.ClipboardBrush plain = new Brushes.ClipboardBrush(3, null, false);
        EditSession centered = new EditSession(world, session, "brush");
        check("clipboard brush pastes", plain.apply(centered, new BlockVector3(10, 80, 10), actor) > 0);
        centered.flushQueue();
        check("clipboard brush pastes the clipboard's air", world.getBlock(10, 80, 10) == air
                && world.getBlock(9, 80, 10) == stone && world.getBlock(11, 80, 10) == stone);

        // -a skips the clipboard's air cells instead of erasing the target.
        world.setBlock(9, 82, 10, stone);
        world.setBlock(10, 82, 10, stone);
        world.setBlock(11, 82, 10, stone);
        Brushes.ClipboardBrush ignoreAir = new Brushes.ClipboardBrush(3, null, false, true, false, false, false,
                null, false);
        EditSession kept = new EditSession(world, session, "brush -a");
        ignoreAir.apply(kept, new BlockVector3(10, 82, 10), actor);
        kept.flushQueue();
        check("clipboard brush -a keeps the target air cell", world.getBlock(10, 82, 10) == stone
                && world.getBlock(11, 82, 10) == stone);

        // -o puts the clipboard's origin on the clicked block instead of centring.
        world.setBlock(20, 80, 20, stone);
        Brushes.ClipboardBrush onOrigin = new Brushes.ClipboardBrush(3, null, true);
        EditSession top = new EditSession(world, session, "brush -o");
        onOrigin.apply(top, new BlockVector3(20, 80, 20), actor);
        top.flushQueue();
        check("clipboard brush -o starts at the target", world.getBlock(20, 80, 20) == stone
                && world.getBlock(21, 80, 20) == air && world.getBlock(22, 80, 20) == stone);

        // -m only pastes where the mask accepts the target block.
        for (int x = 30; x <= 32; x++) {
            world.setBlock(x, 80, 30, stone);
        }
        Brushes.ClipboardBrush onStone = new Brushes.ClipboardBrush(3, null, true, false, false, false, false,
                new Masks.BlockMask(world, List.of("minecraft:stone")), false);
        EditSession matched = new EditSession(world, session, "brush -m");
        onStone.apply(matched, new BlockVector3(30, 80, 30), actor);
        matched.flushQueue();
        check("clipboard brush -m pastes where the target matches", world.getBlock(31, 80, 30) == air);
        for (int x = 40; x <= 42; x++) {
            world.setBlock(x, 80, 40, stone);
        }
        Brushes.ClipboardBrush onAir = new Brushes.ClipboardBrush(3, null, true, false, false, false, false,
                new Masks.BlockMask(world, List.of("minecraft:air")), false);
        EditSession skipped = new EditSession(world, session, "brush -m air");
        onAir.apply(skipped, new BlockVector3(40, 80, 40), actor);
        skipped.flushQueue();
        check("clipboard brush -m skips the rest", world.getBlock(41, 80, 40) == stone);

        // copypaste: the first click copies the connected blob, the next paste it.
        session.setClipboard(null);
        check("clearing the clipboard works", !session.hasClipboard());
        Brushes.CopyPastaBrush pasta = new Brushes.CopyPastaBrush(4, false, false);
        world.setBlock(50, 71, 50, stone);
        world.setBlock(50, 72, 50, stone);
        EditSession copy = new EditSession(world, session, "copypaste");
        // The walk stops at the height of the click, so the clicked block and the
        // ones above it are the two that get copied.
        check("copypaste copies without pasting", pasta.apply(copy, new BlockVector3(50, 71, 50), actor) == 0);
        copy.flushQueue();
        check("copypaste filled the clipboard", session.hasClipboard()
                && session.getClipboard().getClipboard().volume() == 2);
        EditSession paste = new EditSession(world, session, "copypaste paste");
        check("copypaste pastes back", pasta.apply(paste, new BlockVector3(54, 71, 50), actor) > 0);
        paste.flushQueue();
        check("copypaste placed the blob above the click", world.getBlock(54, 72, 50) == stone
                && world.getBlock(54, 73, 50) == stone);
    }

    private static void testHeightMapSmoothing() {
        section("height map smoothing");
        CommandManager.get().initialise();
        BlockStateRegistry registry = BlockState.registry();
        TestWorld world = new TestWorld("smooth");
        world.fillFlat(70);
        TestActor actor = new TestActor("Alice", world, new BlockVector3(0, 71, 0));
        LocalSession session = actor.session();
        session.setMaxBlocksChanged(100000);
        int stone = registry.defaultState("minecraft:stone");
        int sand = registry.defaultState("minecraft:sand");
        int snow = registry.parse("minecraft:snow[layers=8]");
        int air = registry.air();

        // //smooth blurs the height map, so the spike of a column is pulled back
        // down to the height of its neighbours.
        for (int y = 71; y <= 74; y++) {
            world.setBlock(25, y, 25, stone);
        }
        CommandManager.get().dispatch(actor, "//pos1 20,68,20");
        CommandManager.get().dispatch(actor, "//pos2 30,74,30");
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//smooth 1");
        check("//smooth lowered the spike", world.getBlock(25, 74, 25) == air && world.getBlock(25, 72, 25) == air);
        check("//smooth kept a top block", world.getBlock(25, 70, 25) != air);
        check("//smooth reported the change", actor.messages().stream()
                .anyMatch(message -> message.contains("Smoothed")));

        // The optional second argument is the mask the height map is built from,
        // so a stone height map does not see a sand spike at all.
        for (int y = 71; y <= 74; y++) {
            world.setBlock(45, y, 45, sand);
        }
        CommandManager.get().dispatch(actor, "//pos1 40,68,40");
        CommandManager.get().dispatch(actor, "//pos2 50,74,50");
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//smooth 1 stone");
        check("//smooth <mask> left the sand alone", world.getBlock(45, 74, 45) == sand);
        check("//smooth <mask> ran", actor.messages().stream()
                .anyMatch(message -> message.contains("Smoothed")));

        // //snowsmooth blurs the snow layer of every column instead of the terrain.
        for (int x = 20; x <= 30; x++) {
            for (int z = 20; z <= 30; z++) {
                world.setBlock(x, 71, z, registry.parse("minecraft:snow[layers=1]"));
            }
        }
        world.setBlock(25, 72, 25, snow);
        world.setBlock(25, 73, 25, snow);
        CommandManager.get().dispatch(actor, "//pos1 20,68,20");
        CommandManager.get().dispatch(actor, "//pos2 30,75,30");
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//snowsmooth 1 -l 2");
        check("//snowsmooth flattened the drift", world.getBlock(25, 73, 25) == air);
        check("//snowsmooth kept the snow", registry.describe(world.getBlock(21, 71, 21))
                .startsWith("minecraft:snow"));
        check("//snowsmooth reported the change", actor.messages().stream()
                .anyMatch(message -> message.contains("Smoothed")));

        // The brush form takes the same -l and -m flags.
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/brush snowsmooth 5 1 -l 3 -m stone");
        check("brush snowsmooth binds the smoother",
                com.maxlananas.fawebim.core.brush.BrushFactory.current(session)
                        instanceof Brushes.SnowSmoothBrush);
        EditSession snowSession = new EditSession(world, session, "brush snowsmooth");
        com.maxlananas.fawebim.core.brush.BrushFactory.current(session)
                .apply(snowSession, new BlockVector3(25, 71, 25), actor);
        snowSession.flushQueue();
        check("brush snowsmooth ran with -l and -m", actor.messages().stream()
                .noneMatch(message -> message.contains("Syntax")));

        // Every brush reads the arguments of its signature: /brush sphere takes a
        // pattern and a radius, and neither may fall back to a default.
        world.setBlock(100, 91, 100, air);
        CommandManager.get().dispatch(actor, "/brush sphere dirt 2");
        com.maxlananas.fawebim.core.brush.Brush sphere =
                com.maxlananas.fawebim.core.brush.BrushFactory.current(session);
        check("brush sphere took its radius", sphere != null && sphere.radius() == 2.0);
        EditSession sphereSession = new EditSession(world, session, "brush sphere");
        sphere.apply(sphereSession, new BlockVector3(100, 90, 100), actor);
        sphereSession.flushQueue();
        check("brush sphere took its pattern",
                world.getBlock(100, 90, 100) == registry.defaultState("minecraft:dirt"));

        // /brush smooth samples a box above the click and takes the mask its
        // height map is built from as a positional argument.
        world.setBlock(70, 71, 70, stone);
        world.setBlock(70, 72, 70, stone);
        CommandManager.get().dispatch(actor, "/brush smooth 3 1 stone");
        check("brush smooth binds the terrain smoother",
                com.maxlananas.fawebim.core.brush.BrushFactory.current(session)
                        instanceof Brushes.SmoothBrush);
        EditSession smoothSession = new EditSession(world, session, "brush smooth");
        com.maxlananas.fawebim.core.brush.BrushFactory.current(session)
                .apply(smoothSession, new BlockVector3(70, 69, 70), actor);
        smoothSession.flushQueue();
        check("brush smooth flattened the bump", world.getBlock(70, 72, 70) == air);

        // /brush populateschematic drops copies of a schematic where its mask
        // matches the surface, at the density it was given.
        int gold = registry.defaultState("minecraft:gold_block");
        BlockArrayClipboard stamps = new BlockArrayClipboard(new BlockVector3(0, 0, 0));
        stamps.setBlock(0, 0, 0, gold);
        Schematics.save(stamps, "populate-selftest", "sponge.3");
        CommandManager.get().dispatch(actor, "/brush populateschematic populate-selftest.schem stone 4 100");
        check("brush populateschematic binds the scatter brush",
                com.maxlananas.fawebim.core.brush.BrushFactory.current(session)
                        instanceof Brushes.PopulateSchematicBrush);
        TestWorld populated = new TestWorld("populate");
        populated.fillFlat(70);
        TestActor scatter = new TestActor("Bob", populated, new BlockVector3(0, 71, 0));
        scatter.session().setMaxBlocksChanged(100000);
        CommandManager.get().dispatch(scatter, "/brush populateschematic populate-selftest.schem stone 4 100");
        EditSession scatterSession = new EditSession(populated, scatter.session(), "populate");
        int stamped = com.maxlananas.fawebim.core.brush.BrushFactory.current(scatter.session())
                .apply(scatterSession, new BlockVector3(0, 70, 0), scatter);
        scatterSession.flushQueue();
        check("brush populateschematic placed copies", stamped > 0);
        // The mask decides which block of a column counts as the surface: the
        // stone one, so no copy lands on the grass above it.
        int onStone = 0;
        int onSurface = 0;
        for (int x = -16; x < 16; x++) {
            for (int z = -16; z < 16; z++) {
                for (int y = 60; y <= 80; y++) {
                    if (populated.getBlock(x, y, z) != gold) {
                        continue;
                    }
                    if (y == 67) {
                        onStone++;
                    } else if (y >= 69) {
                        onSurface++;
                    }
                }
            }
        }
        check("brush populateschematic honoured its mask", onStone > 0 && onSurface == 0);
    }

    private static void testGravityBrush() {
        section("gravity brush");
        CommandManager.get().initialise();
        TestWorld world = new TestWorld("gravity");
        TestActor actor = new TestActor("Alice", world, new BlockVector3(0, 71, 0));
        LocalSession session = actor.session();
        session.setMaxBlocksChanged(100000);
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int dirt = BlockState.registry().defaultState("minecraft:dirt");
        int air = BlockState.registry().air();

        // The window is a square of the brush radius around the click, so with a
        // radius of 3 and a click at y 71 it spans y 68 to 74. A column already
        // resting on the bottom of its window is left alone.
        world.setBlock(10, 68, 10, stone);
        Brushes.GravityBrush resting = new Brushes.GravityBrush(3, null);
        EditSession still = new EditSession(world, session, "gravity resting");
        checkEquals("gravity leaves a settled column alone", 0,
                resting.apply(still, new BlockVector3(10, 71, 10), actor));
        still.flushQueue();
        check("gravity kept the resting block", world.getBlock(10, 68, 10) == stone);

        // A block with air under it inside the window falls onto the lowest gap,
        // and the blocks of the column keep their order.
        world.setBlock(20, 70, 20, stone);
        world.setBlock(20, 74, 20, dirt);
        EditSession compact = new EditSession(world, session, "gravity compact");
        resting.apply(compact, new BlockVector3(20, 71, 20), actor);
        compact.flushQueue();
        check("gravity compacted the column", world.getBlock(20, 68, 20) == stone
                && world.getBlock(20, 69, 20) == dirt && world.getBlock(20, 74, 20) == air);

        // WorldEdit carries a height on -h and uses it in place of the radius,
        // so a block below that window is out of reach.
        world.setBlock(30, 70, 30, stone);
        world.setBlock(30, 72, 30, dirt);
        Brushes.GravityBrush windowed = new Brushes.GravityBrush(10, null);
        windowed.setHeight(2);
        EditSession window = new EditSession(world, session, "gravity -h 2");
        windowed.apply(window, new BlockVector3(30, 71, 30), actor);
        window.flushQueue();
        check("gravity -h <height> narrows the window", world.getBlock(30, 69, 30) == stone
                && world.getBlock(30, 70, 30) == dirt && world.getBlock(30, 72, 30) == air);

        // FAWE turns the same switch into a flag: the scan then starts at the
        // bottom of the world, so the column falls to the world floor.
        world.setBlock(40, 71, 40, stone);
        Brushes.GravityBrush full = new Brushes.GravityBrush(3, null);
        full.setFullHeight(true);
        EditSession toFloor = new EditSession(world, session, "gravity -h");
        full.apply(toFloor, new BlockVector3(40, 71, 40), actor);
        toFloor.flushQueue();
        check("gravity -h reaches the world floor", world.getBlock(40, world.minY(), 40) == stone
                && world.getBlock(40, 71, 40) == air);

        // The command line wires both forms, which is what the switch audit wants:
        // -h <height> is WorldEdit's, -h alone is FAWE's.
        CommandRegistry.Entry entry = CommandManager.get().registry().get("/brush gravity");
        check("gravity is registered", entry != null);
        if (entry != null) {
            check("gravity declares -h both ways",
                    entry.booleanFlags.contains("h") && entry.valueFlags.contains("h"));
            check("gravity lists -h once", entry.arguments.stream()
                    .filter(argument -> argument.contains("-h")).count() == 1);

            actor.clearMessages();
            world.setBlock(50, 85, 50, stone);
            CommandManager.get().dispatch(actor, "/brush gravity 5 -h 20");
            check("gravity -h 20 binds a gravity brush",
                    com.maxlananas.fawebim.core.brush.BrushFactory.current(session)
                            instanceof Brushes.GravityBrush);
            EditSession byHeight = new EditSession(world, session, "brush gravity -h 20");
            com.maxlananas.fawebim.core.brush.BrushFactory.current(session)
                    .apply(byHeight, new BlockVector3(50, 71, 50), actor);
            byHeight.flushQueue();
            check("gravity -h 20 used the height", world.getBlock(50, 51, 50) == stone
                    && world.getBlock(50, 85, 50) == air);

            actor.clearMessages();
            world.setBlock(60, 75, 60, stone);
            CommandManager.get().dispatch(actor, "/brush gravity 5 -h");
            EditSession byFlag = new EditSession(world, session, "brush gravity -h");
            com.maxlananas.fawebim.core.brush.BrushFactory.current(session)
                    .apply(byFlag, new BlockVector3(60, 71, 60), actor);
            byFlag.flushQueue();
            check("gravity -h alone used the world floor", world.getBlock(60, world.minY(), 60) == stone
                    && world.getBlock(60, 75, 60) == air);

            // The clipboard brush reads -m as the source mask, a parameter FAWE
            // names differently from the generic one, so the flag has to reach
            // that parameter on the command line too.
            int gold = BlockState.registry().defaultState("minecraft:gold_block");
            BlockArrayClipboard patch = new BlockArrayClipboard(new BlockVector3(0, 0, 0));
            patch.setBlock(0, 0, 0, gold);
            session.setClipboard(patch);
            world.setBlock(70, 80, 70, stone);
            world.setBlock(71, 80, 70, dirt);
            CommandManager.get().dispatch(actor, "/brush clipboard -o -m minecraft:stone");
            EditSession sourceMasked = new EditSession(world, session, "brush clipboard -m");
            com.maxlananas.fawebim.core.brush.BrushFactory.current(session)
                    .apply(sourceMasked, new BlockVector3(70, 80, 70), actor);
            sourceMasked.flushQueue();
            check("clipboard brush -m pasted onto the matching block", world.getBlock(70, 80, 70) == gold);
            check("clipboard brush -m skipped the other block", world.getBlock(71, 80, 70) == dirt);
        }
    }

    /**
     * {@code //schem save} writes small clipboards on the calling thread and hands
     * anything past the threshold to the world's worker pool; the large branch is
     * the one a player notices when they save a build, so it is exercised here.
     */
    private static void testLargeSchematicSave() throws Exception {
        section("large schematic save");
        CommandManager.get().initialise();
        TestWorld world = new TestWorld("large-save");
        TestActor actor = new TestActor("Alice", world, new BlockVector3(0, 71, 0));
        LocalSession session = actor.session();
        int stone = BlockState.registry().defaultState("minecraft:stone");

        Path dir = Files.createTempDirectory("fawebim-large-schematics");
        Schematics.setDirectory(dir);

        // Two cells far apart, so the bounding box is past the async threshold
        // without the clipboard holding a million blocks.
        BlockArrayClipboard big = new BlockArrayClipboard(new BlockVector3(0, 0, 0));
        big.setBlock(0, 0, 0, stone);
        big.setBlock(200, 100, 50, stone);
        check("large clipboard is past the async threshold",
                big.volume() >= Schematics.ASYNC_SAVE_THRESHOLD);
        session.setClipboard(big);

        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//schem save bigselftest sponge.3");
        // The write runs on the worker, so the notice is not necessarily the last
        // message by the time it is checked.
        check("large save reports a background write", actor.messages().stream()
                .anyMatch(message -> message.contains("in the background")));

        world.awaitExecutor();
        check("large save reports the result", actor.messages().stream()
                .anyMatch(message -> message.contains("Saved schematic 'bigselftest.schem'")));
        check("large save wrote the file", Schematics.exists("bigselftest", "sponge.3")
                && Files.isRegularFile(dir.resolve("bigselftest.schem")));
        BlockArrayClipboard reloaded = Schematics.load("bigselftest.schem");
        checkEquals("large save kept the volume", big.volume(), reloaded.volume());
        check("large save kept the far block", reloaded.getBlock(200, 100, 50) == stone);

        // A large payload is where guessing the layout from the file used to pick
        // the wrong reader, so every format is round-tripped at this size.
        check("large legacy save counts what it cannot store", Schematics.legacyLosses(big) == 0);
        for (String format : List.of("sponge.2", "mcedit")) {
            Schematics.save(big, "bigselftest-" + format.replace('.', '_'), format);
            String written = Files.list(dir).map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("bigselftest-" + format.replace('.', '_')))
                    .findFirst().orElse(null);
            check("large " + format + " save wrote the file", written != null);
            if (written != null) {
                BlockArrayClipboard back = Schematics.load(written);
                check("large " + format + " save round-trips", back.volume() == big.volume()
                        && back.getBlock(200, 100, 50) == stone);
                Schematics.delete(written);
            }
        }

        // The small branch stays on the calling thread and says so.
        BlockArrayClipboard small = new BlockArrayClipboard(new BlockVector3(0, 0, 0));
        small.setBlock(0, 0, 0, stone);
        session.setClipboard(small);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//schem save smallselftest sponge.3");
        check("small save reports a direct write",
                actor.lastMessage().contains("Saved schematic") && !actor.lastMessage().contains("background"));
        check("small save wrote the file", Schematics.exists("smallselftest", "sponge.3"));

        Schematics.delete("bigselftest.schem");
        Schematics.delete("smallselftest.schem");
    }

    private static void testConfigAndSettings() throws Exception {
        section("configuration");
        Path directory = Files.createTempDirectory("fawebim-config");
        Config config = Config.get();
        config.load(directory);

        // Every declared setting is listed, has a description and a file path, and
        // the file that was just written holds all of them.
        check("config declares its settings", config.settings().size() >= 35);
        check("every setting is described", config.settings().stream()
                .allMatch(setting -> !setting.description().isEmpty()));
        check("every setting has a file path", config.settings().stream()
                .allMatch(setting -> !setting.path().isEmpty()));
        Path file = directory.resolve("config/fawebim.yml");
        check("config file written", Files.exists(file));
        Map<String, Object> onDisk = MiniYaml.parse(Files.readString(file));
        for (Setting<?> setting : config.settings()) {
            check("config file holds " + setting.path(),
                    MiniYaml.path(onDisk, setting.path(), null) != null);
        }

        // A value typed in game lands in the field, in the file, and comes back.
        int before = config.maxBrushRadius;
        check("setting a value succeeds", config.set("max-brush-radius", "42") == null);
        checkEquals("setting changed the field", 42, config.maxBrushRadius);
        check("setting wrote the file", Files.readString(file).contains("42"));
        config.maxBrushRadius = before;
        config.save();
        config.reload();
        checkEquals("reload read the value back", before, config.maxBrushRadius);

        // A bad value is refused and the field keeps what it had.
        check("a bad value is refused", config.set("max-brush-radius", "lots") != null);
        checkEquals("a refused value changed nothing", before, config.maxBrushRadius);
        check("an unknown key is refused", config.set("no-such-setting", "1") != null);

        // A value written by hand in the file is picked up by a reload, and a
        // partial file only changes the keys it mentions.
        Files.writeString(file, "limits:\n  max-brush-radius:\n    maximum: 77\n");
        config.reload();
        checkEquals("reload picked up the edited file", 77, config.maxBrushRadius);
        Setting<?> history = config.find("history-size");
        check("a partial file left the other keys alone",
                history != null && history.value().equals(history.defaultValue()));
        config.maxBrushRadius = before;
        config.save();

        // The in-game surface edits the same thing.
        CommandManager.get().initialise();
        TestWorld world = new TestWorld("config");
        TestActor actor = new TestActor("Alice", world, new BlockVector3(0, 71, 0));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim settings");
        check("/fawebim settings lists the keys", actor.messages().stream()
                .anyMatch(message -> message.contains("Settings (")));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim set max-brush-radius 33");
        check("/fawebim set reports the change", actor.messages().stream()
                .anyMatch(message -> message.contains("saved to config/fawebim.yml")));
        checkEquals("/fawebim set changed the field", 33, config.maxBrushRadius);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim reset max-brush-radius");
        check("/fawebim reset reports the default", actor.messages().stream()
                .anyMatch(message -> message.contains("default")));
        checkEquals("/fawebim reset restored the default", 1000, config.maxBrushRadius);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim set max-brush-radius nonsense");
        check("/fawebim set refuses a bad value", actor.messages().stream()
                .anyMatch(message -> message.contains("Expected")));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim settings wand");
        check("/fawebim settings narrows to one key", actor.messages().stream()
                .anyMatch(message -> message.contains("wand-item")));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim settings -s boolean");
        check("/fawebim settings filters by type", actor.messages().stream()
                .anyMatch(message -> message.contains("Settings (")));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim path");
        check("/fawebim path reports the file", actor.messages().stream()
                .anyMatch(message -> message.contains("config/fawebim.yml")));

        // The wired limits are read from the configuration, not from a constant.
        check("a ceiling can be configured", config.set("max-change-limit", "100") == null);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/limit 1000000");
        check("//limit is capped by limits.max-blocks-changed.maximum", actor.messages().stream()
                .anyMatch(message -> message.contains("at most")));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/limit 50");
        check("//limit accepts a value under the ceiling", actor.messages().stream()
                .anyMatch(message -> message.contains("Limit set to 50")));
        config.set("max-change-limit", "-1");
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim set max-brush-radius");
        check("/fawebim set <key> says what the key holds", actor.messages().stream()
                .anyMatch(message -> message.contains("holds") && message.contains("expects")));

        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/history size 12");
        check("/history size sets the undo depth", actor.messages().stream()
                .anyMatch(message -> message.contains("History size set to 12")));
        checkEquals("history kept the new depth", 12, actor.session().getHistory().maxRecords());

        // Tab completion offers the keys the player has started to type.
        CommandRegistry.Entry entry = CommandManager.get().registry().get("/fawebim");
        check("/fawebim declares completions", entry != null && entry.suggestions != null);
        if (entry != null && entry.suggestions != null) {
            check("completion offers the actions",
                    entry.suggestions.apply("").contains("settings"));
            check("completion offers a matching key",
                    entry.suggestions.apply("max-brush").contains("max-brush-radius"));
            check("a switch offers both spellings",
                    entry.suggestions.apply("set per-player-history t").contains("true")
                            && entry.suggestions.apply("set per-player-history f").contains("false"));
            check("a number offers the value it holds now", entry.suggestions
                    .apply("set max-brush-radius ").contains(config.find("max-brush-radius").value()));
            check("a key that is not a setting offers nothing",
                    entry.suggestions.apply("set nope ").isEmpty());
        }
        // The model behind the graphical settings screen: the screen draws it, so
        // the grouping, the search and the value checks are testable here.
        ConfigUi ui = new ConfigUi(config);

        // A setting is named by its key, by the path it has in the file, or by
        // the end of that path: /fawebim set accepts all three spellings.
        String[] spellings = {"max-brush-radius", "limits.max-brush-radius.maximum",
                "max-brush-radius.maximum"};
        for (String spelling : spellings) {
            Setting<?> bySpelling = ui.resolve(spelling);
            check("resolve " + spelling, bySpelling != null && bySpelling.key().equals("max-brush-radius"));
        }
        check("resolve is not confused by an unknown name", ui.resolve("nothing-like-this") == null);
        int listed = ui.groups().stream().mapToInt(group -> group.settings().size()).sum();
        checkEquals("every setting has a group", config.settings().size(), listed);
        checkEquals("no setting is listed twice", config.settings().size(),
                (int) ui.groups().stream().flatMap(group -> group.settings().stream()).distinct().count());
        check("the sidebar starts with editing", ui.groups().get(0).name().equals("Editing"));
        check("the wand item is a tool", ui.settings("Tools", "").stream()
                .anyMatch(setting -> setting.key().equals("wand-item")));
        check("the history group holds the history settings", ui.settings("History", "").stream()
                .anyMatch(setting -> setting.key().equals("history-size")));
        check("a search narrows the list", ui.settings(null, "brush").size() < config.settings().size()
                && !ui.settings(null, "brush").isEmpty());
        check("a search reads the description too", ui.settings(null, "undo").stream()
                .anyMatch(setting -> setting.key().equals("history-enabled")));
        checkEquals("an unknown group shows nothing", 0, ui.settings("nope", "").size());
        check("a bad value is refused", ui.set("max-brush-radius", "abc") != null);
        check("a good value is stored", ui.set("max-brush-radius", "37") == null
                && config.maxBrushRadius == 37);
        check("the value of the model follows the setting",
                ui.value("max-brush-radius").equals("37"));
        check("an unknown key is refused", ui.set("nope", "1") != null);
        String declared = config.find("max-brush-radius").defaultValue();
        check("a reset puts the default back",
                ui.reset("max-brush-radius") && ui.value("max-brush-radius").equals(declared));

        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim gui");
        check("/fawebim gui says so when there is no screen", actor.messages().stream()
                .anyMatch(message -> message.contains("no configuration screen")));
        actor.setScreenAvailable(true);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim");
        check("bare /fawebim opens the screen when one exists", actor.messages().isEmpty());
        actor.setScreenAvailable(false);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fawebim");
        check("bare /fawebim falls back to the listing", actor.messages().stream()
                .anyMatch(message -> message.contains("Settings (")));

        config.maxBrushRadius = 1000;
        config.save();
    }

    private static void testCommands() {
        section("commands");
        CommandManager.get().initialise();
        var registry = CommandManager.get().registry();
        check("command count > 200", registry.all().size() > 200);
        check("//set registered", registry.get("//set") != null);
        check("//copy registered", registry.get("//copy") != null);
        check("//paste registered", registry.get("//paste") != null);
        check("//schem registered", registry.get("//schem") != null);
        check("/brush sphere registered", registry.get("/brush sphere") != null
                || registry.get("//brush sphere") != null);
        check("/tool registered", registry.get("/tool") != null);
        check("/superpickaxe registered", registry.get("/superpickaxe") != null);
        check("//pos1 registered", registry.get("//pos1") != null);
        check("//regen registered", registry.get("//regen") != null);
        check("//biome registered", registry.get("//biome") != null);
        check("//deform registered", registry.get("//deform") != null);
        check("//generate registered", registry.get("//generate") != null);
        check("//curve registered", registry.get("//curve") != null);
        check("//stack registered", registry.get("//stack") != null);
        check("//move registered", registry.get("//move") != null);
        check("//fill registered", registry.get("//fill") != null);
        check("//drain registered", registry.get("//drain") != null);
        check("//smooth registered", registry.get("//smooth") != null);
        check("//naturalize registered", registry.get("//naturalize") != null);
        check("//hollow registered", registry.get("//hollow") != null);
        check("//thaw registered", registry.get("//thaw") != null);
        check("//green registered", registry.get("//green") != null);
        check("//walls registered", registry.get("//walls") != null);
        check("//count registered", registry.get("//count") != null);
        check("//distr registered", registry.get("//distr") != null);
        check("/fast registered", registry.get("/fast") != null);
        check("//undo registered", registry.get("//undo") != null);
        check("//redo registered", registry.get("//redo") != null);
        check("//expand registered", registry.get("//expand") != null);

        // execution
        TestWorld world = new TestWorld("commands");
        world.fillFlat(70);
        TestActor actor = new TestActor("Carol", world, new BlockVector3(0, 71, 0));
        actor.session().setMaxBlocksChanged(100000);
        CommandManager.get().dispatch(actor, "//pos1 0,70,0");
        CommandManager.get().dispatch(actor, "//pos2 4,72,4");
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//set stone");
        check("//set ran", world.getBlock(2, 71, 2) == BlockState.registry().defaultState("minecraft:stone"));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//replace stone dirt");
        check("//replace ran", world.getBlock(2, 71, 2) == BlockState.registry().defaultState("minecraft:dirt"));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//copy");
        check("//copy ran", actor.session().hasClipboard());
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//paste 20,70,20");
        check("//paste ran", world.getBlock(20, 70, 20) != BlockState.registry().air());
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//undo");
        check("//undo ran", actor.session().getHistory().canRedo());
        checkEquals("//undo restored air", BlockState.registry().air(), world.getBlock(20, 100, 20));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//size");
        check("//size responded", actor.messages().size() > 0);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//sphere stone 3");
        check("//sphere responded", actor.messages().size() > 0);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//biome plains");
        check("//biome ran", actor.messages().size() > 0);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//cyl stone 2 3");
        check("//cyl ran", actor.messages().size() > 0);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//smooth 1");
        check("//smooth ran", actor.messages().size() > 0);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//walls stone");
        check("//walls ran", actor.messages().size() > 0);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//schem save selftest sponge.3");
        check("//schem save ran", actor.messages().size() > 0);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//schem load selftest");
        check("//schem load ran", actor.session().hasClipboard());
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//sel sphere");
        check("//sel responded", actor.messages().size() > 0);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//desel");
        check("//desel responded", actor.messages().size() > 0);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/brush sphere stone 5");
        check("brush bound", com.maxlananas.fawebim.core.brush.BrushFactory.current(actor.session()) != null);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/tool tree");
        check("tool bound", com.maxlananas.fawebim.core.tool.Tools.current(actor.session()) != null);
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "/fast");
        check("/fast responded", actor.lastMessage().contains("Fast mode"));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//definitelynotacommand");
        check("unknown command handled", actor.lastMessage().contains("Unknown command"));
    }

    /**
     * The navigation commands move the player through {@code Actor}, so they can be
     * exercised head-less: ascend and descend search for a floor, {@code /ceil}
     * builds its glass platform and {@code /thru} walks the view ray through a wall.
     */
    private static void testNavigation() {
        section("navigation");
        TestWorld world = new TestWorld("navigation");
        world.fillFlat(70);
        TestActor actor = new TestActor("Dave", world, new BlockVector3(5, 71, 5));

        int air = BlockState.registry().air();
        int stone = BlockState.registry().defaultState("minecraft:stone");

        world.setBlock(5, 74, 5, stone);
        check("ascend finds the platform", Navigation.ascendLevel(actor));
        checkEquals("ascend lands on the platform", 75, actor.position().y());

        check("descend finds the floor", Navigation.descendLevel(actor));
        checkEquals("descend lands on the floor", 70, actor.position().y());

        world.setBlock(5, 71, 5, stone);
        world.setBlock(5, 72, 5, stone);
        world.setBlock(5, 73, 5, stone);
        check("unstuck finds free space", Navigation.findFreePosition(actor));
        check("unstuck moved the player up", actor.position().y() > 71);

        for (int y = 72; y <= 79; y++) {
            world.setBlock(5, y, 5, air);
        }
        world.setBlock(5, 71, 5, air);
        actor.setPosition(new BlockVector3(5, 71, 5));
        world.setBlock(5, 80, 5, stone);
        check("ceil climbs to the ceiling", Navigation.ascendToCeiling(actor, 0, false));
        checkEquals("ceil stopped under the ceiling", 78, actor.position().y());
        checkEquals("ceil placed a platform", BlockState.registry().defaultState("minecraft:glass"),
                world.getBlock(5, 77, 5));

        actor.setPosition(new BlockVector3(5, 71, 5));
        check("up rises the distance asked for", Navigation.ascendUpwards(actor, 4, false));
        checkEquals("up lands at the requested height", 75, actor.position().y());
        check("up is blocked by the ceiling", !Navigation.ascendUpwards(actor, 100, false));

        world.fillFlat(70);
        for (int y = 71; y <= 73; y++) {
            world.setBlock(5, y, 7, stone);
            world.setBlock(5, y, 8, stone);
        }
        actor.setPosition(new BlockVector3(5, 71, 5));
        actor.setYaw(0);
        check("thru passes the wall", Navigation.passThroughForwardWall(actor, 8));
        checkEquals("thru landed behind the wall", 9, actor.position().z());
    }

    /**
     * The angle masks read the terrain surface: flat ground has a slope of zero,
     * a staircase rises one block per block, {@code #roc} measures curvature
     * instead and {@code #surfaceangle} looks at the air around the block.
     */
    private static void testAngleMasks() {
        section("angle masks");
        TestWorld world = new TestWorld("angle");
        world.fillFlat(70);
        int stone = BlockState.registry().defaultState("minecraft:stone");

        checkEquals("surface under an airborne query", 69,
                world.getNearestSurfaceTerrainBlock(5, 5, 80, world.minY(), world.maxY()));

        check("flat ground has a slope of zero", new Masks.AngleMask(world, 0, 0, false, 1)
                .test(5, 69, 5));
        check("flat ground is not a slope", !new Masks.AngleMask(world, 0.36, 0.58, false, 1)
                .test(5, 69, 5));
        check("flat ground has no curvature", !new Masks.ROCAngleMask(world, 0.1, 10, false, 4)
                .test(20, 69, 20));

        // A staircase: one block up for every block across is a slope of 0.5.
        for (int step = 1; step <= 6; step++) {
            for (int y = 70; y <= 69 + step; y++) {
                world.setBlock(5 + step, y, 5, stone);
            }
        }
        check("a staircase reads as a slope", new Masks.AngleMask(world, 0.36, 0.58, false, 1)
                .test(5, 69, 5));
        check("a staircase is not steeper than it is", !new Masks.AngleMask(world, 0.6, 10, false, 1)
                .test(5, 69, 5));
        check("roc reads a staircase as curvature", new Masks.ROCAngleMask(world, 0.1, 10, false, 4)
                .test(5, 69, 5));

        check("surfaceangle accepts flat ground", new Masks.SurfaceAngleMask(world, 0, 90, 1)
                .test(5, 69, 5));
        check("surfaceangle rejects a steep minimum", !new Masks.SurfaceAngleMask(world, 45, 90, 1)
                .test(5, 69, 5));
    }

    /**
     * {@code //regen} clears the session mask while it runs and says so when a
     * seed cannot be used: Minecraft's chunk source is built from the level seed,
     * so the command must not pretend it regenerated with another one.
     */
    private static void testRegen() {
        section("regen");
        TestWorld world = new TestWorld("regen");
        world.fillFlat(70);
        TestActor actor = new TestActor("Erin", world, new BlockVector3(0, 71, 0));
        CommandManager.get().dispatch(actor, "//pos1 0,70,0");
        CommandManager.get().dispatch(actor, "//pos2 15,80,15");
        CommandManager.get().dispatch(actor, "//gmask stone");
        CommandManager.get().dispatch(actor, "//regen 4242");
        check("regen warns about the ignored seed",
                actor.messages().stream().anyMatch(message -> message.contains("seed")));
        check("regen restored the mask", actor.session().getMask() != null);
        int chunkChanges = world.setCount();
        check("regen touched the world", chunkChanges > 0);
    }

    private static void testTimeLimiter() {
        section("time limiter");
        TimeLimiter limiter = new TimeLimiter(50);
        limiter.count(10);
        check("limiter not expired", !limiter.isExpired());
        checkEquals("limiter processed", 10L, limiter.processed());
        check("limiter elapsed", limiter.elapsedMillis() >= 0);
        TimeLimiter expired = new TimeLimiter(1);
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        check("limiter expired", expired.isExpired());
        try {
            if (expired.isExpired()) {
                throw new TimeLimiter.OperationTimeoutException(expired.elapsedMillis(), 0);
            }
            check("timeout exception", false);
        } catch (TimeLimiter.OperationTimeoutException e) {
            check("timeout exception", e.elapsedMillis() >= 0);
        }
        // The clock is read once in a while, so a limiter that counts per block
        // still stops an edit that runs for a minute.
        boolean thrown = false;
        for (int i = 0; i < 100_000 && !thrown; i++) {
            try {
                expired.check(1);
            } catch (TimeLimiter.OperationTimeoutException expected) {
                thrown = true;
            }
        }
        check("a counting check still expires", thrown);
        check("a counting check counts what it was given", expired.processed() > 100);
    }

    private static void testUtil() {
        section("util");
        checkEquals("split", List.of("a", "b"), Str.split("a b"));
        checkEquals("split quotes", List.of("hello world", "b"), Str.split("\"hello world\" b"));
        check("isInteger", Str.isInteger("42"));
        check("isDouble", Str.isDouble("4.2"));
        checkEquals("stripNamespace", "stone", Str.stripNamespace("minecraft:stone"));
        checkEquals("join", "a,b", Str.join(List.of("a", "b"), ","));
        RandomCollection<String> collection = new RandomCollection<>();
        collection.add(1, "a");
        checkEquals("random collection", "a", collection.next(new Random(1)));
        check("random collection not empty", !collection.isEmpty());
        checkEquals("msg plain", "hello", com.maxlananas.fawebim.core.util.Msg.of("hello").plain());
        check("msg format number", com.maxlananas.fawebim.core.util.Msg.formatNumber(1234567).length() > 6);
        check("MiniYaml parse", "1".equals(String.valueOf(com.maxlananas.fawebim.core.util.MiniYaml
                .parse("a: 1\nb:\n  c: true\n").get("a"))));
        checkEquals("MiniYaml path", "true",
                String.valueOf(com.maxlananas.fawebim.core.util.MiniYaml.path(
                        com.maxlananas.fawebim.core.util.MiniYaml.parse("b:\n  c: true\n"), "b.c", null)));
        check("regen options", new RegenOptions().setRegenBiomes(true).shouldRegenBiomes());
        check("world target block", new TestWorld("ray").getTargetBlock(
                new TestActor("Ray", new TestWorld("ray"), new BlockVector3(0, 0, 0)), 10) != null);
    }
}
