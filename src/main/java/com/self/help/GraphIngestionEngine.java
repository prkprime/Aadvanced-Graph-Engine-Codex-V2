package com.self.help;

import com.self.help.context.GraphEngineContext;
import com.self.help.input.GraphMappingSchema;
import com.self.help.input.MappingSpec;
import com.self.help.input.MappingTargetType;
import com.self.help.legacy.IntegerColumnarStore;
import com.self.help.legacy.RawDataStore;
import com.self.help.output.GraphEdgeResponse;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.roaringbitmap.RoaringBitmap;

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

    public synchronized void ingest(int rowId) {
        String fromId = this.dataCube.getString(rowId, this.graphEngineContext.getFromIdColIndex());
        String toId = this.dataCube.getString(rowId, this.graphEngineContext.getToIdColIndex());

        boolean isFromNull = (fromId == null);
        boolean isToNull = (toId == null);

        if (isFromNull && isToNull) {
            System.out.println("Skipped row " + rowId + " because both FROM_ID and TO_ID are null.");
            return;
        }

        int nextInternalRowId = getIngestedRowCount();

        if (isFromNull) {
            this.graphEngineContext.getFromDeleted().add(nextInternalRowId);
            ingestWithMask(rowId, nextInternalRowId, this.graphEngineContext.getFromNullReadMask());
        } else if (isToNull) {
            this.graphEngineContext.getToDeleted().add(nextInternalRowId);
            ingestWithMask(rowId, nextInternalRowId, this.graphEngineContext.getToNullReadMask());
        } else {
            // Standard row: read all columns directly
            for (int i = 0; i < targetIndexToDataCubeIndex.length; i++) {
                encodeAndStore(i, this.dataCube.getString(rowId, targetIndexToDataCubeIndex[i]), nextInternalRowId);
            }
        }
    }

    /**
     * Ingests a partial row using the supplied read mask. For each target column index,
     * reads the value from the data cube only if that index is set in the mask; otherwise
     * stores null, meaning that side of the row is tombstoned.
     *
     * @param rowId           source row to read from the data cube
     * @param internalRowId   destination row in the encoded stores
     * @param readMask        bitmap of target column indices that should be read
     */
    private void ingestWithMask(int rowId, int internalRowId, RoaringBitmap readMask) {
        for (int i = 0; i < targetIndexToDataCubeIndex.length; i++) {
            String value = readMask.contains(i) ? this.dataCube.getString(rowId, targetIndexToDataCubeIndex[i]) : null;
            encodeAndStore(i, value, internalRowId);
        }
    }

    private void encodeAndStore(int colIndex, String value, int internalRowId) {
        int orEncode = biDirectionalDictionaries[colIndex].getOrEncode(value);
        invertedIndexColumns[colIndex].addRowToValue(orEncode, internalRowId);
        numericColumnarStores[colIndex].appendRow(internalRowId, orEncode);
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
     * Returns the graph engine context containing dictionaries, inverted indexes, and row tombstones.
     *
     * @return the graph engine context
     */
    public synchronized GraphEngineContext getGraphEngineContext() {
        return graphEngineContext;
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
            if (!this.graphEngineContext.getFromDeleted().contains(rowId)) {
                addDecodedVertex(dictionary, fromNodeIdStore, fromNodeLabelStore, nodeIdDictionary, nodeLabelDictionary, rowId);
            }
            if (!this.graphEngineContext.getToDeleted().contains(rowId)) {
                addDecodedVertex(dictionary, toNodeIdStore, toNodeLabelStore, nodeIdDictionary, nodeLabelDictionary, rowId);
            }
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
            String fromVertexId = this.graphEngineContext.getFromDeleted().contains(rowId) ? null : nodeIdDictionary.getValue(fromNodeIdStore.getInt(rowId));
            String toVertexId = this.graphEngineContext.getToDeleted().contains(rowId) ? null : nodeIdDictionary.getValue(toNodeIdStore.getInt(rowId));
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
        if (nodeId == null) {
            return;
        }
        String nodeLabel = nodeLabelDictionary.getValue(nodeLabelStore.getInt(rowId));
        dictionary.putIfAbsent(nodeId, nodeLabel);
    }

    /**
     * Resolves and returns the BiDirectionalDictionary for a given mapping target type and name
     * using the provided schema.
     *
     * @param schema     the graph mapping schema used to configure the engine
     * @param targetType the target type (ID, LABEL, ATTRIBUTE, or RELATION)
     * @param name       the attribute or relation column name (ignored for ID and LABEL)
     * @return the associated BiDirectionalDictionary
     * @throws IllegalArgumentException if the requested target is invalid or not found
     */
    @NotNull
    public BiDirectionalDictionary getDictionaryFor(
            @NotNull GraphMappingSchema schema,
            @NotNull MappingTargetType targetType,
            @Nullable String name) {
        
        return switch (targetType) {
            case ID -> graphEngineContext.getIdContext().getBiDirectionalDictionary();
            case LABEL -> graphEngineContext.getLabelContext().getBiDirectionalDictionary();
            case ATTRIBUTE -> {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Attribute name is required to lookup ATTRIBUTE dictionary");
                }
                int attrIndex = -1;
                for (int i = 0; i < schema.attributePairs().size(); i++) {
                    if (schema.attributePairs().get(i).attributeName().equalsIgnoreCase(name)) {
                        attrIndex = i;
                        break;
                    }
                }
                if (attrIndex == -1) {
                    throw new IllegalArgumentException("Attribute '" + name + "' not found in mapping schema");
                }
                yield graphEngineContext.getAttributesContext()[attrIndex].getBiDirectionalDictionary();
            }
            case RELATION -> {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Relation column name is required to lookup RELATION dictionary");
                }
                int relIndex = -1;
                for (int i = 0; i < schema.relationColumns().size(); i++) {
                    if (schema.relationColumns().get(i).equalsIgnoreCase(name)) {
                        relIndex = i;
                        break;
                    }
                }
                if (relIndex == -1) {
                    throw new IllegalArgumentException("Relation column '" + name + "' not found in mapping schema");
                }
                yield graphEngineContext.getRelationContext()[relIndex].getBiDirectionalDictionary();
            }
        };
    }
}
