package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.clipboard.SchematicFormat;
import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The sub-commands of {@code //schem} against FAWE's: the format word of a
 * save is one of the names FAWE looks a format up by, and a save without one
 * writes the format {@code saving.format} names.
 *
 * <p>The format word was read loosely - anything that did not start with
 * {@code mcedit} or {@code structure} was written as Sponge v3, typos and
 * FAWE's {@code mce} and {@code schematic} included - and {@code //schem save}
 * without a format wrote sponge.3 whatever {@code saving.format} said.</p>
 */
final class SchematicCommandTests {

    private SchematicCommandTests() {
    }

    static void run() throws Exception {
        section("schematic sub-commands");
        Path previous = Schematics.directory();
        Path dir = Files.createTempDirectory("fawebim-schematic-commands");
        Schematics.setDirectory(dir);
        String format = Config.get().defaultSchematicFormat;
        try {
            saveWritesTheConfiguredFormat();
            formatNamesAreFawes();
            theFormatSettingTakesOnlyFormats();
        } finally {
            Config.get().defaultSchematicFormat = format;
            Schematics.setDirectory(previous);
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static TestActor builder(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(63);
        TestActor actor = new TestActor(name, world, new BlockVector3(0, 64, 0));
        answer(actor, "//pos1 0,60,0");
        answer(actor, "//pos2 3,63,3");
        answer(actor, "//copy");
        return actor;
    }

    private static void saveWritesTheConfiguredFormat() {
        TestActor actor = builder("SchemConfigured");
        Config.get().defaultSchematicFormat = "mcedit";
        String saved = answer(actor, "//schem save configured");
        check("//schem save without a format writes the one saving.format names (" + saved + ")",
                saved.contains("Saved schematic 'configured.schematic'")
                        && Files.isRegularFile(Schematics.directory().resolve("configured.schematic")));
        Config.get().defaultSchematicFormat = "sponge.3";
        String given = answer(actor, "//schem save given structure");
        check("a format given on the line still wins (" + given + ")",
                Files.isRegularFile(Schematics.directory().resolve("given.nbt")));
    }

    private static void formatNamesAreFawes() throws IOException {
        TestActor actor = builder("SchemNames");
        String typo = answer(actor, "//schem save typo strucutre");
        check("a format that is none is refused with the list (" + typo + ")",
                typo.contains("Unknown schematic format: strucutre")
                        && typo.contains("sponge.3, sponge.2, sponge.1, mcedit, structure"));
        check("and nothing is written", !Files.exists(Schematics.directory().resolve("typo.schem")));
        check("png, which the mod cannot write, is refused",
                answer(actor, "//schem save picture png").contains("Unknown schematic format: png"));

        answer(actor, "//schem save legacy mce");
        check("FAWE's mce is MCEdit", Files.isRegularFile(Schematics.directory().resolve("legacy.schematic")));
        answer(actor, "//schem save old schematic");
        check("and so is schematic", Files.isRegularFile(Schematics.directory().resolve("old.schematic")));
        answer(actor, "//schem save quick fast");
        checkEquals("FAWE's fast is Sponge v3", 3, spongeVersion(Schematics.directory().resolve("quick.schem")));
        answer(actor, "//schem save older fast.2");
        checkEquals("and fast.2 is Sponge v2", 2, spongeVersion(Schematics.directory().resolve("older.schem")));

        String formats = answer(actor, "//schem formats");
        check("//schem formats lists each format with the names it is looked up by (" + formats + ")",
                formats.contains("Schematic formats (5, default sponge.3)")
                        && formats.contains("mcedit: .schematic - also mce, schematic, legacy")
                        && formats.contains("sponge.3: .schem - also fast, fawe, schem"));
        checkEquals("a format is found by any of its names, ignoring case", SchematicFormat.SPONGE_2,
                SchematicFormat.find("FAWE.2"));
    }

    private static void theFormatSettingTakesOnlyFormats() throws IOException {
        TestActor actor = builder("SchemSetting");
        String refused = answer(actor, "/fawebim set schematic-format strucutre");
        check("the format setting refuses a word that is no format (" + refused + ")",
                refused.contains("Expected one of sponge.3, sponge.2, sponge.1, mcedit, structure"));
        checkEquals("and keeps its value", "sponge.3", Config.get().defaultSchematicFormat);
        answer(actor, "/fawebim set schematic-format FAST");
        checkEquals("FAWE's names are stored as the format's own", "sponge.3", Config.get().defaultSchematicFormat);
        answer(actor, "/fawebim set schematic-format mce");
        checkEquals("mce is mcedit", "mcedit", Config.get().defaultSchematicFormat);
        Config.get().defaultSchematicFormat = "sponge.3";

        // A typo in the file keeps the value it had, and says so where it
        // used to be dropped in silence.
        Path game = Files.createTempDirectory("fawebim-config-format");
        Config.get().load(game);
        Path file = game.resolve("config").resolve("fawebim.yml");
        String written = Files.readString(file);
        check("the file holds the format", written.contains("format: sponge.3"));
        Files.writeString(file, written.replace("format: sponge.3", "format: strucutre"));
        int warnings = SelfTestMain.loggedWarnings.size();
        Config.get().reload();
        checkEquals("a format in the file that is none is not taken", "sponge.3",
                Config.get().defaultSchematicFormat);
        check("and is reported", SelfTestMain.loggedWarnings.subList(warnings, SelfTestMain.loggedWarnings.size())
                .stream().anyMatch(line -> line.contains("Ignored saving.format: 'strucutre'")
                        && line.contains("it stays sponge.3")));
    }

    /** The Version of a Sponge file: at the root in v1 and v2, under Schematic in v3. */
    private static int spongeVersion(Path file) throws IOException {
        byte[] inflated;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            inflated = in.readAllBytes();
        }
        NbtCompound root = NbtIo.read(inflated);
        NbtCompound body = root.getCompoundOrNull("Schematic");
        return (body != null ? body : root).getInt("Version", -1);
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }
}
