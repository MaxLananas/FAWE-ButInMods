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
    private EditSession readSession;
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

    /**
     * The rest of the line exactly as it was typed, flags included. A command
     * that routes to another one hands this over instead of the positional
     * arguments, so {@code -h} and friends reach the command that reads them.
     */
    public String tail() {
        return tokens.size() > 1 ? Str.join(tokens.subList(1, tokens.size()), " ") : "";
    }

    public String joined(int from) {
        return Str.join(positional.subList(Math.min(from, positional.size()), positional.size()), " ");
    }

    public int intArg(int index) {
        return Parsers.intArg(arg(index), "argument " + (index + 1));
    }

    public int intArg(int index, int fallback) {
        return index < positional.size() ? intArg(index) : fallback;
    }

    /**
     * A whole number between {@code min} and {@code max}, both included: an
     * argument a command sizes a loop or a random draw with is refused outside
     * that range before anything runs.
     */
    public int intArg(int index, int fallback, int min, int max, String name) {
        int value = intArg(index, fallback);
        if (value < min || value > max) {
            throw CommandRegistry.error("The " + name + " must be between " + min + " and " + max);
        }
        return value;
    }

    /**
     * A number argument; {@code NaN} and the infinities are refused. Coordinate
     * style arguments ({@code ~5}, {@code ^3}) are read by {@link #blockVector}.
     */
    public double doubleArg(int index) {
        return Parsers.finiteArg(arg(index), "argument " + (index + 1));
    }

    public double doubleArg(int index, double fallback) {
        return index < positional.size() ? doubleArg(index) : fallback;
    }

    /**
     * The size argument of a command that works on the world around the player:
     * the half-width of the block of world it walks. A size of two billion is a
     * walk over four billion cells - the server never answers again - so it is
     * refused above {@code limits.max-radius}, the ceiling every radius a
     * generator takes already answers to, with the same line that reports it.
     */
    public int sizeArg(int index, int fallback) {
        return index < positional.size() ? sizeArg(index) : fallback;
    }

    /** {@link #sizeArg(int, int)} for an argument the command cannot do without. */
    public int sizeArg(int index) {
        int size = intArg(index);
        checkRadius(size);
        return size;
    }

    /**
     * A radius that may have decimals, under the same ceiling as
     * {@link #sizeArg(int, int)}: {@code NaN} and the infinities are refused
     * with every other number that is not one.
     */
    public double radiusArg(int index, double fallback) {
        if (index >= positional.size()) {
            return fallback;
        }
        double radius = doubleArg(index);
        checkRadius(radius);
        return radius;
    }

    private static void checkRadius(double radius) {
        int maximum = com.maxlananas.fawebim.core.platform.Config.get().maxRadius;
        if (maximum > 0 && Math.abs(radius) > maximum) {
            throw CommandRegistry.error("Maximum radius (in configuration): " + maximum);
        }
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
        return value == null ? fallback : Parsers.intArg(value, "-" + flag);
    }

    public double flagDouble(String flag, double fallback) {
        String value = flagValue(flag, null);
        return value == null ? fallback : Parsers.finiteArg(value, "-" + flag);
    }

    public EditSession editSession() {
        if (editSession == null) {
            editSession = new EditSession(world(), session(), entry.name);
        }
        return editSession;
    }

    /**
     * A session for the commands that only read blocks, such as {@code //count}
     * and {@code //distr}. It carries the session's source mask so a mask parsed
     * there sees the blocks the source mask lets through, and it records no
     * history entry of its own.
     */
    public EditSession readSession() {
        if (readSession == null) {
            readSession = new EditSession(world(), session(), entry.name, false);
        }
        return readSession;
    }

    public EditSession editSession(String description) {
        if (editSession == null) {
            editSession = new EditSession(world(), session(), description);
        }
        return editSession;
    }

    /**
     * Ends the edits this command opened: what is still buffered is written and
     * the history record is published. The dispatcher calls it once the handler
     * returned or failed, so an edit that stopped half way leaves the world and
     * its history agreeing on the part that was done.
     *
     * @return the blocks the command's edit changed
     */
    long close() {
        long changed = editSession == null ? 0 : editSession.getBlocksChanged();
        if (selection != null) {
            // A command that took the selection may have changed it - //expand,
            // //shift, //move -s - and the selector keeps points of its own that
            // the next click builds on.
            session().getSelector(world()).learnChanges();
        }
        RuntimeException failure = null;
        for (EditSession opened : new EditSession[] {editSession, readSession}) {
            if (opened == null) {
                continue;
            }
            try {
                opened.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return changed;
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
                return new BlockVector3(base.x(), Parsers.coordinate(Parsers.finiteArg(input, "y"), input),
                        base.z());
            }
            throw CommandRegistry.error("Expected a position like 10,64,-5 but got '" + input + "'");
        }
        String[] parts = new String[3];
        int local = 0;
        for (int i = 0; i < 3; i++) {
            parts[i] = split[i].trim();
            if (parts[i].startsWith("^")) {
                local++;
            }
        }
        if (local != 0 && local != 3) {
            // Minecraft's own rule: ^ offsets are along the way the player looks,
            // which has no meaning for one axis of a world position.
            throw CommandRegistry.error("Cannot mix world and local (^) coordinates in '" + input + "'");
        }
        double[] values = local == 3 ? localCoordinates(parts, input) : new double[3];
        if (local == 0) {
            BlockVector3 origin = actor.position();
            for (int i = 0; i < 3; i++) {
                values[i] = worldCoordinate(parts[i], i, origin, input);
            }
        }
        return new BlockVector3(Parsers.coordinate(values[0], input), Parsers.coordinate(values[1], input),
                Parsers.coordinate(values[2], input));
    }

    /** One axis of a world position: a number, or {@code ~} plus an offset from the player. */
    private static double worldCoordinate(String token, int axis, BlockVector3 origin, String input) {
        if (token.startsWith("~")) {
            double base = origin == null ? 0 : (axis == 0 ? origin.x() : axis == 1 ? origin.y() : origin.z());
            String rest = token.substring(1);
            return rest.isEmpty() ? base : base + number(rest, input);
        }
        return number(token, input);
    }

    /**
     * A {@code ^left,^up,^forward} position, computed the way Minecraft's
     * local coordinates are: along the player's view, pitch included, from the
     * centre of the block they stand in. Each offset moves along an axis of the
     * view, so all three have to be read together - moving along world axes
     * one by one put {@code ^0,^0,^5} five blocks east whichever way the player
     * looked.
     */
    private double[] localCoordinates(String[] parts, String input) {
        BlockVector3 origin = actor.position();
        if (origin == null) {
            throw CommandRegistry.error("Local (^) coordinates need a player to look from");
        }
        double left = parts[0].length() == 1 ? 0 : number(parts[0].substring(1), input);
        double up = parts[1].length() == 1 ? 0 : number(parts[1].substring(1), input);
        double forwards = parts[2].length() == 1 ? 0 : number(parts[2].substring(1), input);
        double yaw = Math.toRadians(actor.yaw() + 90.0);
        double pitch = Math.toRadians(-actor.pitch());
        double pitchUp = Math.toRadians(-actor.pitch() + 90.0);
        double forwardX = Math.cos(yaw) * Math.cos(pitch);
        double forwardY = Math.sin(pitch);
        double forwardZ = Math.sin(yaw) * Math.cos(pitch);
        double upX = Math.cos(yaw) * Math.cos(pitchUp);
        double upY = Math.sin(pitchUp);
        double upZ = Math.sin(yaw) * Math.cos(pitchUp);
        // left = -(forward x up)
        double leftX = -(forwardY * upZ - forwardZ * upY);
        double leftY = -(forwardZ * upX - forwardX * upZ);
        double leftZ = -(forwardX * upY - forwardY * upX);
        return new double[] {
            origin.x() + 0.5 + forwardX * forwards + upX * up + leftX * left,
            origin.y() + forwardY * forwards + upY * up + leftY * left,
            origin.z() + 0.5 + forwardZ * forwards + upZ * up + leftZ * left,
        };
    }

    private static double number(String token, String input) {
        double value;
        try {
            value = Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw CommandRegistry.error("Expected a position like 10,64,-5 but got '" + input + "'");
        }
        if (!Double.isFinite(value)) {
            throw CommandRegistry.error("Expected a position like 10,64,-5 but got '" + input + "'");
        }
        return value;
    }

    /** Resolves a pattern argument using the session's global pattern as fallback. */
    /**
     * A vector argument: either the three components separated by commas or one
     * number repeated on all three axes, which is how WorldEdit reads
     * {@code //blob <pattern> <size> <radius>}.
     */
    public Vector3 vectorArg(int index, double fallback) {
        if (index >= positional.size()) {
            return new Vector3(fallback, fallback, fallback);
        }
        String[] parts = positional.get(index).split(",", -1);
        if (parts.length == 1) {
            double value = doubleArg(index);
            return new Vector3(value, value, value);
        }
        if (parts.length != 3) {
            throw CommandRegistry.error("Expected one value or x,y,z, got '"
                    + positional.get(index) + "'");
        }
        String what = "argument " + (index + 1);
        return new Vector3(Parsers.finiteArg(parts[0], what), Parsers.finiteArg(parts[1], what),
                Parsers.finiteArg(parts[2], what));
    }

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
        return Parsers.direction(arg(index));
    }

    public boolean boolArg(int index, boolean fallback) {
        if (index >= positional.size()) {
            return fallback;
        }
        String value = arg(index).toLowerCase(Locale.ROOT);
        return value.equals("true") || value.equals("yes") || value.equals("1");
    }
}
