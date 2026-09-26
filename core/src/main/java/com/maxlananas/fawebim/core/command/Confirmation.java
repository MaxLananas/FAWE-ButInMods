package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.util.Msg;

import java.util.concurrent.TimeUnit;

/**
 * FAWE's {@code @Confirm}: a command stops and asks for {@code //confirm} before
 * an edit that is more likely a slip than a plan - a selection over a large
 * area, more undos than anyone types on purpose, a rollback of the server's
 * history.
 *
 * <p>FAWE holds the command's thread until the actor confirms, fifteen seconds
 * at most. Commands here run on the server thread, which cannot wait, so the
 * dispatcher parks the command line on the session instead and
 * {@code //confirm} runs it again with the checks passed: the actor reads the
 * same lines and the edit is the same.</p>
 *
 * <p>The checks are {@link Ctx#confirmRegion}, {@link Ctx#confirmCount} and
 * {@link Ctx#confirmAlways}, which throw {@link Required}; a command declared
 * with {@link CommandRegistry.Entry#confirmRegion} goes through the first one
 * before its handler runs.</p>
 */
public final class Confirmation {

    /**
     * FAWE's limit on the columns a selection spans, times the copies a command
     * makes of it: {@code 2 << 18}, a square about 724 blocks wide.
     */
    public static final long MAX_AREA = 2L << 18;

    /** FAWE's limit on a count, such as the steps of {@code //undo}. */
    public static final long MAX_COUNT = 50;

    /** How long a parked command waits for {@code //confirm}, as long as FAWE waits. */
    public static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(15);

    private Confirmation() {
    }

    /** Stops a command that has to be confirmed first; the dispatcher parks it. */
    public static final class Required extends RuntimeException {

        private final String reason;

        Required(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        /** The line that asks: why, then how to run the command anyway. */
        public Msg prompt(String line) {
            return Msg.warn((reason == null ? "" : reason + ". ") + "Use //confirm to execute " + line);
        }
    }
}
