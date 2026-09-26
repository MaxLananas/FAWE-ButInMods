package com.maxlananas.fawebim.core.util;

import java.util.Arrays;

/**
 * A first-in first-out queue of longs in a growing ring buffer.
 *
 * <p>The breadth-first walks over connected blocks queue packed positions:
 * an {@code ArrayDeque<BlockVector3>} holds an object per queued position,
 * this holds the long.</p>
 */
public final class LongQueue {

    private long[] values;
    private int head;
    private int size;

    public LongQueue() {
        this(64);
    }

    public LongQueue(int capacity) {
        this.values = new long[Integer.highestOneBit(Math.max(8, capacity - 1)) << 1];
    }

    public void add(long value) {
        if (size == values.length) {
            grow();
        }
        values[(head + size) & (values.length - 1)] = value;
        size++;
    }

    /** Takes the oldest value; the queue must not be empty. */
    public long poll() {
        if (size == 0) {
            throw new java.util.NoSuchElementException();
        }
        long value = values[head];
        head = (head + 1) & (values.length - 1);
        size--;
        return value;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    private void grow() {
        long[] grown = Arrays.copyOf(values, values.length << 1);
        // The part of the ring that wrapped around moves up to follow the rest.
        if (head > 0) {
            System.arraycopy(values, 0, grown, values.length, head);
            Arrays.fill(grown, 0, head, 0);
        }
        // Values sit at [head, head + size) in the grown array, without wrapping.
        values = grown;
    }
}
