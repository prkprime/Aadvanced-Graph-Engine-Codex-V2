package com.self.help.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegerColumnarStoreTest {

    @Test
    void appendsAndReadsFromZeroCapacityStore() {
        IntegerColumnarStore store = new IntegerColumnarStore(0);

        store.appendRow(0, 42);

        assertEquals(42, store.getInt(0, 0));
        assertEquals(1, store.getRowCount());
    }

    @Test
    void growsPastInitialCapacity() {
        IntegerColumnarStore store = new IntegerColumnarStore(1);

        store.appendRow(0, 10);
        store.appendRow(1, 20);

        assertEquals(10, store.getInt(0, 0));
        assertEquals(20, store.getInt(1, 0));
        assertEquals(2, store.getRowCount());
    }

    @Test
    void rejectsInvalidInitialCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new IntegerColumnarStore(-1));
    }

    @Test
    void rejectsNonSequentialAppend() {
        IntegerColumnarStore store = new IntegerColumnarStore(1);

        assertThrows(IllegalArgumentException.class, () -> store.appendRow(1, 10));
    }

    @Test
    void rejectsOutOfRangeRows() {
        IntegerColumnarStore store = new IntegerColumnarStore(1);

        assertThrows(IndexOutOfBoundsException.class, () -> store.getInt(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> store.getInt(0, 0));
    }

    @Test
    void rejectsInvalidColumnIndex() {
        IntegerColumnarStore store = new IntegerColumnarStore(1);
        store.appendRow(0, 10);

        assertThrows(IndexOutOfBoundsException.class, () -> store.getInt(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> store.getInt(0, 1));
    }
}
