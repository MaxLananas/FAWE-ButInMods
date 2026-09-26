package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.util.InputException;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.NbtIo;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.EntityData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Schematic files are what other programs read and write: WorldEdit, FAWE and
 * the game's structure blocks. The files written here are checked against the
 * formats' layout, files built the way WorldEdit writes them are read back, and
 * so are the layouts earlier builds of this mod wrote.
 */
final class SchematicFormatTests {

    private static final List<String> FORMATS = List.of("sponge.3", "sponge.2", "sponge.1", "mcedit", "structure");

    private SchematicFormatTests() {
    }

    static void run() throws Exception {
        section("schematic formats");
        Path previous = Schematics.directory();
        Path dir = Files.createTempDirectory("fawebim-schematic-formats");
        Schematics.setDirectory(dir);
        try {
            savesFromTheBoxNotTheOrigin();
            writesTheFormatsLayout();
            readsWhatWorldEditWrites();
            readsVarIntsOfSeveralBytes();
            readsTheLayoutsEarlierBuildsWrote();
            blockEntitiesTravelWithoutChangingTheClipboard();
            refusesBrokenAndHostileFiles();
            nbtIsStrictAndBounded();
            sidesAreLimited();
            saveOverwritesWithTheForceSwitch();
        } finally {
            Schematics.setDirectory(previous);
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static int state(String name) {
        return BlockState.registry().parse(name);
    }

    /** A 4x4x4 clipboard of three blocks, its origin inside it the way {@code //copy -c} leaves it. */
    private static BlockArrayClipboard offsetClipboard() {
        int stone = state("minecraft:stone");
        int dirt = state("minecraft:dirt");
        int cobble = state("minecraft:cobblestone");
        BlockArrayClipboard clipboard = new BlockArrayClipboard(new BlockVector3(10, 64, 20));
        for (int y = 64; y < 68; y++) {
            for (int z = 20; z < 24; z++) {
                for (int x = 10; x < 14; x++) {
                    int pick = (x * 7 + y * 3 + z) % 4;
                    clipboard.setBlock(x, y, z, pick == 0 ? stone : pick == 1 ? dirt : pick == 2 ? cobble
                            : BlockState.registry().air());
                }
            }
        }
        clipboard.setOrigin(new BlockVector3(12, 66, 22));
        return clipboard;
    }

    private static String fileOf(Path dir, String prefix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith(prefix + "."))
                    .findFirst().orElseThrow();
        }
    }

