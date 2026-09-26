package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.Str;
import com.maxlananas.fawebim.core.session.SessionManager;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
            "#spread[", "#solidspread[", "#surfacespread[", "#l[", "#linear[", "#l3d[", "#l2d[",
            "#perlin[", "#simplex[", "#voronoi[", "#rmf[", "#ts[", "#typeswap[", "#swaptype[",
            "#rel[", "#~[", "#color[", "#colour[", "#averagecolor[", "#anglecolor[",
            "#lighten[", "#darken[", "#saturate[", "#desaturate[", "##", "*", "=");

    /** The mask names this build parses, {@code #} included. */
    private static final List<String> MASKS = List.of(
            "#air", "#existing", "#solid", "#liquid", "#fullcube", "#wall", "#surface",
            "#angle[", "#surfaceangle[", "#roc[", "#beside[", "#extrema[", "#xaxis", "#yaxis",
            "#zaxis", "#true", "#false", "#exposed", "#biome[", "#region", "#sel", "#dregion",
            "#dsel", "#offset[", "#simplex[", "%", "!", "=");

    private static final List<String> DIRECTIONS = List.of(
            "north", "south", "east", "west", "up", "down", "me", "back", "+x", "-x", "+y", "-y",
            "+z", "-z");

    private static final List<String> SHAPES = List.of(
            "sphere", "hsphere", "cyl", "hcyl", "cuboid", "pyramid", "hpyramid", "cone", "hcone");

    private static final List<String> BOOLEANS = List.of("true", "false");

    private Suggestions() {
    }

    /**
     * The completions of one argument of a command: the argument's declared name
     * decides what may be written there, and the text typed so far filters it.
     */
    public static List<String> forArgument(String argument, String typed) {
        String name = plain(argument).toLowerCase(Locale.ROOT);
        String prefix = typed.trim().toLowerCase(Locale.ROOT);
        if (name.contains("pattern")) {
            return patterns(prefix);
        }
        if (name.contains("mask")) {
            return masks(prefix);
        }
        if (name.contains("biome")) {
            return filtered(BlockState.registry().biomeNames(), prefix);
        }
        if (name.contains("block") || name.contains("image") || name.contains("item")) {
            return filtered(BlockState.registry().blockNames(), prefix);
        }
        if (name.contains("direction") || name.contains("dir")) {
            return filtered(DIRECTIONS, prefix);
        }
        if (name.contains("shape") || name.contains("region") || name.contains("type")) {
            return filtered(SHAPES, prefix);
        }
        if (name.contains("player")) {
            return players(prefix);
        }
        if (name.startsWith("true|false") || name.equals("boolean") || name.contains("randomize")
                || name.contains("hollow")) {
            return filtered(BOOLEANS, prefix);
        }
        if (name.contains("boolean")) {
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
                                              java.util.function.Function<String, List<String>> part) {
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
        if (prefix.startsWith("!") || prefix.startsWith("%")) {
            return List.of();
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
