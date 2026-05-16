package com.self.help.context;

import com.self.help.legacy.IntegerColumnarStore;
import com.self.help.legacy.RawDataStore;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Holds dictionary, encoded-value storage, inverted-index state, and raw-column
 * metadata for one relation column.
 * Relation values are encoded through the dictionary so ingestion and query paths
 * can work with compact integer ids instead of repeatedly comparing raw strings.
 */
@Getter(onMethod_ = {@NotNull})
public class RelationContext {
    /**
     * Dictionary used to encode relation values to dense integer ids and decode
     * them back to their original strings when results are hydrated.
     */
    private final BiDirectionalDictionary biDirectionalDictionary;

    /**
     * Zero-based column index in the {@link RawDataStore} for the relation
     * column represented by this context.
     */
    private final int indexFromDataCube;

    /**
     * Inverted index from encoded relation values to raw row ids.
     */
    private final InvertedIndexColumn invertedIndexColumn;

    /**
     * Encoded integer relation values, stored by row id.
     */
    private final IntegerColumnarStore relationIntegerColumnarStore;

    /**
     * Creates a relation context for one source relation column.
     * The context allocates a dictionary, inverted index, and integer column
     * store for relation values, then resolves the supplied relation column name
     * to its zero-based index in the raw data store.
     *
     * @param dataCube raw source store that owns the mapped relation column
     * @param relationName source column containing relation values
     */
    public RelationContext(@NotNull RawDataStore dataCube, @NotNull String relationName) {
        this.biDirectionalDictionary = new BiDirectionalDictionary();
        this.indexFromDataCube = dataCube.getColumnIndex(relationName);
        this.invertedIndexColumn = new InvertedIndexColumn();
        this.relationIntegerColumnarStore = new IntegerColumnarStore();
    }
}
