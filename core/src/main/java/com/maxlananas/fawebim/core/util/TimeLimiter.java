package com.maxlananas.fawebim.core.util;

/**
 * Tracks how long an operation has been running so the engine can honour
 * FAWE's {@code /timeout} and {@code /watchdog} settings.
 *
 * <p>The counter is a plain field and the clock is read once in a while: an edit
 * counts every block it touches, so an atomic increment and a {@code nanoTime}
 * call per block would cost more than the write itself. The limiter belongs to
 * the thread running the edit.</p>
 */
public final class TimeLimiter {

    /** How many counts pass between two readings of the clock. */
    private static final int CLOCK_INTERVAL_MASK = 0x1FF;

    private final long limitNanos;
    private final long startNanos;
    private long processed;
    private int checks;

    public TimeLimiter(long limitMillis) {
        this.limitNanos = limitMillis <= 0 ? Long.MAX_VALUE : limitMillis * 1_000_000L;
        this.startNanos = System.nanoTime();
    }

    public static TimeLimiter unlimited() {
        return new TimeLimiter(0);
    }

    public boolean isExpired() {
        return limitNanos != Long.MAX_VALUE && (System.nanoTime() - startNanos) > limitNanos;
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public void count(long amount) {
        processed += amount;
    }

    public long processed() {
        return processed;
    }

    public void check(long amount) throws OperationTimeoutException {
        processed += amount;
        if ((++checks & CLOCK_INTERVAL_MASK) != 0) {
            return;
        }
        if (isExpired()) {
            throw new OperationTimeoutException(elapsedMillis(), processed);
        }
    }

    /** Thrown when an edit exceeds the configured timeout. */
    public static final class OperationTimeoutException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final long elapsedMillis;
        private final long processed;

        public OperationTimeoutException(long elapsedMillis, long processed) {
            super("Operation exceeded the configured timeout after " + elapsedMillis + "ms (" + processed + " blocks)");
            this.elapsedMillis = elapsedMillis;
            this.processed = processed;
        }

        public long elapsedMillis() {
            return elapsedMillis;
        }

        public long processed() {
            return processed;
        }
    }
}
