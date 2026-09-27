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
 *
 * <p>{@code //schem move <folder>} moves the files the clipboard was loaded
 * from, as FAWE's does; it took the folder for a schematic to convert.
 * {@code //schem unload <file>} takes one schematic out of the loaded ones;
 * it cleared the clipboard whatever the name. {@code //schem delete *}
 * deletes the loaded files; it looked for a file named *.</p>
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
            loadNamesTheFileItRead();
            moveTakesTheLoadedFilesIntoAFolder();
            twoWordsStillConvert();
            unloadTakesOneSchematicOut();
            deleteStarDeletesTheLoadedFiles();
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

    private static void loadNamesTheFileItRead() {
        TestActor actor = builder("SchemLoad");
        answer(actor, "//schem save lodge");
        String loaded = answer(actor, "//schem load lodge");
        check("//schem load names the file it read, its size and what comes next, as FAWE's (" + loaded + ")",
                loaded.contains("Loaded schematic 'lodge.schem' (4x4x4): paste it with //paste"));
    }

    private static void moveTakesTheLoadedFilesIntoAFolder() {
        TestActor actor = builder("SchemMove");
        String none = answer(actor, "//schem move trees");
        check("a copy has no file to move (" + none + ")", none.contains("No schematic file to move"));
        answer(actor, "//schem save oak");
        answer(actor, "//schem load oak");
        String moved = answer(actor, "//schem move trees");
        check("//schem move puts the loaded schematic in the folder, as FAWE's (" + moved + ")",
                moved.contains("Moved 'oak.schem' to 'trees/oak.schem'") && Files.isRegularFile(file("trees/oak.schem"))
                        && !Files.exists(file("oak.schem")));
        String again = answer(actor, "//schem move trees/");
        check("the clipboard follows its file (" + again + ")", again.contains("'trees/oak.schem' is already there"));
        String back = answer(actor, "//schem move .");
        check(". is the schematic folder itself (" + back + ")",
                back.contains("Moved 'trees/oak.schem' to 'oak.schem'") && Files.isRegularFile(file("oak.schem")));
        answer(actor, "//schem save trees/oak");
        String taken = answer(actor, "//schem move trees");
        check("a file of the same name in the folder is not overwritten (" + taken + ")",
                taken.contains("'trees/oak.schem' already exists") && Files.isRegularFile(file("oak.schem")));
        check("a folder out of the schematic folder is refused",
                answer(actor, "//schem move ../out").contains("Invalid schematic name"));
    }

    private static void twoWordsStillConvert() {
        TestActor actor = builder("SchemConvert");
        answer(actor, "//schem save house");
        String converted = answer(actor, "//schem move house mcedit");
        check("//schem move <name> <format> still rewrites a schematic in another format (" + converted + ")",
                converted.contains("Converted 'house' to mcedit: 'house.schematic'")
                        && Files.isRegularFile(file("house.schematic")) && !Files.exists(file("house.schem")));
        // -f, since the save takes house.schematic for the same schematic.
        answer(actor, "//schem save house -f");
        check("a second house is saved beside it", Files.isRegularFile(file("house.schem")));
        String refused = answer(actor, "//schem move house.schematic sponge.3");
        check("without overwriting another schematic under the new name (" + refused + ")",
                refused.contains("'house.schem' already exists") && Files.isRegularFile(file("house.schematic")));
        String typo = answer(actor, "//schem move house.schem strucutre");
        check("an unknown format leaves the schematic alone (" + typo + ")",
                typo.contains("Unknown schematic format") && Files.isRegularFile(file("house.schem")));
    }

    private static void unloadTakesOneSchematicOut() {
        TestActor actor = builder("SchemUnload");
        answer(actor, "//schem save pool/a");
        answer(actor, "//schem save pool/b");
        answer(actor, "//schem loadall -o pool/*");
        checkEquals("loadall holds both", 2, actor.session().getClipboardPool().size());
        String missing = answer(actor, "//schem unload pool/c");
        check("a schematic that is not loaded is said to be, as FAWE says it (" + missing + ")",
                missing.contains("You do not have 'pool/c' loaded") && actor.session().getClipboardPool().size() == 2);
        String one = answer(actor, "//schem unload pool/a.schem");
        check("//schem unload takes one schematic out of the loaded ones (" + one + ")",
                one.contains("Unloaded 'pool/a.schem': 1 clipboard left") && actor.session().hasClipboard());
        check("and the clipboard is the one left",
                actor.session().getClipboard().getClipboard().getSource().endsWith("b.schem"));
        String last = answer(actor, "//schem unload pool/b");
        check("unloading the last one empties the clipboard (" + last + ")",
                last.contains("your clipboard is empty") && !actor.session().hasClipboard());
        answer(actor, "//schem load pool/a");
        answer(actor, "//schem unload");
        check("//schem unload without a name still clears the clipboard", !actor.session().hasClipboard());
    }

    private static void deleteStarDeletesTheLoadedFiles() {
        TestActor actor = builder("SchemDeleteAll");
        answer(actor, "//schem save gone/a");
        answer(actor, "//schem save gone/b");
        answer(actor, "//schem save kept");
        answer(actor, "//schem loadall -o gone/*");
        String deleted = answer(actor, "//schem delete *");
        check("//schem delete * deletes the files the clipboard was loaded from, as FAWE's (" + deleted + ")",
                deleted.contains("Deleted schematic 'gone/a.schem'") && deleted.contains("Deleted schematic 'gone/b.schem'")
                        && !Files.exists(file("gone/a.schem")) && !Files.exists(file("gone/b.schem")));
        check("and only those", Files.isRegularFile(file("kept.schem")));
        check("a second time there is nothing left to delete",
                answer(actor, "//schem delete *").contains("No schematic file to delete"));
    }

    private static Path file(String name) {
        return Schematics.directory().resolve(name);
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
