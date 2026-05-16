package com.self.help;

import com.self.help.context.GraphEngineContext;
import com.self.help.input.MappingSpec;
import com.self.help.legacy.IntegerColumnarStore;
import com.self.help.legacy.RawDataStore;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;

import static com.self.help.util.MappingSpecUtil.validateSpec;

/**
 * Ingests rows from a raw column store into a graph-oriented index structure.
 * The engine treats each ingested row as an edge with a from-node side, a
 * to-node side, and optional relation columns. During ingestion it dictionary
 * encodes mapped values, stores them in a numerical sidecar, and updates
 * RoaringBitmap-backed inverted indexes.
 */
public class GraphIngestionEngine {
    private final RawDataStore dataCube;
    private final GraphEngineContext graphEngineContext;
    private final int[] targetIndexToDataCubeIndex;

    private final BiDirectionalDictionary[] biDirectionalDictionaries;
    private final InvertedIndexColumn[] invertedIndexColumns;
    private final IntegerColumnarStore[] numericColumnarStores;

    /**
     * Builds an ingestion engine for the supplied raw column store and mapping.
     * The mapping is validated up front, and all source column positions, index
     * mappings, and engine-owned registries are precomputed so row ingestion
     * does not need repeated column-name lookups.
     *
     * @param dataCube raw source store that owns the string columns
     * @param spec     graph mapping that identifies from-node, to-node, and relation columns
     * @throws IllegalArgumentException when mapped columns are missing, attributes are mismatched,
     *                                  or from/to node specs share source columns
     */
    public GraphIngestionEngine(RawDataStore dataCube, MappingSpec spec) {
        validateSpec(dataCube, spec);
        this.dataCube = dataCube;
        graphEngineContext = new GraphEngineContext(dataCube, spec);
        numericColumnarStores = graphEngineContext.flatMapIntegerColumnarStores();

        biDirectionalDictionaries = graphEngineContext.flatMapBiDirectionalDictionaries();
        invertedIndexColumns = graphEngineContext.flatMapInvertedIndexColumns();
        targetIndexToDataCubeIndex = graphEngineContext.flatMapTargetIndexToDataCubeIndex();
    }

    /**
     * Ingests one raw row from the store used to construct this engine.
     *
     * @param rowId zero-based source row id to ingest
     */
    public synchronized void ingest(int rowId) {
        for (int i = 0; i < targetIndexToDataCubeIndex.length; i++) {
            String targetData = this.dataCube.getString(rowId, targetIndexToDataCubeIndex[i]);
            int orEncode = biDirectionalDictionaries[i].getOrEncode(targetData);
            invertedIndexColumns[i].addRowToValue(orEncode, rowId);
            numericColumnarStores[i].appendRow(rowId, orEncode);
        }
    }

    /**
     * Ingests one raw row, preserving the original two-argument API while
     * rejecting a store different from the one used to build the engine.
     *
     * @param rowId zero-based source row id to ingest
     * @param dataCube raw source store used to construct this engine
     * @throws IllegalArgumentException when a different raw store is supplied
     */
    public synchronized void ingest(int rowId, RawDataStore dataCube) {
        if (dataCube != this.dataCube) {
            throw new IllegalArgumentException("Rows must be ingested from the raw store used to construct this engine.");
        }
        ingest(rowId);
    }
}
