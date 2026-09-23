package com.maxlananas.fawebim.core.tool;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.brush.BrushSettings;
import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.session.LocalSession;

import java.util.List;
import java.util.Locale;

/**
 * What the mouse wheel does while a brush is equipped — FAWE's {@code /tool scroll}.
 *
 * <p>The server never sees the wheel itself: the client only reports the hotbar
 * slot it moves to, which is what FAWE's platform listeners turn into a scroll of
 * one step. The Fabric adapter does the same, so the actions here are what the
 * wheel ends up driving.</p>
 */
public abstract class Scroll {

    /** The setting a scroll changes. */
    public enum Action {
        NONE,
        CLIPBOARD,
        MASK,
        PATTERN,
        TARGET_OFFSET,
        RANGE,
        SIZE,
        TARGET
    }

    private final Brush brush;

    protected Scroll(Brush brush) {
        this.brush = brush;
    }

    protected Brush brush() {
        return brush;
    }

    /** Applies one scroll step; false means "the tool cannot scroll". */
    public abstract boolean increment(int amount);

    /** Parses the action name the command line carries. */
    public static Action action(String name) {
        try {
            return Action.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String actions() {
        StringBuilder text = new StringBuilder();
        for (Action action : Action.values()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(action.name().toLowerCase(Locale.ROOT));
        }
        return text.toString();
    }

    /**
     * Builds the action for {@code /tool scroll <action> [arguments]}.
     *
     * <p>The arguments are the values the action cycles through: a mask list for
     * {@code mask}, a pattern list for {@code pattern}, the session clipboards for
     * {@code clipboard}.</p>
     */
    public static Scroll of(Action action, Brush brush, LocalSession session,
                            List<Mask> masks, List<Pattern> patterns) {
        return switch (action) {
            case NONE -> null;
            case SIZE -> new Size(brush, session);
            case RANGE -> new Range(brush, session);
            case MASK -> new Masks(brush, masks);
            case PATTERN -> new Patterns(brush, patterns);
            case TARGET -> new Target(brush);
            case TARGET_OFFSET -> new TargetOffset(brush);
            case CLIPBOARD -> new Clipboards(brush, session);
        };
    }

    /** Wraps a value into {@code [min, max]}, like FAWE's {@code MathMan.wrap}. */
    static int wrap(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        int span = max - min + 1;
        int wrapped = (value - min) % span;
        return min + (wrapped < 0 ? wrapped + span : wrapped);
    }

    private static final class Size extends Scroll {

        private final LocalSession session;

        private Size(Brush brush, LocalSession session) {
            super(brush);
            this.session = session;
        }

        @Override
        public boolean increment(int amount) {
            double max = session == null || session.getMaxBrushRadius() <= 0
                    ? 4095 : session.getMaxBrushRadius();
            double size = Math.max(0, Math.min(max, brush().radius() + amount));
            brush().setRadius(size);
            return true;
        }
    }

    private static final class Range extends Scroll {

        private final LocalSession session;

        private Range(Brush brush, LocalSession session) {
            super(brush);
            this.session = session;
        }

        @Override
        public boolean increment(int amount) {
            BrushSettings settings = brush().settings();
            int configured = session == null ? 0 : (int) session.getMaxBrushRange();
            int max = configured <= 0 ? (int) brush().radius() + 100 : configured;
            settings.setRange(wrap(settings.getRange() + amount, (int) brush().radius() + 1, max));
            return true;
        }
    }

    private static final class Masks extends Scroll {

        private final List<Mask> masks;
        private int index;

        private Masks(Brush brush, List<Mask> masks) {
            super(brush);
            this.masks = masks;
        }

        @Override
        public boolean increment(int amount) {
            if (masks.size() < 2) {
                return false;
            }
            index = wrap(index + amount, 0, masks.size() - 1);
            brush().setMask(masks.get(index));
            return true;
        }
    }

    private static final class Patterns extends Scroll {

        private final List<Pattern> patterns;
        private int index;

        private Patterns(Brush brush, List<Pattern> patterns) {
            super(brush);
            this.patterns = patterns;
        }

        @Override
        public boolean increment(int amount) {
            if (patterns.size() < 2) {
                return false;
            }
            index = wrap(index + amount, 0, patterns.size() - 1);
            brush().setFill(patterns.get(index));
            return true;
        }
    }

    private static final class Target extends Scroll {

        private Target(Brush brush) {
            super(brush);
        }

        @Override
        public boolean increment(int amount) {
            BrushSettings settings = brush().settings();
            int modes = ToolTarget.Mode.values().length;
            settings.setTargetMode(wrap(settings.getTargetMode() + amount, 0, modes - 1));
            return true;
        }
    }

    private static final class TargetOffset extends Scroll {

        private TargetOffset(Brush brush) {
            super(brush);
        }

        @Override
        public boolean increment(int amount) {
            BrushSettings settings = brush().settings();
            settings.setTargetOffset(settings.getTargetOffset() + amount);
            return true;
        }
    }

    private static final class Clipboards extends Scroll {

        private final LocalSession session;

        private Clipboards(Brush brush, LocalSession session) {
            super(brush);
            this.session = session;
        }

        @Override
        public boolean increment(int amount) {
            List<BlockArrayClipboard> pool = session == null ? List.of() : session.getClipboardPool();
            if (pool.size() < 2) {
                return false;
            }
            int index = wrap(pool.indexOf(current()) + amount, 0, pool.size() - 1);
            session.setClipboard(pool.get(index));
            return true;
        }

        private BlockArrayClipboard current() {
            return session != null && session.hasClipboard()
                    ? session.getClipboard().getClipboard() : null;
        }
    }
}
