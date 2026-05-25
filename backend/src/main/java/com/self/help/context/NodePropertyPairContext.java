package com.self.help.context;

import com.self.help.legacy.IntegerColumnarStore;
import com.self.help.legacy.RawDataStore;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Holds dictionary, encoded-value storage, and inverted-index state for a paired node mapping.
 * A node context represents the same logical node property on the from-node and
 * to-node sides of an edge. It shares one dictionary across both sides while
 * keeping side-specific encoded columns and row indexes.
 */
@Getter(onMethod_ = {@NotNull})
public class NodePropertyPairContext {
    /**
     * Shared dictionary used to encode node values from both edge sides into
     * dense integer ids.
     */
    private final BiDirectionalDictionary biDirectionalDictionary;

    /**
     * Inverted index from encoded from-node values to raw row ids.
     */
    private final InvertedIndexColumn fromInvertedIndexColumn;

    /**
     * Encoded integer values for the from-node side, stored by row id.
     */
    private final IntegerColumnarStore fromIntegerColumnarStore;


    /**
     * Inverted index from encoded to-node values to raw row ids.
     */
    private final InvertedIndexColumn toInvertedIndexColumn;

    /**
     * Encoded integer values for the to-node side, stored by row id.
     */
    private final IntegerColumnarStore toIntegerColumnarStore;

    /**
     * Zero-based column index in the {@link RawDataStore} for the from-node
     * value represented by this context.
     */
    private final int fromDataCubeIndex;

    /**
     * Zero-based column index in the {@link RawDataStore} for the to-node value
     * represented by this context.
     */
    private final int toDataCubeIndex;

    /**
     * Creates a node context for a pair of source columns.
     * The context allocates a shared dictionary plus side-specific inverted
     * indexes and integer column stores, then resolves each supplied column name
     * to its zero-based index in the raw data store.
     *
     * @param dataCube   raw source store that owns the mapped node columns
     * @param fromColumn source column containing the from-node values
     * @param toColumn   source column containing the to-node values
     */
    public NodePropertyPairContext(@NotNull RawDataStore dataCube, @NotNull String fromColumn, @NotNull String toColumn) {
        this.biDirectionalDictionary = new BiDirectionalDictionary();
        this.fromInvertedIndexColumn = new InvertedIndexColumn();
        this.toInvertedIndexColumn = new InvertedIndexColumn();
        this.fromDataCubeIndex = dataCube.getColumnIndex(fromColumn);
        this.toDataCubeIndex = dataCube.getColumnIndex(toColumn);
        this.fromIntegerColumnarStore = new IntegerColumnarStore();
        this.toIntegerColumnarStore = new IntegerColumnarStore();
    }
}
