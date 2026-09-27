package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.command.Suggestions;
import com.maxlananas.fawebim.core.world.World;

import java.util.List;
import java.util.function.Supplier;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Tab completion offers what the word being typed may be.
 *
 * <p>The word was matched with the argument at its index, flags counted, so the
 * pattern of {@code /brush set sphere } and the tree type of
 * {@code /brush forest sphere 5 } were never offered, and neither was the mask
 * after {@code //copy -m}. Every argument whose name contained "type" or
 * "region" offered nine brush shapes, five of which no brush takes - for the
 * tree type of {@code //forest}, the placement of {@code /placement}, the
 * entities of {@code /remove} - and the sub-commands of {@code /anvil} offered
 * the blocks, one of them being "replacepattern". Features and structures
 * offered nothing.</p>
 */
final class CompletionTests {

    private CompletionTests() {
    }

    static void run() {
        section("tab completion");
        TestWorld world = new TestWorld("completion");
        Supplier<World> asked = () -> world;
        theWordIsMatchedWithTheArgumentsItMayFill(asked);
        aTypeIsWhatItsCommandTakes(asked);
        flagsAndTheirValuesComplete(asked);
        theWordsOfAnAlternativeAreOffered(asked);
    }

    private static List<String> complete(String command, String remaining, Supplier<World> world) {
        CommandRegistry.Entry entry = CommandManager.get().registry().get(command);
        check("the command " + command + " is registered", entry != null);
        return entry == null ? List.of() : Suggestions.forLine(entry, remaining, world);
    }

    private static void theWordIsMatchedWithTheArgumentsItMayFill(Supplier<World> world) {
        check("a pattern after the optional radius of /brush set",
                complete("/brush set", "sphere sto", world).contains("stone"));
        check("the tree types after the shape of /brush forest",
                complete("/brush forest", "sphere bir", world).contains("birch"));
        check("and after its radius and density",
                complete("/brush forest", "sphere 5 50 bir", world).contains("birch"));
        List<String> shapes = complete("/brush forest", "", world);
        checkEquals("the shapes a brush takes, and only those",
                List.of("sphere", "cyl", "cuboid", "cube", "fixedsphere", "fixedcyl"), shapes);
        check("the size of //forestgen is not a tree type",
                complete("//forestgen", "", world).isEmpty());
        check("its type is", complete("//forestgen", "10 ", world).contains("dark_oak"));
        check("//replace offers its mask and its pattern",
                complete("//replace", "#so", world).contains("#solid"));
    }

    private static void aTypeIsWhatItsCommandTakes(Supplier<World> world) {
        check("//feature offers the features of the world",
                complete("//feature", "oa", world).contains("oak"));
        check("with the namespace once it is typed",
                complete("//feature", "minecraft:oa", world).contains("minecraft:oak"));
        check("the feature brush its type",
                complete("/brush feature", "sphere 5 5 oa", world).contains("oak"));
        check("the structure placer the structures",
                complete("/tool structureplacer", "oak_l", world).contains("oak_log"));
        check("//forest its tree types", complete("//forest", "jun", world).contains("jungle"));
        check("//tree the tree types, not the tools", complete("//tree", "", world).contains("dark_oak")
                && !complete("//tree", "", world).contains("repl"));
        check("//repl its pattern", complete("//repl", "sto", world).contains("stone"));
        check("the long range builder a pattern for both clicks",
                complete("/tool lrbuild", "stone gold_bl", world).contains("gold_block"));
        check("the //placefeature spelling what //feature takes",
                complete("/placefeature", "oa", world).contains("oak"));
        check("/placement its placements", complete("placement", "", world).containsAll(
                List.of("world", "player", "here", "pos1", "min", "max")));
        check("/remove its entity filters", complete("remove", "fall", world).contains("fallingblocks"));
        check("/tool primary the brushes, through its alias //primary",
                complete("//primary", "cyl", world).contains("cylinder"));
        boolean[] opened = new boolean[1];
        complete("//set", "sto", () -> {
            opened[0] = true;
            return null;
        });
        check("the world is only asked for a feature or a structure", !opened[0]);
    }

    private static void flagsAndTheirValuesComplete(Supplier<World> world) {
        List<String> flags = complete("//copy", "-", world);
        check("a dash offers the flags of the command", flags.contains("-e") && flags.contains("-m"));
        check("a mask follows -m", complete("//copy", "-m sto", world).contains("stone"));
        check("a flag is not counted as an argument",
                complete("/brush sphere", "-h sto", world).contains("stone"));
        check("a negative number offers no flag", complete("//expand", "-5", world).isEmpty());
    }

    private static void theWordsOfAnAlternativeAreOffered(Supplier<World> world) {
        List<String> anvil = complete("/anvil", "re", world);
        check("/anvil offers its sub-commands", anvil.contains("replace") && anvil.contains("removelayers"));
        check("and not the blocks", !anvil.contains("redstone_block"));
        checkEquals("//cui offers true and false", List.of("true", "false"), complete("//cui", "", world));
    }
}
