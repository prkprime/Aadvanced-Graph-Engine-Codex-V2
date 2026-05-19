package com.self.help;

import com.self.help.context.GraphEngineContext;
import com.self.help.input.MappingSpec;
import com.self.help.legacy.IntegerColumnarStore;
import com.self.help.legacy.RawDataStore;
import com.self.help.output.GraphEdgeResponse;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Returns how many rows have been ingested into the graph indexes.
     *
     * @return ingested row count
     */
    public synchronized int getIngestedRowCount() {
        return numericColumnarStores[0].getRowCount();
    }

    /**
     * Builds a vertex dictionary from the encoded graph stores.
     * The returned map is keyed by vertex id and contains the matching vertex
     * label as the value. The traversal reads the row-aligned integer stores and
     * decodes values through the graph dictionaries instead of scanning the raw
     * input data store.
     *
     * @return vertex id to vertex label mapping in first-seen order
     */
    public synchronized Map<String, String> getVertexDictionary() {
        Map<String, String> dictionary = new LinkedHashMap<>();
        int nodeColumnCount = 2 + graphEngineContext.getAttributesContext().length;
        int fromNodeIdStoreIndex = 0;
        int fromNodeLabelStoreIndex = 1;
        int toNodeIdStoreIndex = nodeColumnCount;
        int toNodeLabelStoreIndex = nodeColumnCount + 1;

        IntegerColumnarStore fromNodeIdStore = numericColumnarStores[fromNodeIdStoreIndex];
        IntegerColumnarStore fromNodeLabelStore = numericColumnarStores[fromNodeLabelStoreIndex];
        IntegerColumnarStore toNodeIdStore = numericColumnarStores[toNodeIdStoreIndex];
        IntegerColumnarStore toNodeLabelStore = numericColumnarStores[toNodeLabelStoreIndex];

        BiDirectionalDictionary nodeIdDictionary = biDirectionalDictionaries[fromNodeIdStoreIndex];
        BiDirectionalDictionary nodeLabelDictionary = biDirectionalDictionaries[fromNodeLabelStoreIndex];

        int rowCount = getIngestedRowCount();
        for (int rowId = 0; rowId < rowCount; rowId++) {
            addDecodedVertex(dictionary, fromNodeIdStore, fromNodeLabelStore, nodeIdDictionary, nodeLabelDictionary, rowId);
            addDecodedVertex(dictionary, toNodeIdStore, toNodeLabelStore, nodeIdDictionary, nodeLabelDictionary, rowId);
        }

        return dictionary;
    }

    /**
     * Builds row-wise edge responses from the encoded graph stores.
     * The returned list preserves ingestion order. Each edge contains decoded
     * from/to vertex ids and relation values in mapping-spec order.
     *
     * @return row-wise graph edges in ingestion order
     */
    public synchronized List<GraphEdgeResponse> getEdges() {
        int rowCount = getIngestedRowCount();
        List<GraphEdgeResponse> edges = new ArrayList<>(rowCount);
        int nodeColumnCount = 2 + graphEngineContext.getAttributesContext().length;
        int fromNodeIdStoreIndex = 0;
        int toNodeIdStoreIndex = nodeColumnCount;
        int relationStartStoreIndex = nodeColumnCount * 2;

        IntegerColumnarStore fromNodeIdStore = numericColumnarStores[fromNodeIdStoreIndex];
        IntegerColumnarStore toNodeIdStore = numericColumnarStores[toNodeIdStoreIndex];
        BiDirectionalDictionary nodeIdDictionary = biDirectionalDictionaries[fromNodeIdStoreIndex];

        for (int rowId = 0; rowId < rowCount; rowId++) {
            String fromVertexId = nodeIdDictionary.getValue(fromNodeIdStore.getInt(rowId));
            String toVertexId = nodeIdDictionary.getValue(toNodeIdStore.getInt(rowId));
            edges.add(new GraphEdgeResponse(fromVertexId, toVertexId, decodeRelationValues(rowId, relationStartStoreIndex)));
        }

        return List.copyOf(edges);
    }

    private List<String> decodeRelationValues(int rowId, int relationStartStoreIndex) {
        int relationCount = graphEngineContext.getRelationContext().length;
        List<String> relations = new ArrayList<>(relationCount);

        for (int relationIndex = 0; relationIndex < relationCount; relationIndex++) {
            int storeIndex = relationStartStoreIndex + relationIndex;
            int encodedValue = numericColumnarStores[storeIndex].getInt(rowId);
            relations.add(biDirectionalDictionaries[storeIndex].getValue(encodedValue));
        }

        return relations;
    }

    private void addDecodedVertex(
            Map<String, String> dictionary,
            IntegerColumnarStore nodeIdStore,
            IntegerColumnarStore nodeLabelStore,
            BiDirectionalDictionary nodeIdDictionary,
            BiDirectionalDictionary nodeLabelDictionary,
            int rowId) {
        String nodeId = nodeIdDictionary.getValue(nodeIdStore.getInt(rowId));
        String nodeLabel = nodeLabelDictionary.getValue(nodeLabelStore.getInt(rowId));
        dictionary.putIfAbsent(nodeId, nodeLabel);
    }
}
