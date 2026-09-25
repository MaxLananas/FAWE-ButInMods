package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.Str;
import com.maxlananas.fawebim.core.world.Direction;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything a command implementation needs: parsed arguments, flags, the
 * actor's session, the selection and a lazily created edit session.
 */
public final class Ctx {

    private final CommandRegistry.Entry entry;
    private final Actor actor;
    private final List<String> tokens;
    private final List<String> positional = new ArrayList<>();
    private final Map<String, List<String>> flags = new LinkedHashMap<>();
    private EditSession editSession;
    private Region selection;

    Ctx(CommandRegistry.Entry entry, Actor actor, List<String> tokens) {
        this.entry = entry;
        this.actor = actor;
        this.tokens = tokens;
        parse();
    }

    private void parse() {
        List<String> raw = tokens.size() > 1 ? tokens.subList(1, tokens.size()) : List.of();
        for (int i = 0; i < raw.size(); i++) {
            String token = raw.get(i);
            if (isSwitch(token)) {
                String flag = token.substring(1);
                boolean valueFlag = entry.valueFlags.contains(flag);
                if (valueFlag) {
                    // -flag value, or -flag=value
                    int eq = flag.indexOf('=');
                    if (eq >= 0) {
                        flags.computeIfAbsent(flag.substring(0, eq), k -> new ArrayList<>())
                                .add(flag.substring(eq + 1));
                    } else if (i + 1 < raw.size()) {
                        flags.computeIfAbsent(flag, k -> new ArrayList<>()).add(raw.get(++i));
                    } else if (entry.booleanFlags.contains(flag)) {
                        // A flag upstream declares both ways and that ends the
                        // line keeps its switch reading, e.g. /brush gravity 5 -h.
                        flags.computeIfAbsent(flag, k -> new ArrayList<>());
                    }
                } else {
                    flags.computeIfAbsent(flag, k -> new ArrayList<>());
                }
                continue;
            }
            if (token.startsWith("--")) {
                String flag = token.substring(2);
                flags.computeIfAbsent(flag, k -> new ArrayList<>());
                continue;
            }
            positional.add(token);
        }
    }

    /**
     * Whether a token is a switch. A command line mixes both kinds of dash -
     * {@code //pos1 -1,59,-1} is a position and {@code //expand -10} is a count -
     * so only a dash that opens neither is read as the start of a switch.
     */
    private static boolean isSwitch(String token) {
        if (token.length() < 2 || token.charAt(0) != '-' || Str.isDouble(token)) {
            return false;
        }
        for (String part : token.substring(1).split(",", -1)) {
            String value = part.trim();
            if (!value.isEmpty() && (value.charAt(0) == '~' || value.charAt(0) == '^')) {
                value = value.substring(1);
            }
            if (!value.isEmpty() && !Str.isDouble(value)) {
                return true;
            }
        }
        return false;
    }

    public Actor actor() {
        return actor;
    }

    public LocalSession session() {
        return actor.session();
    }

    public World world() {
        return actor.world();
    }

    public CommandRegistry.Entry entry() {
        return entry;
    }

    public List<String> args() {
        return positional;
    }

    public String arg(int index) {
        if (index >= positional.size()) {
            throw CommandRegistry.error("Missing argument " + (index + 1) + " for " + entry.usage());
        }
        return positional.get(index);
    }

    /**
     * One positional argument, or {@code null} when the line did not reach it.
     * {@link Argument#isNumber()} is what tells a count from a player name in the
     * commands where upstream accepts either.
     */
    public Argument argument(int index) {
        return index < positional.size() ? new Argument(positional.get(index)) : null;
    }

    /** A positional argument with the two readings a command may need. */
    public record Argument(String value) {

        public boolean isNumber() {
            return Str.isInteger(value) || Str.isDouble(value);
        }
    }

    public String arg(int index, String fallback) {
        return index < positional.size() ? positional.get(index) : fallback;
    }

    public String joined(int from) {
        return Str.join(positional.subList(Math.min(from, positional.size()), positional.size()), " ");
    }

    public int intArg(int index) {
        return (int) Math.round(doubleArg(index));
    }

    public int intArg(int index, int fallback) {
        return index < positional.size() ? (int) Math.round(doubleArg(index)) : fallback;
    }

