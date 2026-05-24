package com.self.help.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.roaringbitmap.RoaringBitmap;

/**
 * Stores one inverted index column from encoded dictionary values to row ids.
 * Each dictionary id owns a RoaringBitmap containing rows that have that value,
 * allowing fast candidate-row lookup and intersection for selective filters.
 */
public class InvertedIndexColumn {
    private static final int INITIAL_VALUE_CAPACITY = 64;

    private RoaringBitmap[] bitmaps;

    /**
     * Creates an empty dictionary-id to row-id inverted index.
     */
    public InvertedIndexColumn() {
        this.bitmaps = new RoaringBitmap[INITIAL_VALUE_CAPACITY];
    }

    /**
     * Adds a row id to the bitmap bucket for a dictionary id.
     * The internal bucket array expands automatically when the dictionary id
     * is beyond the current capacity.
     *
     * @param dictId encoded value id
     * @param rowId row id to associate with the encoded value
     */
    public void addRowToValue(int dictId, int rowId) {
        if (dictId < 0) {
            throw new IllegalArgumentException("Dictionary id cannot be negative.");
        }

        if (dictId >= bitmaps.length) {
            expand(dictId);
        }

        if (bitmaps[dictId] == null) {
            bitmaps[dictId] = new RoaringBitmap();
        }
        bitmaps[dictId].add(rowId);
    }

    private void expand(int requiredDictId) {
        int newCapacity = bitmaps.length;

        while (newCapacity <= requiredDictId) {
            newCapacity *= 2;
        }

        RoaringBitmap[] newArray = new RoaringBitmap[newCapacity];
        System.arraycopy(bitmaps, 0, newArray, 0, bitmaps.length);
        this.bitmaps = newArray;
    }

    /**
     * Returns the internal bitmap for a dictionary id, or {@code null} when no
     * rows are indexed for that id.
     * Note - this should be used in the query layer.
     * @param dictId encoded value id
     * @return internal bitmap for the id, or {@code null}
     */
    public @Nullable RoaringBitmap getRowsForValueOrNull(int dictId) {
        if (dictId < 0 || dictId >= bitmaps.length) {
            return null;
        }
        return bitmaps[dictId];
    }

    /**
     * Convenience alias for adding a row to an encoded value bucket.
     *
     * @param encodedValueId encoded value id
     * @param rowId row id to index
     */
    public void add(int encodedValueId, int rowId) {
        addRowToValue(encodedValueId, rowId);
    }

    /**
     * Removes a row id from the bitmap bucket for a dictionary id.
     *
     * @param dictId encoded value id
     * @param rowId  row id to remove association with the encoded value
     */
    public void removeRowFromValue(int dictId, int rowId) {
        if (dictId >= 0 && dictId < bitmaps.length && bitmaps[dictId] != null) {
            bitmaps[dictId].remove(rowId);
        }
    }
}
