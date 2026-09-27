package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.Msg;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * What a player reads back: the usage for a line that stops short, FAWE's
 * //calc, the names FAWE gives /worldedit, and the numbers in a message.
 *
 * <p>{@code //set} with nothing after it answered "Empty pattern" and
 * {@code //deform} ran on an empty expression. {@code //calc 2+2*3} wrote
 * "=: 8", {@code //calc 2+} gave 2, and {@code //calc a=2; a*3} failed with
 * an internal error. {@code /fawe} was an unknown command, and
 * {@code /we help} printed a usage line.</p>
 */
final class CommandFeedbackTests {

    private CommandFeedbackTests() {
    }

    static void run() {
        section("command feedback");
        aShortLineGetsTheUsage();
        calculateAnswersAsFawe();
        worldEditAnswersToFawesNames();
        rotateWarnsAboutAnglesBetweenQuarters();
        numbersReadAsTyped();
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(63);
        TestActor actor = new TestActor(name, world, new BlockVector3(0, 64, 0));
        answer(actor, "//pos1 0,60,0");
        answer(actor, "//pos2 3,63,3");
        return actor;
    }

    private static void aShortLineGetsTheUsage() {
        TestActor actor = actor("ShortLine");
        TestWorld world = (TestWorld) actor.world();
        String set = answer(actor, "//set");
        check("//set without a pattern gives the usage (" + set + ")",
                set.contains("Missing argument 1 for //set <pattern>"));
        String replace = answer(actor, "//replace");
        check("so does //replace (" + replace + ")", replace.contains("Missing argument 1 for //replace"));
        String near = answer(actor, "//replacenear 5");
        check("and //replacenear with its size only (" + near + ")",
                near.contains("Missing argument 2 for //replacenear"));
        int before = world.getBlock(1, 63, 1);
        String deform = answer(actor, "//deform");
        check("//deform without an expression gives the usage (" + deform + ")",
                deform.contains("Missing argument 1 for //deform"));
        checkEquals("and changes nothing", before, world.getBlock(1, 63, 1));
    }

    private static void calculateAnswersAsFawe() {
        TestActor actor = actor("Calc");
        String sum = answer(actor, "//calc 2+2*3");
        check("//calc writes the expression and its value (" + sum + ")", sum.contains("2+2*3 = 8"));
        String decimals = answer(actor, "//calc 0.1+0.2");
        check("with at most five decimals (" + decimals + ")",
                decimals.contains("0.1+0.2 = 0.3") && !decimals.contains("0.30000000000000004"));
        check("and the thousands grouped", answer(actor, "//calc 1000000*3").contains("= 3,000,000"));
        String assigned = answer(actor, "//calc a=2; a*3");
        check("a variable is assigned in the expression (" + assigned + ")", assigned.contains("= 6"));
        String cut = answer(actor, "//calc 2+");
        check("an expression that stops short is refused (" + cut + ")",
                cut.contains("'2+' could not be parsed as a valid expression"));
        check("and one that is missing gets the usage",
                answer(actor, "//calc").contains("Missing argument 1 for //calculate"));
        boolean refused;
        try {
            Expression.compile("3*");
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("the expressions of //generate and the masks refuse it too", refused);
        checkEquals("an empty expression is still nothing", 0.0,
                Expression.compile("").evaluate(new Expression.Variables()));
    }

    private static void worldEditAnswersToFawesNames() {
        TestActor actor = actor("WorldEditNames");
        String fawe = answer(actor, "/fawe");
        check("/fawe lists the sub-commands of /worldedit (" + fawe + ")",
                fawe.contains("Sub-commands of we") && fawe.contains("/we version") && fawe.contains("//cui"));
        check("on one page", !fawe.contains("page 1/"));
        check("/fawe version is /we version", answer(actor, "/fawe version").contains("FastAsyncWorldEdit, but in mods"));
        check("so is /fastasyncworldedit ver",
                answer(actor, "/fastasyncworldedit ver").contains("FastAsyncWorldEdit, but in mods"));
        check("and /worldedit ver", answer(actor, "/worldedit ver").contains("FastAsyncWorldEdit, but in mods"));
        String help = answer(actor, "/we help copy");
        check("/we help searches the commands (" + help + ")", help.contains("Commands matching 'copy'"));
        String unknown = answer(actor, "/we nope");
        check("an unknown sub-command lists the known ones (" + unknown + ")",
                unknown.contains("Unknown sub-command 'nope'") && unknown.contains("version"));
        check("a listing of one says one command", answer(actor, "//help -s schem").contains("(1 command)"));
    }

    private static void rotateWarnsAboutAnglesBetweenQuarters() {
        TestActor actor = actor("RotateNote");
        answer(actor, "//copy");
        String quarter = answer(actor, "//rotate 90");
        check("//rotate 90 says nothing more (" + quarter + ")", !quarter.contains("Interpolation"));
        String between = answer(actor, "//rotate 45");
        check("//rotate 45 says the angle lands between two quarters (" + between + ")",
                between.contains("Interpolation is not supported"));
    }

    private static void numbersReadAsTyped() {
        TestActor actor = actor("Numbers");
        check("a brush radius of 3 reads 3", answer(actor, "/brush sphere stone 3").contains("(radius 3)"));
        check("and one of 2.5 reads 2.5", answer(actor, "/brush sphere stone 2.5").contains("(radius 2.5)"));
        checkEquals("three decimals at most", "3.142", Msg.formatDouble(Math.PI));
        checkEquals("and no zero after them", "0.1", Msg.formatDouble(0.1));
        checkEquals("a whole number has none", "12", Msg.formatDouble(12));
        checkEquals("and a negative one keeps its sign", "-2.25", Msg.formatDouble(-2.25));

        // A count takes the noun that goes with it, where it wrote "block(s)".
        answer(actor, "//pos1 0,70,0");
        answer(actor, "//pos2 0,70,0");
        String one = answer(actor, "//set stone");
        check("one block is a block (" + one + ")", one.contains("Set: 1 block affected in"));
        check("and undone, one block change", answer(actor, "//undo").contains("Undid: 1 block change"));
        answer(actor, "//pos2 1,70,0");
        String two = answer(actor, "//set stone");
        check("two are blocks (" + two + ")", two.contains("Set: 2 blocks affected in"));
        String none = answer(actor, "//set stone");
        check("and so is none (" + none + ")", none.contains("Set: 0 blocks affected in"));
        String copied = answer(actor, "//copy");
        check("//copy counts them the same way (" + copied + ")", copied.contains("Copied: 2 blocks to your clipboard"));
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }
}
