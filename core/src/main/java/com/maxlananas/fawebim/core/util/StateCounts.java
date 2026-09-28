package com.maxlananas.fawebim.core.util;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * How many times each block state was seen.
 *
 * <p>A count per state id in an array, and the ids in the order they were
 * first seen: tallying the millions of blocks of a selection is an array
 * increment per block, with no boxed key or map entry, and reading the tally
 * back walks only the states that occurred. Counts are {@code long}s, since a
 * selection holds more blocks than an {@code int} counts.</p>
 *
 * <p>Not thread-safe: one tally belongs to one walk.</p>
 */
public final class StateCounts {

    private long[] counts;
    private int[] seen = new int[16];
    private int distinct;
    private long total;

    /**
     * @param stateCount how many state ids the registry has, so that the array
     *                   never grows; a larger id still fits, the array grows for it
     */
    public StateCounts(int stateCount) {
        this.counts = new long[Math.max(16, stateCount)];
    }

    /** Counts one block of a state. A negative id is no state and is not counted. */
    public void add(int state) {
        add(state, 1);
    }

    public void add(int state, long amount) {
        if (state < 0 || amount <= 0) {
            return;
        }
        if (state >= counts.length) {
            counts = Arrays.copyOf(counts, Math.max(state + 1, counts.length * 2));
        }
        if (counts[state] == 0) {
            if (distinct == seen.length) {
                seen = Arrays.copyOf(seen, distinct * 2);
            }
            seen[distinct++] = state;
        }
        counts[state] += amount;
        total += amount;
    }

    public long total() {
        return total;
    }

    /** How many different states were counted. */
    public int distinct() {
        return distinct;
    }

    /** The {@code index}-th state counted, in the order they were first seen. */
    public int state(int index) {
        return seen[index];
    }

    public long count(int state) {
        return state >= 0 && state < counts.length ? counts[state] : 0;
    }

    /**
     * The counts summed by a name of their state - the block name, which puts
     * the states of one block together, or the full state - in the order the
     * names were first seen. Each distinct state is named once.
     */
    public Map<String, Long> byName(IntFunction<String> naming) {
        Map<String, Long> named = new LinkedHashMap<>();
        for (int i = 0; i < distinct; i++) {
            named.merge(naming.apply(seen[i]), counts[seen[i]], Long::sum);
        }
        return named;
    }
}
