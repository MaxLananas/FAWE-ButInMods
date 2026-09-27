package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Schematics kept in folders, as FAWE keeps them: {@code //schem save
 * trees/oak} makes the folder, {@code //schem list trees/} lists it, and
 * {@code //schem load trees/oak} reads it - without a name ever leading out of
 * the schematic folder.
 *
 * <p>A name with a slash in it was refused, so a schematic folder of hundreds
 * of files was one flat list. {@code //schem delete} of a name that was not
 * there answered that it was deleted.</p>
 */
final class SchematicFolderTests {

    private SchematicFolderTests() {
    }

    static void run() throws Exception {
        section("schematic folders");
        Path previous = Schematics.directory();
        Path dir = Files.createTempDirectory("fawebim-schematic-folders");
        Schematics.setDirectory(dir);
        try {
            savesAndLoadsThroughFolders();
            listsAFolderAtATime();
            neverLeavesTheSchematicFolder();
            deleteSaysWhenThereIsNothingToDelete();
            loadAllLooksInAFolder();
            completesTheNamesOfAFolder();
        } finally {
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

    private static void savesAndLoadsThroughFolders() {
        TestActor actor = builder("FolderSave");
        String saved = answer(actor, "//schem save trees/oak");
        check("//schem save trees/oak saves into a folder (" + saved + ")",
                saved.contains("Saved schematic 'trees/oak.schem'"));
        check("which it makes", Files.isRegularFile(Schematics.directory().resolve("trees").resolve("oak.schem")));
        answer(actor, "//schem save trees/big/birch");
        check("as deep as the name goes",
                Files.isRegularFile(Schematics.directory().resolve("trees/big/birch.schem")));
        actor.session().setClipboard(null);
        String loaded = answer(actor, "//schem load trees/oak");
        check("//schem load trees/oak reads it back (" + loaded + ")",
                loaded.contains("Loaded schematic 'trees/oak'") && actor.session().hasClipboard());
        String taken = answer(actor, "//schem save trees/oak");
        check("a name taken in a folder is refused without -f (" + taken + ")", taken.contains("already exists"));
    }

    private static void listsAFolderAtATime() {
        TestActor actor = builder("FolderList");
        answer(actor, "//schem save house");
        answer(actor, "//schem save trees/oak");
        answer(actor, "//schem save trees/big/birch");
        String top = answer(actor, "//schem list");
        check("//schem list shows the folders before the schematics (" + top + ")",
                top.indexOf("trees/: folder") >= 0 && top.indexOf("trees/: folder") < top.indexOf("house.schem"));
        check("and not what is inside them", !top.contains("oak"));
        String trees = answer(actor, "//schem list trees/");
        check("a folder ending in a slash is listed (" + trees + ")",
                trees.contains("Schematics in trees/") && trees.contains("trees/big/: folder")
                        && trees.contains("trees/oak.schem"));
        checkEquals("each name given as it loads", List.of("trees/big/", "trees/oak.schem"), Schematics.entries("trees"));
        String missing = answer(actor, "//schem list nothing/");
        check("a folder that is not there is said to be missing (" + missing + ")",
                missing.contains("No folder named 'nothing'"));
        String word = answer(actor, "//schem list trees/ o");
        check("a word keeps the names of the folder it starts (" + word + ")",
                word.contains("trees/oak.schem") && !word.contains("big"));
    }

    private static void neverLeavesTheSchematicFolder() {
        TestActor actor = builder("FolderEscape");
        for (String name : List.of("../escape", "trees/../../escape", "/absolute", "trees//oak", "trees/",
                "c:evil", "trees/./oak")) {
            String answer = answer(actor, "//schem save " + name);
            check("//schem save " + name + " is refused (" + answer + ")", answer.contains("Invalid schematic name"));
        }
        check("and nothing was written outside", !Files.exists(Schematics.directory().resolveSibling("escape.schem")));
        // The command line reads a backslash as an escape; a name from
        // elsewhere may still carry one, which Windows reads as a folder.
        boolean refused;
        try {
            Schematics.exists("..\\escape", "sponge.3");
            refused = false;
        } catch (RuntimeException e) {
            refused = true;
        }
        check("a backslash in a name is refused", refused);
        check("//schem load ../x is refused", answer(actor, "//schem load ../x").contains("Invalid schematic name"));
        check("//schem list ../ is refused", answer(actor, "//schem list ../").contains("Invalid schematic name"));
    }

    private static void deleteSaysWhenThereIsNothingToDelete() {
        TestActor actor = builder("FolderDelete");
        answer(actor, "//schem save trees/pine");
        String nothing = answer(actor, "//schem delete nothing");
        check("deleting a schematic that is not there says so (" + nothing + ")",
                nothing.contains("No schematic named 'nothing'"));
        String folder = answer(actor, "//schem delete trees");
        check("a folder is not a schematic (" + folder + ")", folder.contains("is a folder"));
        check("and is left alone", Files.isDirectory(Schematics.directory().resolve("trees")));
        String pine = answer(actor, "//schem delete trees/pine");
        check("a schematic in a folder is deleted (" + pine + ")",
                pine.contains("Deleted schematic 'trees/pine'")
                        && !Files.exists(Schematics.directory().resolve("trees/pine.schem")));
    }

    private static void loadAllLooksInAFolder() {
        TestActor actor = builder("FolderLoadAll");
        answer(actor, "//schem save pool/a");
        answer(actor, "//schem save pool/b");
        answer(actor, "//schem save c");
        String loaded = answer(actor, "//schem loadall -o pool/*");
        check("//schem loadall pool/* takes the schematics of the folder (" + loaded + ")",
                loaded.contains("Loaded 2 clipboards"));
        check("a wildcard before the last part is refused",
                answer(actor, "//schem loadall -o po*/a").contains("Only the last part"));
    }

    private static void completesTheNamesOfAFolder() {
        TestActor actor = builder("FolderComplete");
        answer(actor, "//schem save tower");
        answer(actor, "//schem save trees/oak");
        java.util.function.Function<String, List<String>> complete =
                CommandManager.get().registry().get("//schem").suggestions;
        checkEquals("//schem load t completes the schematics and folders it starts",
                List.of("trees/", "tower.schem"), complete.apply("load t"));
        checkEquals("inside a folder", List.of("trees/oak.schem"), complete.apply("load trees/o"));
        checkEquals("//schem list completes folders only", List.of("trees/"), complete.apply("list t"));
        checkEquals("and nothing after the name", List.of(), complete.apply("load tower.schem sponge"));
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }
}