    /**
     * Every writer counted the blocks from the clipboard's origin instead of its
     * minimum corner, so a clipboard copied with {@code -c} lost every block
     * below and behind its centre.
     */
    private static void savesFromTheBoxNotTheOrigin() throws IOException {
        BlockArrayClipboard clipboard = offsetClipboard();
        BlockVector3 min = clipboard.getBox().min();
        for (String format : FORMATS) {
            String name = "offset-" + format.replace('.', '_');
            Schematics.save(clipboard, name, format);
            BlockArrayClipboard loaded = Schematics.load(fileOf(Schematics.directory(), name));
            BlockVector3 loadedMin = loaded.getBox().min();
            int wrong = 0;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        int expected = clipboard.getBlock(min.x() + x, min.y() + y, min.z() + z);
                        int actual = loaded.getBlock(loadedMin.x() + x, loadedMin.y() + y, loadedMin.z() + z);
                        if (expected != actual) {
                            wrong++;
                        }
                    }
                }
            }
            checkEquals(format + " keeps every block of a clipboard whose origin is not its corner", 0, wrong);
            checkEquals(format + " keeps the size", clipboard.getBox().volume(), loaded.getBox().volume());
            if (!format.equals("structure")) {
                checkEquals(format + " keeps where the origin is",
                        clipboard.getOrigin().subtract(min), loaded.getOrigin().subtract(loadedMin));
            }
        }
    }

    /** The root name of a gzipped NBT file, and the document itself. */
    private record Document(String rootName, NbtCompound root) {
    }

    private static Document document(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        check(file.getFileName() + " is gzipped", NbtIo.isGzip(bytes));
        byte[] inflated;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            inflated = in.readAllBytes();
        }
        int length = ((inflated[1] & 0xFF) << 8) | (inflated[2] & 0xFF);
        String rootName = new String(inflated, 3, length, StandardCharsets.UTF_8);
        return new Document(rootName, NbtIo.read(inflated));
    }

    private static void writesTheFormatsLayout() throws IOException {
        BlockArrayClipboard clipboard = offsetClipboard();
        clipboard.addBlockEntity(new BlockVector3(11, 65, 21), new NbtCompound().putString("id", "minecraft:chest"));
        Path dir = Schematics.directory();

        Schematics.save(clipboard, "layout-v3", "sponge.3");
        Document v3 = document(dir.resolve("layout-v3.schem"));
        checkEquals("a v3 root has no name", "", v3.rootName());
        NbtCompound body = v3.root().getCompoundOrNull("Schematic");
        check("a v3 file keeps everything under Schematic", body != null && v3.root().size() == 1);
        checkEquals("v3 Version", 3, body.get("Version"));
        checkEquals("v3 DataVersion is the game's", Config.DATA_VERSION, body.get("DataVersion"));
        check("v3 sides are shorts", body.get("Width") instanceof Short && body.get("Height") instanceof Short
                && body.get("Length") instanceof Short);
        check("v3 Offset is an int array of the minimum corner",
                java.util.Arrays.equals(new int[]{10, 64, 20}, (int[]) body.get("Offset")));
        NbtCompound blocks = body.getCompoundOrNull("Blocks");
        check("v3 blocks are a compound of Palette, Data and BlockEntities", blocks != null
                && blocks.get("Palette") instanceof NbtCompound && blocks.get("Data") instanceof byte[]
                && blocks.get("BlockEntities") instanceof List<?>);
        checkEquals("v3 data holds one varint per block", 64, blocks.getByteArray("Data").length);
        NbtCompound blockEntity = blocks.getCompoundList("BlockEntities").get(0);
        check("a v3 block entity has an int array Pos relative to the corner, an Id and its Data",
                java.util.Arrays.equals(new int[]{1, 1, 1}, (int[]) blockEntity.get("Pos"))
                        && "minecraft:chest".equals(blockEntity.getString("Id", null))
                        && blockEntity.get("Data") instanceof NbtCompound);
        NbtCompound metadata = body.getCompoundOrNull("Metadata");
        check("v3 metadata carries WorldEdit's offset from the origin to the corner", metadata != null
                && metadata.getInt("WEOffsetX", 0) == -2 && metadata.getInt("WEOffsetY", 0) == -2
                && metadata.getInt("WEOffsetZ", 0) == -2);

        Schematics.save(clipboard, "layout-v2", "sponge.2");
        Document v2 = document(dir.resolve("layout-v2.schem"));
        checkEquals("a v2 root is named Schematic", "Schematic", v2.rootName());
        checkEquals("v2 Version", 2, v2.root().get("Version"));
        check("v2 block data is varints", v2.root().getByteArray("BlockData").length == 64
                && v2.root().get("Palette") instanceof NbtCompound
                && v2.root().getInt("PaletteMax", 0) == v2.root().getCompoundOrNull("Palette").size());
        check("v2 block entities are a list", v2.root().get("BlockEntities") instanceof List<?>);

        Schematics.save(clipboard, "layout-mcedit", "mcedit");
        Document mcedit = document(dir.resolve("layout-mcedit.schematic"));
        checkEquals("an MCEdit root is named Schematic", "Schematic", mcedit.rootName());
        check("MCEdit sides are shorts and block entities a list", mcedit.root().get("Width") instanceof Short
                && mcedit.root().get("TileEntities") instanceof List<?>
                && mcedit.root().getInt("WEOriginX", 0) == 10);

        Schematics.save(clipboard, "layout-structure", "structure");
        Document structure = document(dir.resolve("layout-structure.nbt"));
        checkEquals("a structure root has no name", "", structure.rootName());
        check("a structure size is a list of ints, as the game reads it",
                List.of(4, 4, 4).equals(structure.root().get("size")));
        check("a structure block pos is a list of ints",
                structure.root().getCompoundList("blocks").get(0).get("pos") instanceof List<?>);
        checkEquals("a structure declares the game's data version", Config.DATA_VERSION,
                structure.root().get("DataVersion"));
        checkEquals("a structure lists air too, so placing it clears what it covers", 64,
                structure.root().getCompoundList("blocks").size());
    }

    private static byte[] varints(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : values) {
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }
        return out.toByteArray();
    }

    private static void writeFile(String name, NbtCompound root, String rootName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.write(root, rootName, out, true);
        Files.write(Schematics.directory().resolve(name), out.toByteArray());
    }

    /** A 3x2x2 v3 file with a chest and an armor stand, laid out the way WorldEdit 7.3 writes it. */
    private static NbtCompound worldEditV3() {
        NbtCompound palette = new NbtCompound().putInt("minecraft:air", 0).putInt("minecraft:stone", 1)
                .putInt("minecraft:chest", 2);
        int[] indices = new int[12];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i % 2;
        }
        // (x=2, y=1, z=1) is index (1 * 2 + 1) * 3 + 2 = 11.
        indices[11] = 2;
        NbtCompound item = new NbtCompound().putByte("Slot", 0).putString("id", "minecraft:stone").putInt("count", 3);
        NbtCompound chest = new NbtCompound().putIntArray("Pos", new int[]{2, 1, 1}).putString("Id", "minecraft:chest")
                .putCompound("Data", new NbtCompound().putList("Items", List.of(item)).putString("CustomName", "\"Loot\""));
        NbtCompound blocks = new NbtCompound().putCompound("Palette", palette).putByteArray("Data", varints(indices))
                .putList("BlockEntities", List.of(chest));
        NbtCompound stand = new NbtCompound().putList("Pos", List.of(0.5, 1.0, 0.5))
                .putString("Id", "minecraft:armor_stand").putCompound("Data", new NbtCompound().putByte("Invisible", 1));
        NbtCompound body = new NbtCompound().putInt("Version", 3).putInt("DataVersion", 3953)
                .putCompound("Metadata", new NbtCompound().putInt("WEOffsetX", -1).putInt("WEOffsetY", 0)
                        .putInt("WEOffsetZ", -2))
                .putShort("Width", 3).putShort("Height", 2).putShort("Length", 2)
                .putIntArray("Offset", new int[]{100, 64, 200})
                .putCompound("Blocks", blocks).putList("Entities", List.of(stand));
        return new NbtCompound().putCompound("Schematic", body);
    }

    /** A v3 file of WorldEdit ran through the reader of a v1 file and failed on an index out of bounds. */
    private static void readsWhatWorldEditWrites() throws IOException {
        writeFile("we-v3.schem", worldEditV3(), "");
        BlockArrayClipboard loaded = Schematics.load("we-v3.schem");
        checkEquals("WorldEdit's v3 file: size", 12L, loaded.getBox().volume());
        check("WorldEdit's v3 file: blocks in index order", loaded.getBlock(1, 0, 0) == state("minecraft:stone")
                && loaded.getBlock(0, 0, 0) == BlockState.registry().air()
                && loaded.getBlock(2, 1, 1) == state("minecraft:chest"));
        NbtCompound chest = loaded.getBlockEntity(new BlockVector3(2, 1, 1));
        check("WorldEdit's v3 file: the chest keeps its data and type", chest != null
                && "minecraft:chest".equals(chest.getString("id", null))
                && chest.getCompoundList("Items").size() == 1 && !chest.contains("Pos"));
        checkEquals("WorldEdit's v3 file: the origin is the corner minus WEOffset", new BlockVector3(1, 0, 2),
                loaded.getOrigin());
        List<EntityData> entities = loaded.entities();
        check("WorldEdit's v3 file: the armor stand comes along", entities.size() == 1
                && entities.get(0).type().equals("minecraft:armor_stand")
                && entities.get(0).position().equals(new Vector3(0.5, 1.0, 0.5))
                && entities.get(0).nbt().getByte("Invisible", 0) == 1);

        NbtCompound flatChest = new NbtCompound().putIntArray("Pos", new int[]{0, 0, 0}).putString("Id", "minecraft:chest")
                .putString("CustomName", "\"Flat\"");
        NbtCompound v2 = new NbtCompound().putInt("Version", 2).putInt("DataVersion", 2975)
                .putShort("Width", 2).putShort("Height", 1).putShort("Length", 1)
                .putIntArray("Offset", new int[]{0, 0, 0}).putInt("PaletteMax", 2)
                .putCompound("Palette", new NbtCompound().putInt("minecraft:chest", 0).putInt("minecraft:stone", 1))
                .putByteArray("BlockData", varints(0, 1)).putList("BlockEntities", List.of(flatChest));
        writeFile("we-v2.schem", v2, "Schematic");
        BlockArrayClipboard loadedV2 = Schematics.load("we-v2.schem");
        NbtCompound flat = loadedV2.getBlockEntity(new BlockVector3(0, 0, 0));
        check("WorldEdit's v2 file: blocks and the flattened block entity", loadedV2.getBlock(1, 0, 0)
                == state("minecraft:stone") && flat != null && "\"Flat\"".equals(flat.getString("CustomName", null))
                && "minecraft:chest".equals(flat.getString("id", null)) && !flat.contains("Id"));
        checkEquals("a file without WorldEdit's metadata pastes from its corner", BlockVector3.ZERO,
                loadedV2.getOrigin());
    }

    /**
     * A palette index of 128 or more takes two varint bytes; eight such blocks
     * are sixteen bytes, as long as eight shorts, and still read as varints.
     */
    private static void readsVarIntsOfSeveralBytes() throws IOException {
        int[] indices = {300, 0, 300, 0, 300, 300, 0, 300};
        NbtCompound v2 = new NbtCompound().putInt("Version", 2)
                .putShort("Width", 2).putShort("Height", 2).putShort("Length", 2)
                .putCompound("Palette", new NbtCompound().putInt("minecraft:air", 0).putInt("minecraft:stone", 300))
                .putByteArray("BlockData", varints(indices));
        writeFile("wide-palette.schem", v2, "Schematic");
        BlockArrayClipboard loaded = Schematics.load("wide-palette.schem");
        int stone = state("minecraft:stone");
        check("two-byte varints decode", loaded.getBlock(0, 0, 0) == stone && loaded.getBlock(1, 0, 0) != stone
                && loaded.getBlock(1, 1, 1) == stone);
    }

    private static void readsTheLayoutsEarlierBuildsWrote() throws IOException {
        int stone = state("minecraft:stone");
        NbtCompound oldChest = new NbtCompound().putString("id", "minecraft:chest")
                .putCompound("Pos", new NbtCompound().putInt("x", 0).putInt("y", 0).putInt("z", 0));
        NbtCompound oldPig = new NbtCompound().putString("Id", "minecraft:pig")
                .putCompound("Pos", new NbtCompound().putDouble("x", 0.5).putDouble("y", 0).putDouble("z", 0.5));
        NbtCompound inner = new NbtCompound().putInt("Version", 3)
                .putCompound("Palette", new NbtCompound().putInt("minecraft:air", 0).putInt("minecraft:stone", 1))
                .putByteArray("Data", varints(1, 0))
                .putCompound("BlockEntities", new NbtCompound().putCompound("be0", oldChest))
                .putCompound("Entities", new NbtCompound().putCompound("e0", oldPig));
        NbtCompound hybrid = new NbtCompound().putInt("Version", 3).putInt("DataVersion", 4189)
                .putInt("Width", 2).putInt("Height", 1).putInt("Length", 1)
                .putCompound("Offset", new NbtCompound().putInt("x", 5).putInt("y", 6).putInt("z", 7))
                .putCompound("Schematic", inner);
        Files.write(Schematics.directory().resolve("old-v3.schem"), NbtIo.write(hybrid, true, true));
        BlockArrayClipboard loaded = Schematics.load("old-v3.schem");
        check("an earlier build's v3 file still loads", loaded.getBlock(0, 0, 0) == stone
                && loaded.getBlock(1, 0, 0) == BlockState.registry().air());
        check("its block entity, whose Pos was a compound, loads instead of crashing",
                loaded.getBlockEntity(BlockVector3.ZERO) != null);
        checkEquals("its entity loads", 1, loaded.entities().size());

        NbtCompound shorts = new NbtCompound().putInt("Version", 2).putInt("Width", 2).putInt("Height", 1)
                .putInt("Length", 1)
                .putCompound("Palette", new NbtCompound().putInt("minecraft:air", 0).putInt("minecraft:stone", 1))
                .putByteArray("BlockData", new byte[]{0, 1, 0, 0});
        writeFile("old-v2.schem", shorts, "");
        BlockArrayClipboard loadedShorts = Schematics.load("old-v2.schem");
        check("an earlier build's v2 file of shorts still loads", loadedShorts.getBlock(0, 0, 0) == stone
                && loadedShorts.getBlock(1, 0, 0) == BlockState.registry().air());
    }

    /**
     * Saving put a {@code Pos} compound into the clipboard's own block entity
     * data (while an asynchronous save could be reading it), and loading read
     * that compound as a list and failed on an index out of bounds.
     */
    private static void blockEntitiesTravelWithoutChangingTheClipboard() throws IOException {
        BlockArrayClipboard clipboard = offsetClipboard();
        BlockVector3 at = new BlockVector3(11, 65, 21);
        clipboard.setBlock(at.x(), at.y(), at.z(), state("minecraft:chest"));
        NbtCompound item = new NbtCompound().putByte("Slot", 0).putString("id", "minecraft:stone").putInt("count", 1);
        NbtCompound chest = new NbtCompound().putString("id", "minecraft:chest").putList("Items", List.of(item))
                .putInt("x", 11).putInt("y", 65).putInt("z", 21);
        clipboard.addBlockEntity(at, chest);
        String before = chest.toString();
        for (String format : FORMATS) {
            String name = "chest-" + format.replace('.', '_');
            Schematics.save(clipboard, name, format);
            checkEquals(format + " leaves the clipboard's block entity as it was", before,
                    clipboard.getBlockEntity(at).toString());
            BlockArrayClipboard loaded = Schematics.load(fileOf(Schematics.directory(), name));
            BlockVector3 relative = at.subtract(clipboard.getBox().min()).add(loaded.getBox().min());
            NbtCompound back = loaded.getBlockEntity(relative);
            check(format + " brings the chest back where it was, with its items and type", back != null
                    && "minecraft:chest".equals(back.getString("id", null))
                    && back.getCompoundList("Items").size() == 1 && !back.contains("x") && !back.contains("Pos"));
        }
    }

    private static String failure(String name) {
        try {
            Schematics.load(name);
            return "loaded";
        } catch (InputException e) {
            return e.getMessage();
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
            return "crashed: " + e;
        }
    }

    private static void refusesBrokenAndHostileFiles() throws IOException {
        NbtCompound huge = worldEditV3();
        huge.getCompoundOrNull("Schematic").putShort("Width", 0xFFFF).putShort("Height", 0xFFFF)
                .putShort("Length", 0xFFFF);
        writeFile("huge.schem", huge, "");
        String hugeAnswer = failure("huge.schem");
        check("a file that claims 65535^3 blocks with a few bytes of data is refused before anything is allocated ("
                + hugeAnswer + ")", hugeAnswer.contains("does not match"));

        int limit = Config.get().maxSchematicSize;
        Config.get().maxSchematicSize = 10;
        try {
            writeFile("limited.schem", worldEditV3(), "");
            String limited = failure("limited.schem");
            check("limits.max-schematic-size refuses a larger schematic (" + limited + ")",
                    limited.contains("max-schematic-size"));
        } finally {
            Config.get().maxSchematicSize = limit;
        }

        NbtCompound missing = worldEditV3();
        missing.getCompoundOrNull("Schematic").getCompoundOrNull("Blocks").putByteArray("Data",
                varints(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5));
        writeFile("missing.schem", missing, "");
        String missingAnswer = failure("missing.schem");
        check("a block that refers to a palette entry that does not exist is refused (" + missingAnswer + ")",
                missingAnswer.contains("palette entry 5"));

        NbtCompound overflow = worldEditV3();
        NbtCompound overflowBody = overflow.getCompoundOrNull("Schematic");
        overflowBody.putShort("Width", 1).putShort("Height", 1).putShort("Length", 1);
        overflowBody.getCompoundOrNull("Blocks").putByteArray("Data",
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F});
        writeFile("overflow.schem", overflow, "");
        String overflowAnswer = failure("overflow.schem");
        check("a varint that overflows an int is refused, not an index out of bounds (" + overflowAnswer + ")",
                overflowAnswer.contains("palette entry"));

        NbtCompound sparse = worldEditV3();
        sparse.getCompoundOrNull("Schematic").getCompoundOrNull("Blocks").getCompoundOrNull("Palette")
                .putInt("minecraft:dirt", 1 << 30);
        writeFile("sparse.schem", sparse, "");
        String sparseAnswer = failure("sparse.schem");
        check("a palette index of a billion is refused instead of sizing a table for it (" + sparseAnswer + ")",
                sparseAnswer.contains("has index"));

        Files.write(Schematics.directory().resolve("noise.schem"), "not a schematic at all".getBytes());
        String noise = failure("noise.schem");
        check("a file that is not NBT says so (" + noise + ")", noise.contains("not a readable schematic"));
    }

    private static String readFailure(byte[] data, long budget) {
        try {
            NbtIo.read(new ByteArrayInputStream(data), false, false, budget);
            return "read";
        } catch (IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
            return "crashed: " + e;
        }
    }

    private static void nbtIsStrictAndBounded() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(NbtIo.TAG_COMPOUND);
        out.writeUTF("");
        out.writeByte(NbtIo.TAG_BYTE_ARRAY);
        out.writeUTF("a");
        out.writeInt(0x7FFFFFF0);
        out.write(new byte[16]);
        String claimed = readFailure(bytes.toByteArray(), NbtIo.defaultBudget());
        check("a byte array that claims 2 GiB is refused without allocating it (" + claimed + ")",
                claimed.startsWith("TooLargeException"));

        bytes.reset();
        out.writeByte(NbtIo.TAG_COMPOUND);
        out.writeUTF("");
        out.writeByte(NbtIo.TAG_LIST);
        out.writeUTF("l");
        out.writeByte(NbtIo.TAG_COMPOUND);
        out.writeInt(Integer.MAX_VALUE);
        String list = readFailure(bytes.toByteArray(), NbtIo.defaultBudget());
        check("a list that claims two billion compounds is refused up front (" + list + ")",
                list.startsWith("TooLargeException"));

        bytes.reset();
        out.writeByte(NbtIo.TAG_COMPOUND);
        out.writeUTF("");
        out.writeByte(NbtIo.TAG_INT_ARRAY);
        out.writeUTF("n");
        out.writeInt(-4);
        String negative = readFailure(bytes.toByteArray(), NbtIo.defaultBudget());
        check("a negative length is refused (" + negative + ")", negative.contains("Negative"));

        bytes.reset();
        out.writeByte(NbtIo.TAG_COMPOUND);
        out.writeUTF("");
        out.writeByte(NbtIo.TAG_LIST);
        out.writeUTF("deep");
        for (int i = 0; i < 20_000; i++) {
            out.writeByte(NbtIo.TAG_LIST);
            out.writeInt(1);
        }
        String deep = readFailure(bytes.toByteArray(), NbtIo.defaultBudget());
        check("20000 nested lists stop at the depth limit instead of the stack (" + deep + ")",
                deep.contains("nested deeper"));

        NbtCompound many = new NbtCompound();
        List<NbtCompound> entries = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            entries.add(new NbtCompound().putInt("i", i));
        }
        many.putList("entries", entries);
        String budget = readFailure(NbtIo.write(many, false), 4096);
        check("a document larger than the reader's budget is refused (" + budget + ")",
                budget.startsWith("TooLargeException"));

        String text = "a\u0000b\uD83D\uDE00";
        byte[] encoded = NbtIo.write(new NbtCompound().putString("s", text), false);
        checkEquals("strings survive in modified UTF-8", text, NbtIo.read(encoded).getString("s", null));
        boolean nulAsTwoBytes = false;
        boolean fourByteSequence = false;
        for (int i = 0; i + 1 < encoded.length; i++) {
            nulAsTwoBytes |= (encoded[i] & 0xFF) == 0xC0 && (encoded[i + 1] & 0xFF) == 0x80;
            fourByteSequence |= (encoded[i] & 0xF8) == 0xF0;
        }
        check("the game's string encoding: NUL as C0 80, no four-byte sequences", nulAsTwoBytes && !fourByteSequence);

        boolean refused;
        try {
            NbtIo.write(new NbtCompound().putString("s", "x".repeat(70_000)), false);
            refused = false;
        } catch (java.io.UTFDataFormatException e) {
            refused = true;
        }
        check("a string longer than NBT allows is refused instead of wrapping its length", refused);
        try {
            NbtIo.write(new NbtCompound().putList("mixed", List.of(1, "two")), false);
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("a list mixing tag types is refused instead of being written as garbage", refused);
    }

    private static void sidesAreLimited() {
        BlockArrayClipboard wide = new BlockArrayClipboard(BlockVector3.ZERO);
        wide.setBlock(0, 0, 0, state("minecraft:stone"));
        wide.setBlock(65536, 0, 0, state("minecraft:stone"));
        for (String format : List.of("sponge.3", "mcedit")) {
            String answer;
            try {
                Schematics.save(wide, "wide-" + format.replace('.', '_'), format);
                answer = "saved";
            } catch (InputException e) {
                answer = e.getMessage();
            }
            check(format + " refuses a side longer than an unsigned short (" + answer + ")",
                    answer.contains("at most 65535"));
        }
    }

    /** {@code -f} is the value flag of {@code //schem list}; under {@code save} it is WorldEdit's overwrite switch. */
    private static void saveOverwritesWithTheForceSwitch() {
        TestWorld world = new TestWorld("SchemForce");
        world.fillFlat(70);
        TestActor actor = new TestActor("SchemForce", world, new BlockVector3(0, 71, 0));
        actor.session().setClipboard(offsetClipboard());
        CommandManager.get().dispatch(actor, "//schem save forced");
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//schem save forced");
        String again = String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
        check("saving over an existing schematic asks for -f (" + again + ")", again.contains("-f"));
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//schem save -f forced");
        String forced = String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
        check("//schem save -f overwrites it (" + forced + ")", forced.contains("Saved schematic 'forced.schem'"));
    }
}
