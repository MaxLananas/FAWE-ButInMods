package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.world.ChunkSet;
import com.maxlananas.fawebim.core.world.PackedBlockArray;

import java.util.Random;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/** The buffers an edit is written into, against a plain array doing the same. */
final class DataStructureTests {

    private DataStructureTests() {
    }

    static void run() {
        section("data structures");
        paletteOutlivesItsDeadStates();
        paletteMatchesAPlainArray();
        biomeOutsideTheChunkLeavesItClean();
    }

    /**
     * A section buffer keeps every state it was ever given in its palette. A
     * section holds 4096 cells, so 4096 distinct states can live in it at once,
     * but a buffer that is written over and over collects more than that, and
     * the 4097th state used to throw out of the palette array mid-edit.
     */
    private static void paletteOutlivesItsDeadStates() {
        PackedBlockArray one = new PackedBlockArray(4);
        boolean survived = true;
        try {
            for (int state = 1; state <= 10_000; state++) {
                one.put(0, state);
            }
        } catch (RuntimeException e) {
            survived = false;
        }
        check("one cell written with 10,000 states in turn keeps working", survived);
        checkEquals("the cell holds the last state", 10_000, one.get(0));

        PackedBlockArray full = new PackedBlockArray(4);
        for (int cell = 0; cell < PackedBlockArray.VOLUME; cell++) {
            full.put(cell, 100 + cell);
        }
        // 4096 live states and a 4097th: the cell it replaces frees a slot.
        checkEquals("a new state over a full palette is a change", 1, full.put(17, 99_999));
        checkEquals("the new state is stored", 99_999, full.get(17));
        boolean kept = true;
        for (int cell = 0; cell < PackedBlockArray.VOLUME; cell++) {
            if (cell != 17 && full.get(cell) != 100 + cell) {
                kept = false;
            }
        }
        check("every other cell keeps its state through the compaction", kept);
        checkEquals("writing the same state again is not a change", -1, full.put(17, 99_999));
    }

    /** Random writes, random states, a wide palette: the same answers as an int[]. */
    private static void paletteMatchesAPlainArray() {
        Random random = new Random(42);
        for (int round = 0; round < 4; round++) {
            PackedBlockArray packed = new PackedBlockArray(1);
            int[] plain = new int[PackedBlockArray.VOLUME];
            boolean[] written = new boolean[PackedBlockArray.VOLUME];
            int states = round == 0 ? 3 : round == 1 ? 300 : round == 2 ? 5000 : 60_000;
            boolean same = true;
            for (int step = 0; step < 40_000; step++) {
                int cell = random.nextInt(PackedBlockArray.VOLUME);
                int state = random.nextInt(states);
                int expected = !written[cell] ? 0 : plain[cell] == state ? -1 : 1;
                int answer = packed.put(cell, state);
                if (answer != expected) {
                    same = false;
                }
                plain[cell] = state;
                written[cell] = true;
            }
            for (int cell = 0; cell < PackedBlockArray.VOLUME; cell++) {
                if (packed.isWritten(cell) != written[cell] || (written[cell] && packed.get(cell) != plain[cell])) {
                    same = false;
                }
            }
            check("a packed section answers like an int[] with " + states + " states", same);
        }
    }

    /** A biome outside the buffer's sections is not a change to apply. */
    private static void biomeOutsideTheChunkLeavesItClean() {
        ChunkSet set = new ChunkSet(0, 0, 0, 255);
        set.setBiome(0, 400, 0, 3, 0);
        check("a biome above the chunk leaves the buffer empty", set.isEmpty());
        checkEquals("a biome above the chunk is not stored", -1, set.getBiome(0, 400, 0));
    }
}
