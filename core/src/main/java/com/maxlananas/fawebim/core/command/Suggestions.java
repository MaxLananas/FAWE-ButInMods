package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.function.EntityRemovers;
import com.maxlananas.fawebim.core.region.RegionFactories;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.session.PlacementType;
import com.maxlananas.fawebim.core.util.Str;
import com.maxlananas.fawebim.core.session.SessionManager;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.TreeTypes;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the client offers to complete an argument with.
 *
 * <p>FAWE completes a pattern with the blocks, the {@code #} pattern names and the
 * block categories, a mask with the {@code #} mask names and the blocks that can
 * stand for one, a biome with the biome ids, and so on. The list is filtered by
 * what has been typed and cut at {@link #LIMIT} entries, which is what the client
 * can show without scrolling for a minute.</p>
 */
public final class Suggestions {

    /** How many entries one argument may offer. */
    private static final int LIMIT = 80;

    /** The pattern names this build parses, {@code #} included. */
    private static final List<String> PATTERNS = List.of(
            "#clipboard", "#copy", "#existing", "#biome", "#hotbar", "#mask[", "#buffer[",
            "#buffer2d[", "#nx[", "#ny[", "#nz[", "#!x[", "#!y[", "#!z[", "#offset[",
            "#spread[", "#randomoffset[", "#solidspread[", "#surfacespread[", "#l[", "#linear[", "#l3d[",
            "#linear3d[", "#l2d[", "#linear2d[",
            "#perlin[", "#simplex[", "#voronoi[", "#rmf[", "#ts[", "#typeswap[", "#swaptype[",
            "#rel[", "#~[", "#color[", "#colour[", "#averagecolor[", "#anglecolor[",
            "#lighten[", "#darken[", "#saturate[", "#desaturate[", "##", "##*", "*", "=", "^", "^[");

    /** The mask names this build parses, {@code #} included. */
    private static final List<String> MASKS = List.of(
            "#air", "#existing", "#solid", "#liquid", "#fullcube", "#wall", "#surface",
            "#angle[", "#surfaceangle[", "#roc[", "#beside[", "#extrema[", "#xaxis", "#yaxis",
            "#zaxis", "#true", "#false", "#exposed", "#biome[", "#region", "#sel", "#dregion",
            "#dsel", "#offset[", "#simplex[", "%", "!", "=", ">", "<", "$", "^[", "^=[");

    private static final List<String> BOOLEANS = List.of("true", "false");

    private Suggestions() {
    }

    /**
     * The completions of the word being typed after a command's name.
     *
     * <p>{@code remaining} is the text after the name. The words before the last
     * are counted past the flags and the values of the value flags, and the word
     * being typed is matched with every argument it may fill: an optional
     * argument takes a word only while the line holds more words than the
     * required arguments still to fill, as WorldEdit binds them, so
     * {@code /brush forest sphere } offers the tree types of {@code <type>} as
     * well as the nothing of {@code [radius]}. A word opening with a dash offers
     * the flags, and the word after {@code -m} a mask.</p>
     *
     * @param world the world of whoever asks; only called for an argument that
     *              takes a feature or a structure id
     */
    public static List<String> forLine(CommandRegistry.Entry entry, String remaining, Supplier<World> world) {
        String[] tokens = remaining.split(" ", -1);
        String typed = tokens[tokens.length - 1];
        Set<String> out = new LinkedHashSet<>();
        int words = 0;
        String valueFlag = null;
        // A value flag of the family that is a switch under the sub-command, as
        // the -f of //schem list, takes no value: see Ctx.
        Set<String> switches = Set.of();
        for (int i = 0; i < tokens.length - 1; i++) {
            String token = tokens[i];
            if (valueFlag != null) {
                valueFlag = null;
            } else if (Ctx.isSwitch(token) || token.startsWith("--")) {
                String flag = token.substring(1);
                if (entry.valueFlags.contains(flag) && !switches.contains(flag)) {
                    valueFlag = flag;
                }
            } else if (!token.isEmpty()) {
                if (words == 0) {
                    switches = entry.switchesUnder.getOrDefault(token.toLowerCase(Locale.ROOT), Set.of());
                }
                words++;
            }
        }
        if (valueFlag != null) {
            if (valueFlag.equals("m")) {
                out.addAll(masks(typed));
            }
        } else if (typed.equals("-") || Ctx.isSwitch(typed)) {
            out.addAll(flags(entry, typed.substring(1)));
        } else {
            String command = (entry.aliasOf != null ? entry.aliasOf : entry).name;
            List<String> arguments = positional(entry.arguments);
            for (int index : candidates(arguments, words)) {
                String argument = arguments.get(index);
                List<String> offered = forArgument(command, argument, typed, world);
                out.addAll(offered.isEmpty() ? choices(argument, typed) : offered);
            }
        }
        if (entry.suggestions != null) {
            out.addAll(entry.suggestions.apply(remaining));
        }
        return out.size() <= LIMIT ? List.copyOf(out) : List.copyOf(out).subList(0, LIMIT);
    }

    /** The declared arguments a word fills, without the flags some signatures list among them. */
    private static List<String> positional(List<String> arguments) {
        List<String> out = new ArrayList<>(arguments.size());
        for (String argument : arguments) {
            if (!argument.startsWith("-") && !argument.startsWith("[-")) {
                out.add(argument);
            }
        }
        return out;
    }

    /**
     * The arguments the word after {@code words} others may fill. The line may
     * still grow by any number of words, and each length binds the optional
     * arguments differently: with {@code spare} words more than the required
     * arguments, the first {@code spare} optional ones take one each.
     */
    private static List<Integer> candidates(List<String> arguments, int words) {
        int required = 0;
        for (String argument : arguments) {
            if (!argument.startsWith("[")) {
                required++;
            }
        }
        int optional = arguments.size() - required;
        Set<Integer> out = new LinkedHashSet<>();
        for (int spare = Math.max(0, words + 1 - required); spare <= optional; spare++) {
            int word = 0;
            int taken = 0;
            for (int index = 0; index < arguments.size(); index++) {
                boolean fills = !arguments.get(index).startsWith("[") || taken++ < spare;
                if (!fills) {
                    continue;
                }
                if (word == words) {
                    out.add(index);
                    break;
                }
                word++;
            }
        }
        // A last argument that is a list takes every word after it.
        if (out.isEmpty() && !arguments.isEmpty() && arguments.get(arguments.size() - 1).contains("...")) {
            out.add(arguments.size() - 1);
        }
        return List.copyOf(out);
    }

    /** The flags of a command whose name starts with what follows the dash. */
    private static List<String> flags(CommandRegistry.Entry entry, String prefix) {
        List<String> out = new ArrayList<>();
        for (Set<String> flags : List.of(entry.booleanFlags, entry.valueFlags)) {
            for (String flag : flags) {
                if (flag.startsWith(prefix) && !out.contains("-" + flag)) {
                    out.add("-" + flag);
                }
            }
        }
        return out;
    }

    /** The {@code a|b|c} alternatives an argument is declared with, filtered by what is typed. */
    private static List<String> choices(String argument, String typed) {
        String plain = argument.replace("[", "").replace("]", "").replace("<", "").replace(">", "");
        if (plain.indexOf('|') < 0) {
            return List.of();
        }
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String choice : plain.split("\\|")) {
            String word = choice.trim();
            if (!word.isEmpty() && word.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * The completions of one argument of a command: the argument's declared name
     * decides what may be written there, and the text typed so far filters it.
     */
    public static List<String> forArgument(String argument, String typed) {
        return forArgument("", argument, typed, null);
    }

    /**
     * The completions of one argument of a command, named as it is registered
     * (an alias by the command it copies). Most arguments say what they take by
     * their name alone; a {@code type} is a tree type, a feature, a structure, a
     * brush or an entity filter depending on the command.
     */
    public static List<String> forArgument(String command, String argument, String typed, Supplier<World> world) {
        String name = plain(argument).toLowerCase(Locale.ROOT);
        String prefix = typed.trim().toLowerCase(Locale.ROOT);
        // The words of an a|b|c argument are offered as they are written, and
        // one of them may contain anything, e.g. the replacepattern of /anvil.
        if (name.indexOf('|') >= 0) {
            return List.of();
        }
        // Both arguments of the long-range builder, primary and secondary, are patterns.
        if (name.contains("pattern") || command.equals("/tool lrbuild")) {
            return patterns(prefix);
        }
        if (name.contains("mask")) {
            return masks(prefix);
        }
        if (name.contains("biome")) {
            return filtered(BlockState.registry().biomeNames(), prefix);
        }
        if (name.contains("item")) {
            return filtered(BlockState.registry().itemNames(), prefix);
        }
        if (name.contains("block")) {
            return filtered(BlockState.registry().blockNames(), prefix);
        }
        if (name.equals("offset")) {
            return filtered(Directions.DIAGONAL_SUGGESTIONS, prefix);
        }
        if (name.contains("direction")) {
            return filtered(Directions.SUGGESTIONS, prefix);
        }
        if (name.equals("selector")) {
            return filtered(com.maxlananas.fawebim.core.region.Selectors.NAMES, prefix);
        }
        if (name.equals("shape")) {
            return filtered(RegionFactories.SHAPES, prefix);
        }
        if (name.equals("placementtype")) {
            List<String> types = new ArrayList<>();
            for (PlacementType type : PlacementType.values()) {
                types.add(type.name().toLowerCase(Locale.ROOT));
            }
            return filtered(types, prefix);
        }
        if (name.equals("feature") || name.equals("type") && command.equals("/brush feature")) {
            return world == null ? List.of() : filtered(world.get().featureIds(), prefix);
        }
        if (name.equals("structure") || name.equals("type") && command.equals("/brush structure")) {
            return world == null ? List.of() : filtered(world.get().structureIds(), prefix);
        }
        if (name.equals("tree-type") || name.equals("type")
                && (command.equals("//forestgen") || command.equals("/brush forest") || command.equals("/tool tree"))) {
            return filtered(TreeTypes.canonicalNames(), prefix);
        }
        if (name.equals("type") && (command.equals("/tool primary") || command.equals("/tool secondary"))) {
            List<String> brushes = new ArrayList<>();
            for (String[] row : BrushTable.BRUSHES) {
                brushes.add(row[0]);
            }
            return filtered(brushes, prefix);
        }
        if (name.equals("type") && command.equals("remove")) {
            List<String> filters = new ArrayList<>();
            for (EntityRemovers.Type type : EntityRemovers.Type.values()) {
                filters.add(type.word());
            }
            return filtered(filters, prefix);
        }
        if (name.contains("player")) {
            return players(prefix);
        }
        if (name.equals("boolean") || name.contains("randomize") || name.contains("hollow")) {
            return filtered(BOOLEANS, prefix);
        }
        return List.of();
    }

    /** Everything a pattern may be built from, for the token being typed. */
    public static List<String> patterns(String typed) {
        return completeToken(typed, Suggestions::patternPart);
    }

    /** Everything a mask may be built from, for the token being typed. */
    public static List<String> masks(String typed) {
        return completeToken(typed, Suggestions::maskPart);
    }

    /**
     * The completions of the segment being typed inside a pattern or a mask,
     * with everything that comes before it kept: {@code #perlin[16][dirt,sto}
     * completes {@code sto} and answers {@code #perlin[16][dirt,stone}, so the
     * suggestion replaces the whole token.
     */
    private static List<String> completeToken(String typed,
                                              Function<String, List<String>> part) {
        String text = typed == null ? "" : typed;
        int start = segmentStart(text);
        String head = text.substring(0, start);
        String tail = text.substring(start);
        int weight = weightPrefix(tail);
        List<String> out = new ArrayList<>();
        for (String suggestion : part.apply(tail.substring(weight).toLowerCase(Locale.ROOT))) {
            if (out.size() >= LIMIT) {
                break;
            }
            out.add(head + tail.substring(0, weight) + suggestion);
        }
        return out;
    }

    /** Where the segment being typed starts: after the last separator. */
    private static int segmentStart(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ',' || c == '[' || c == '(' || c == '{') {
                return i + 1;
            }
            if (c == ']' || c == ')' || c == '}') {
                return text.length();
            }
        }
        return 0;
    }

    /** The {@code 50%} a weighted entry starts with, kept in front of the name. */
    private static int weightPrefix(String tail) {
        int i = 0;
        while (i < tail.length() && Character.isDigit(tail.charAt(i))) {
            i++;
        }
        if (i > 0 && i < tail.length() && tail.charAt(i) == '%') {
            return i + 1;
        }
        return 0;
    }

    /** The names one segment of a pattern may be written with. */
    private static List<String> patternPart(String prefix) {
        List<String> out = new ArrayList<>();
        if (prefix.startsWith("*") || prefix.startsWith("^") || prefix.startsWith("$")) {
            String inner = prefix.substring(1);
            List<String> names = prefix.startsWith("$") ? BlockState.registry().biomeNames()
                    : BlockState.registry().blockNames();
            for (String name : filtered(names, inner)) {
                out.add(prefix.charAt(0) + name);
            }
            return out;
        }
        // The # and ## entries come first: they are what a player reaches for
        // when the block name is not what they are after.
        out.addAll(literal(PATTERNS, prefix));
        out.addAll(categories(prefix));
        if (out.size() < LIMIT) {
            out.addAll(filtered(BlockState.registry().blockNames(), prefix));
        }
        return out;
    }

    /** The names one segment of a mask may be written with. */
    private static List<String> maskPart(String prefix) {
        if (prefix.startsWith("%")) {
            return List.of();
        }
        // A negation and the masks of the block under or over one wrap a mask
        // of their own: what follows the sign completes as one.
        if (!prefix.isEmpty() && "!<>".indexOf(prefix.charAt(0)) >= 0) {
            List<String> out = new ArrayList<>();
            for (String inner : maskPart(prefix.substring(1))) {
                out.add(prefix.charAt(0) + inner);
            }
            return out;
        }
        if (prefix.startsWith("$")) {
            List<String> out = new ArrayList<>();
            for (String biome : filtered(BlockState.registry().biomeNames(), prefix.substring(1))) {
                out.add("$" + biome);
            }
            return out;
        }
        List<String> out = new ArrayList<>();
        out.addAll(literal(MASKS, prefix));
        out.addAll(categories(prefix));
        if (out.size() < LIMIT) {
            out.addAll(filtered(BlockState.registry().blockNames(), prefix));
        }
        return out;
    }

    /** The {@code ##category} entries of the block registry. */
    public static List<String> categories(String prefix) {
        List<String> out = new ArrayList<>();
        for (String category : BlockState.registry().categories()) {
            if (category.isEmpty()) {
                continue;
            }
            String entry = "##" + category;
            if (entry.startsWith(prefix)) {
                out.add(entry);
                if (out.size() >= LIMIT) {
                    break;
                }
            }
        }
        return out;
    }

    /** The names of everybody with a session, for {@code //undo <player>}. */
    public static List<String> players(String prefix) {
        List<String> out = new ArrayList<>();
        for (LocalSession session : SessionManager.get().all()) {
            String name = session.ownerName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(name);
            }
        }
        return out;
    }

    /** The {@code #} names are literal, so they keep the spelling they are written with. */
    private static List<String> literal(List<String> names, String prefix) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith(prefix)) {
                out.add(name);
                if (out.size() >= LIMIT) {
                    break;
                }
            }
        }
        return out;
    }

    private static List<String> filtered(List<String> names, String prefix) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (matches(name, prefix)) {
                out.add(shown(name, prefix));
                if (out.size() >= LIMIT) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * A registry name matches either whole or without its namespace, so typing
     * {@code stone} answers with {@code stone} and typing {@code minecraft:st}
     * with the full id.
     */
    private static boolean matches(String name, String prefix) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith(prefix) || Str.stripNamespace(lower).startsWith(prefix);
    }

    private static String shown(String name, String prefix) {
        return prefix.contains(":") ? name.toLowerCase(Locale.ROOT) : Str.stripNamespace(name);
    }

    /** The argument name without its brackets or its default. */
    private static String plain(String argument) {
        String name = argument.replace("[", " ").replace("]", " ").replace("<", " ")
                .replace(">", " ").trim();
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    /** Number of suggestions the client is offered for one argument. */
    public static int limit() {
        return LIMIT;
    }
}