    public double doubleArg(int index) {
        String value = arg(index);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            // Coordinate style arguments ("~5", "^3") are handled by blockVector().
            throw CommandRegistry.error("Expected a number but got '" + value + "'");
        }
    }

    public double doubleArg(int index, double fallback) {
        return index < positional.size() ? doubleArg(index) : fallback;
    }

    public boolean hasFlag(String flag) {
        return flags.containsKey(flag);
    }

    /** Every flag of the command line, with the values of the value flags. */
    public Map<String, List<String>> flags() {
        return flags;
    }

    public String flagValue(String flag, String fallback) {
        List<String> values = flags.get(flag);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    public int flagInt(String flag, int fallback) {
        String value = flagValue(flag, null);
        return value == null ? fallback : Integer.parseInt(value);
    }

    public double flagDouble(String flag, double fallback) {
        String value = flagValue(flag, null);
        return value == null ? fallback : Double.parseDouble(value);
    }

    public EditSession editSession() {
        if (editSession == null) {
            editSession = new EditSession(world(), session(), entry.name);
        }
        return editSession;
    }

    public EditSession editSession(String description) {
        if (editSession == null) {
            editSession = new EditSession(world(), session(), description);
        }
        return editSession;
    }

    /**
     * Prints the trace of the edit this command ran, when the session has the
     * trace hook on and a session was opened at all.
     */
    public void reportTrace() {
        if (editSession != null) {
            editSession.reportTrace(actor());
        }
    }

    /** The player's selection, or a clear error when there is none. */
    public Region selection() {
        if (selection == null) {
            selection = session().getSelection(world());
        }
        if (selection == null) {
            throw CommandRegistry.error("No region selected. Use //pos1 and //pos2 (or the wand) first.");
        }
        return selection;
    }

    public boolean hasSelection() {
        return session().isSelectionDefined(world());
    }

    /**
     * The block a command that works from where the player stands anchors on.
     *
     * <p>The console has no position, and a command run from it — a script, a
     * command block, the server console — still has to build its sphere or its
     * pyramid somewhere: the centre of the selection is WorldEdit's own answer
     * for those sources, and the world origin is the last resort when no region
     * is selected either.</p>
     */
    /**
     * Refuses a command that moves the player when the source has no position:
     * the console, a command block and a function cannot be teleported.
     */
    public void requirePosition() {
        if (actor.position() == null) {
            throw CommandRegistry.error("This command must be run by a player");
        }
    }

    /** The block the actor looks at, up to {@code range} blocks away. */
    public BlockVector3 targetBlock(int range) {
        BlockVector3 target = world().getTargetBlock(actor, range);
        if (target == null) {
            throw CommandRegistry.error("No block in reach: look at a block and run it again");
        }
        return target;
    }

    public BlockVector3 placement() {
        BlockVector3 placed = session().getPlacement().position(world(), actor());
        if (placed != null) {
            return placed;
        }
        BlockVector3 position = actor.position();
        if (position != null) {
            return position;
        }
        if (hasSelection()) {
            Vector3 center = selection().getCenter();
            return new BlockVector3((int) Math.floor(center.x()), (int) Math.floor(center.y()),
                    (int) Math.floor(center.z()));
        }
        return BlockVector3.ZERO;
    }

    /**
     * Parses a block position, supporting WorldEdit's relative ({@code ~}) and
     * local ({@code ^}) notation relative to the player.
     */
    public BlockVector3 blockVector(int index) {
        return parseBlockVector(arg(index));
    }

    public BlockVector3 parseBlockVector(String input) {
        String[] split = input.split(",");
        if (split.length != 3) {
            if (Str.isInteger(input)) {
                // Single number: the y coordinate for //up style commands.
                BlockVector3 base = placement();
                return new BlockVector3(base.x(), Integer.parseInt(input), base.z());
            }
            throw CommandRegistry.error("Expected a position like 10,64,-5 but got '" + input + "'");
        }
        BlockVector3 origin = actor.position();
        Vector3 direction = actor.direction();
        double[] values = new double[3];
        for (int i = 0; i < 3; i++) {
            values[i] = parseCoordinate(split[i].trim(), i, origin, direction);
        }
        return new BlockVector3((int) Math.floor(values[0]), (int) Math.floor(values[1]), (int) Math.floor(values[2]));
    }

    private double parseCoordinate(String token, int axis, BlockVector3 origin, Vector3 direction) {
        double base = origin == null ? 0 : (axis == 0 ? origin.x() : axis == 1 ? origin.y() : origin.z());
        if (token.startsWith("~")) {
            String rest = token.substring(1);
            return rest.isEmpty() ? base : base + Double.parseDouble(rest);
        }
        if (token.startsWith("^")) {
            String rest = token.substring(1);
            double offset = rest.isEmpty() ? 0 : Double.parseDouble(rest);
            Vector3 forward = new Vector3(direction.x(), 0, direction.z());
            if (forward.length() < 1e-6) {
                forward = new Vector3(0, 0, 1);
            }
            forward = forward.normalize();
            Vector3 right = new Vector3(forward.z(), 0, -forward.x());
            Vector3 up = new Vector3(0, 1, 0);
            // ^left ^up ^forward
            Vector3 local = switch (axis) {
                case 0 -> right;
                case 1 -> up;
                default -> forward;
            };
            return base + local.length() * offset;
        }
        return Double.parseDouble(token);
    }

    /** Resolves a pattern argument using the session's global pattern as fallback. */
    public Pattern pattern(int index) {
        if (index >= positional.size()) {
            Pattern global = session().getPattern();
            if (global == null) {
                throw CommandRegistry.error("Missing pattern argument (set one with /gtexture)");
            }
            return global;
        }
        return Parsers.pattern(arg(index), this);
    }

    public Pattern patternOrDefault(int index, Pattern fallback) {
        return index < positional.size() ? Parsers.pattern(arg(index), this) : fallback;
    }

    public Mask mask(int index) {
        return mask(index, session().getMask());
    }

    public Mask mask(int index, Mask fallback) {
        if (index >= positional.size()) {
            if (fallback == null) {
                throw CommandRegistry.error("Missing mask argument (set one with /gmask)");
            }
            return fallback;
        }
        return Parsers.mask(arg(index), this);
    }

    /** All remaining raw arguments after {@code from}. */
    public List<String> rawArgs(int from) {
        return positional.subList(Math.min(from, positional.size()), positional.size());
    }

    public Direction directionArg(int index) {
        return Direction.parse(arg(index));
    }

    public boolean boolArg(int index, boolean fallback) {
        if (index >= positional.size()) {
            return fallback;
        }
        String value = arg(index).toLowerCase(Locale.ROOT);
        return value.equals("true") || value.equals("yes") || value.equals("1");
    }
}
