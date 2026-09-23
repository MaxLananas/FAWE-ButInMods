package com.fawebutinmods.core.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks how long an operation has been running so the engine can honour
 * FAWE's {@code /timeout} and {@code /watchdog} settings.
 */
public final class TimeLimiter {

    private final long limitNanos;
    private final long startNanos;
    private final AtomicLong processed = new AtomicLong();

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
        processed.addAndGet(amount);
    }

    public long processed() {
        return processed.get();
    }

    public void check(long amount) throws OperationTimeoutException {
        count(amount);
        if (isExpired()) {
            throw new OperationTimeoutException(elapsedMillis(), processed());
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
