package com.self.help.storage;

import org.roaringbitmap.RoaringBitmap;

/**
 * Stores one inverted index column from encoded dictionary values to row ids.
 * Each dictionary id owns a RoaringBitmap containing rows that have that value,
 * allowing fast candidate-row lookup and intersection for selective filters.
 */
public class InvertedIndexColumn {
    // Array index = Dict ID from Dictionary
    // Value = Bitmap of Row IDs
    private RoaringBitmap[] bitmaps;

    /**
     * Creates an empty dictionary-id to row-id inverted index.
     */
    public InvertedIndexColumn() {
        // Starting with 64 slots for unique values
        this.bitmaps = new RoaringBitmap[64];
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
        // Ensure the array can accommodate the Dict ID
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

        // Double the capacity until it can hold the required Dict ID
        while (newCapacity <= requiredDictId) {
            newCapacity *= 2;
        }

        RoaringBitmap[] newArray = new RoaringBitmap[newCapacity];
        // High-performance copy of the bitmap references
        System.arraycopy(bitmaps, 0, newArray, 0, bitmaps.length);
        this.bitmaps = newArray;
    }

    /**
     * Returns the bitmap of row ids for a dictionary id.
     * Missing dictionary ids return an empty bitmap. Existing dictionary ids
     * return the internal bitmap instance, so callers must not mutate it unless
     * they intentionally want to update the index.
     *
     * @param dictId encoded value id
     * @return bitmap containing matching row ids, or an empty bitmap when absent
     */
    public RoaringBitmap getRowsForValue(int dictId) {
        // If the query asks for a Dict ID outside current range, return empty
        if (dictId < 0 || dictId >= bitmaps.length || bitmaps[dictId] == null) {
            return new RoaringBitmap();
        }
        return bitmaps[dictId];
    }

    /**
     * Returns the internal bitmap for a dictionary id, or {@code null} when no
     * rows are indexed for that id.
     *
     * @param dictId encoded value id
     * @return internal bitmap for the id, or {@code null}
     */
    public RoaringBitmap getRowsForValueOrNull(int dictId) {
        if (dictId < 0 || dictId >= bitmaps.length) {
            return null;
        }
        return bitmaps[dictId];
    }

    /**
     * Returns a mutable copy of the bitmap for a dictionary id.
     *
     * @param dictId encoded value id
     * @return cloned bitmap for the id, or {@code null} when no rows are indexed
     */
    public RoaringBitmap copyRowsForValueOrNull(int dictId) {
        RoaringBitmap bitmap = getRowsForValueOrNull(dictId);
        return bitmap == null ? null : bitmap.clone();
    }

    /**
     * Intersects candidate rows with the rows indexed for a dictionary id.
     * The supplied bitmap is mutated in place.
     *
     * @param candidateRows candidate row bitmap to narrow
     * @param dictId encoded value id used as the filter
     * @return {@code true} when the intersection is non-empty; {@code false} otherwise
     */
    public boolean intersectInto(RoaringBitmap candidateRows, int dictId) {
        RoaringBitmap bitmap = getRowsForValueOrNull(dictId);
        if (bitmap == null) {
            return false;
        }
        candidateRows.and(bitmap);
        return !candidateRows.isEmpty();
    }

    /**
     * Convenience alias for adding a row to an encoded value bucket.
     *
     * @param encodedFromId encoded value id
     * @param rowId row id to index
     */
    public void add(int encodedFromId, int rowId) {
        addRowToValue(encodedFromId, rowId);
    }

    /**
     * Removes a row id from a dictionary-id bucket.
     * Empty buckets are cleared to {@code null} so later lookups can cheaply
     * distinguish absent values.
     *
     * @param dictId encoded value id
     * @param rowId row id to remove from the bucket
     */
    public void remove(int dictId, int rowId) {
        if (dictId < 0 || dictId >= bitmaps.length || bitmaps[dictId] == null) {
            return;
        }
        bitmaps[dictId].remove(rowId);
        if (bitmaps[dictId].isEmpty()) {
            bitmaps[dictId] = null;
        }
    }
}
