package com.self.help;

import com.self.help.context.GraphEngineContext;
import com.self.help.context.NodePropertyPairContext;
import com.self.help.context.RelationPropertyContext;
import com.self.help.input.GraphMappingSpec;
import com.self.help.enums.MappingTargetType;
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

import static com.self.help.util.GraphMappingSchemaValidator.validate;

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
    public GraphIngestionEngine(RawDataStore dataCube, GraphMappingSpec spec) {
        validate(dataCube, spec);
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
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        NodePropertyPairContext labelContext = graphEngineContext.getLabelContext();
        BiDirectionalDictionary idDict = idContext.getBiDirectionalDictionary();
        BiDirectionalDictionary labelDict = labelContext.getBiDirectionalDictionary();

        int numIds = idDict.size();
        int[] idToLabel = new int[numIds];
        java.util.Arrays.fill(idToLabel, -1);

        int rowCount = getIngestedRowCount();
        RoaringBitmap fromDeleted = graphEngineContext.getFromDeleted();
        RoaringBitmap toDeleted = graphEngineContext.getToDeleted();

        IntegerColumnarStore fromIdStore = idContext.getFromIntegerColumnarStore();
        IntegerColumnarStore fromLabelStore = labelContext.getFromIntegerColumnarStore();
        IntegerColumnarStore toIdStore = idContext.getToIntegerColumnarStore();
        IntegerColumnarStore toLabelStore = labelContext.getToIntegerColumnarStore();

        for (int rowId = 0; rowId < rowCount; rowId++) {
            mapLabelIfAbsent(fromDeleted, rowId, fromIdStore, numIds, idToLabel, fromLabelStore);
            mapLabelIfAbsent(toDeleted, rowId, toIdStore, numIds, idToLabel, toLabelStore);
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (int encodedId = 0; encodedId < numIds; encodedId++) {
            String nodeId = idDict.getValue(encodedId);
            if (nodeId != null) {
                int encodedLabel = idToLabel[encodedId];
                if (encodedLabel != -1) {
                    result.put(nodeId, labelDict.getValue(encodedLabel));
                }
            }
        }

        return result;
    }

    private static void mapLabelIfAbsent(RoaringBitmap deletedBitMap, int rowId, IntegerColumnarStore idStore, int numIds, int[] idToLabel, IntegerColumnarStore labelStore) {
        if (!deletedBitMap.contains(rowId)) {
            int encodedId = idStore.getInt(rowId);
            if (encodedId >= 0 && encodedId < numIds && idToLabel[encodedId] == -1) {
                idToLabel[encodedId] = labelStore.getInt(rowId);
            }
        }
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
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        BiDirectionalDictionary sharedIdDict = idContext.getBiDirectionalDictionary();

        for (int rowId = 0; rowId < rowCount; rowId++) {
            String fromVertexId = graphEngineContext.getFromDeleted().contains(rowId) ? null
                    : sharedIdDict.getValue(idContext.getFromIntegerColumnarStore().getInt(rowId));
            String toVertexId   = graphEngineContext.getToDeleted().contains(rowId)   ? null
                    : sharedIdDict.getValue(idContext.getToIntegerColumnarStore().getInt(rowId));
            edges.add(new GraphEdgeResponse(fromVertexId, toVertexId, decodeRelationValues(rowId)));
        }

        return List.copyOf(edges);
    }

    private List<String> decodeRelationValues(int rowId) {
        RelationPropertyContext[] relations = graphEngineContext.getRelations();
        List<String> result = new ArrayList<>(relations.length);
        for (RelationPropertyContext relation : relations) {
            result.add(relation.getBiDirectionalDictionary()
                    .getValue(relation.getRelationIntegerColumnarStore().getInt(rowId)));
        }
        return result;
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
            @NotNull GraphMappingSpec schema,
            @NotNull MappingTargetType targetType,
            @Nullable String name) {
        
        return switch (targetType) {
            case ID    -> graphEngineContext.getIdContext().getBiDirectionalDictionary();
            case LABEL -> graphEngineContext.getLabelContext().getBiDirectionalDictionary();
            case ATTRIBUTE -> {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Attribute name is required to lookup ATTRIBUTE dictionary");
                }
                int attrIndex = -1;
                for (int i = 0; i < schema.nodeAttributes().size(); i++) {
                    if (schema.nodeAttributes().get(i).attributeName().equalsIgnoreCase(name)) {
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
                for (int i = 0; i < schema.relations().size(); i++) {
                    if (schema.relations().get(i).columnName().equalsIgnoreCase(name)) {
                        relIndex = i;
                        break;
                    }
                }
                if (relIndex == -1) {
                    throw new IllegalArgumentException("Relation column '" + name + "' not found in mapping schema");
                }
                yield graphEngineContext.getRelations()[relIndex].getBiDirectionalDictionary();
            }
        };
    }
}
