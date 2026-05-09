package com.self.help.legacy;

import java.util.Arrays;

/**
 * Compact single-column store for encoded integer row values.
 * Rows must be appended sequentially, and reads are guarded so callers cannot
 * access positions outside the committed row range.
 */
public class IntegerColumnarStore {
    /**
     * Capacity used when the store starts empty and receives its first row.
     */
    private static final int DEFAULT_INITIAL_ROW_CAPACITY = 1024;

    /**
     * Backing array that stores one encoded integer value per row.
     */
    private int[] intStorage;

    /**
     * Number of rows that have been appended and are safe to read.
     */
    private int rowCount;

    /**
     * Creates an empty integer store with the default initial row capacity.
     */
    public IntegerColumnarStore() {
        this(DEFAULT_INITIAL_ROW_CAPACITY);
    }

    /**
     * Creates an empty integer store with the requested initial row capacity.
     *
     * @param initialRowCapacity initial backing-array size
     * @throws IllegalArgumentException when the initial capacity is negative
     */
    public IntegerColumnarStore(int initialRowCapacity) {
        if (initialRowCapacity < 0) {
            throw new IllegalArgumentException("Initial row capacity cannot be negative.");
        }

        this.intStorage = new int[initialRowCapacity];
    }

    /**
     * Appends one encoded value at the next sequential row id.
     *
     * @param rowId expected row id, starting at {@code 0} and increasing by one
     * @param value encoded integer value to store
     * @throws IllegalArgumentException when {@code rowId} is not the next row id
     * @throws IllegalStateException when no more integer row ids can be stored
     */
    public synchronized void appendRow(int rowId, int value) {
        if (rowId != this.rowCount) {
            throw new IllegalArgumentException("Row ids must be appended sequentially starting at 0.");
        }

        if (this.rowCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("Cannot append more than Integer.MAX_VALUE rows.");
        }

        ensureCapacity(rowId + 1);
        this.intStorage[rowId] = value;
        this.rowCount++;
    }

    /**
     * Reads the encoded value for a stored row.
     *
     * @param rowId row id to read
     * @return encoded integer value for the row
     * @throws IndexOutOfBoundsException when {@code rowId} is outside the stored row range
     */
    public synchronized int getInt(int rowId) {
        validateRowIndex(rowId);
        return this.intStorage[rowId];
    }

    /**
     * Returns the number of encoded rows currently stored.
     *
     * @return encoded row count
     */
    public synchronized int getRowCount() {
        return this.rowCount;
    }

    /**
     * Returns a debug representation of the logical store contents.
     * Only appended rows are included; unused backing-array capacity is omitted.
     *
     * @return store summary with row count and committed integer values
     */
    @Override
    public synchronized String toString() {
        return "IntegerColumnarStore{" +
                "rowCount=" + rowCount +
                ", values=" + Arrays.toString(Arrays.copyOf(intStorage, rowCount)) +
                '}';
    }

    /**
     * Expands the backing array until it can hold the requested row count.
     *
     * @param requiredRowCapacity minimum required row capacity
     */
    private void ensureCapacity(int requiredRowCapacity) {
        if (requiredRowCapacity < 0) {
            throw new IllegalArgumentException("Required row capacity cannot be negative.");
        }

        int currentCapacity = this.intStorage.length;
        if (requiredRowCapacity <= currentCapacity) {
            return;
        }

        int newCapacity = currentCapacity == 0 ? DEFAULT_INITIAL_ROW_CAPACITY : currentCapacity;
        while (newCapacity < requiredRowCapacity) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                newCapacity = requiredRowCapacity;
                break;
            }
            newCapacity *= 2;
        }

        this.intStorage = Arrays.copyOf(this.intStorage, newCapacity);
    }

    /**
     * Ensures a row id points to an appended row.
     *
     * @param rowId row id to validate
     */
    private void validateRowIndex(int rowId) {
        if (rowId < 0 || rowId >= this.rowCount) {
            throw new IndexOutOfBoundsException("Row id " + rowId + " is outside stored row range [0, " + this.rowCount + ").");
        }
    }
}
