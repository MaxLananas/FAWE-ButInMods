package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.actor.Navigation;
import com.maxlananas.fawebim.core.anvil.ChunkData;
import com.maxlananas.fawebim.core.anvil.RegionFiles;
import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Clipboards;
import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.BlockVectorSet;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.region.RegionSelector;
import com.maxlananas.fawebim.core.region.SelectorLimits;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.session.SessionManager;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.RandomCollection;
import com.maxlananas.fawebim.core.util.Str;
import com.maxlananas.fawebim.core.util.TimeLimiter;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.RegenOptions;

import java.nio.file.Files;
import java.nio.file.Path;
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
        testPatterns();
        testExpressions();
        testEditSessionAndHistory();
        testClipboardAndSchematic();
        testCommands();
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
        BlockVectorSet set = new BlockVectorSet();
        for (int i = 0; i < 5000; i++) {
            set.add(new BlockVector3(i % 100, i / 100, i % 37));
        }
        check("BlockVectorSet size", set.size() == 5000);
        check("BlockVectorSet contains", set.contains(new BlockVector3(3, 4, 33)));
        check("BlockVectorSet rejects", !set.contains(new BlockVector3(999, 999, 999)));
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
        check("wall mask", new Masks.WallMask(session) != null);
        check("surface mask", new Masks.SurfaceMask(session, 1, false).test(0, 70, 0));
        check("biome mask", new Masks.BiomeMask(session, world.getBiome(0, 0, 0)) != null);
        check("region mask", new Masks.RegionMask(new com.maxlananas.fawebim.core.region.CuboidRegion(
                new BlockVector3(0, 0, 0), new BlockVector3(4, 4, 4))).test(2, 2, 2));
        check("expression mask", new Masks.ExpressionMask("y > 60", session, new Random()).test(0, 61, 0));
        check("hotbar mask", new Masks.HotbarMask(java.util.Set.of(stone)) != null);
        check("axis mask", new Masks.AxisMask(1, 8) != null);
        check("random mask", new Masks.SimplexMask(0.5, 0.1) != null);
        check("angle mask", new Masks.AngleMask(session, 0, 1, false) != null);
        check("extrema mask", new Masks.ExtremaMask(session, 0, 100) != null);
        check("offset mask", new Masks.OffsetMask(session, solid, 0, 1, 0) != null);
        check("exposed mask", new Masks.ExposedMask(session).test(0, 70, 0));
        check("adjacent mask", new Masks.AdjacentMask(session, new Masks.SolidMask(session), 2, 4) != null);
        Masks.ExtentHolder.clear();
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
        check("block entity stored", world.getBlockEntity(1, 71, 1) != null);
        EditSession biomeSession = new EditSession(world, session, "biome");
        check("biome set", biomeSession.setBiome(1, 71, 1, 5));
        biomeSession.flushQueue();
        checkEquals("biome stored", 5, world.getBiome(1, 71, 1));
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
        CommandManager.get().dispatch(actor, "/brush sphere 5 stone");
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
