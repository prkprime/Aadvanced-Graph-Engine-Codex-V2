package com.self.help;

import com.self.help.context.GraphEngineContext;
import com.self.help.context.NodePropertyPairContext;
import com.self.help.context.RelationPropertyContext;
import com.self.help.input.GraphMappingSpec;
import com.self.help.enums.MappingTargetType;
import com.self.help.enums.TraversalDirection;
import com.self.help.legacy.IntegerColumnarStore;
import com.self.help.legacy.RawDataStore;
import com.self.help.output.GraphEdgeResponse;
import com.self.help.output.GraphNodeStats;
import com.self.help.output.VertexAttributesResponse;
import com.self.help.output.GraphSchemaResponse;
import com.self.help.output.KNeighborsResponse;
import com.self.help.output.VertexDetailsResponse;
import com.self.help.input.NodePropertyMappingSpec;
import com.self.help.input.RelationPropertyMappingSpec;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Set;
import java.util.LinkedHashSet;

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
     * @param rowId         source row to read from the data cube
     * @param internalRowId destination row in the encoded stores
     * @param readMask      bitmap of target column indices that should be read
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
     * @param rowId    zero-based source row id to ingest
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
     * The returned map is keyed by the compact numeric vertex id (the integer
     * assigned by the {@link BiDirectionalDictionary} during ingestion) and
     * maps to the display label. The traversal reads the row-aligned integer
     * stores rather than scanning the raw input data store.
     *
     * <p>The numeric id is the stable primary key exposed to the UI. To resolve
     * the original source string for a numeric id, use {@link #getSourceId(int)}.
     *
     * @return numeric vertex id to vertex label mapping in dictionary-assignment order
     */
    public synchronized Map<Integer, String> getVertexDictionary() {
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

        Map<Integer, String> result = new LinkedHashMap<>();
        for (int encodedId = 0; encodedId < numIds; encodedId++) {
            int encodedLabel = idToLabel[encodedId];
            if (encodedLabel != -1) {
                result.put(encodedId, labelDict.getValue(encodedLabel));
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

        for (int rowId = 0; rowId < rowCount; rowId++) {
            Integer fromVertexId = graphEngineContext.getFromDeleted().contains(rowId) ? null
                    : idContext.getFromIntegerColumnarStore().getInt(rowId);
            Integer toVertexId = graphEngineContext.getToDeleted().contains(rowId) ? null
                    : idContext.getToIntegerColumnarStore().getInt(rowId);
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
            case ID -> graphEngineContext.getIdContext().getBiDirectionalDictionary();
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

    /**
     * Resolves and returns the string display label for a given vertex numeric ID
     * by looking up the first valid non-deleted occurrence in the ingested stores.
     *
     * @param numericId encoded integer id of the vertex
     * @return the resolved display label string, or null if id does not exist
     */
    @Nullable
    public synchronized String getResolvedVertexLabel(int numericId) {
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        if (numericId < 0 || numericId >= idContext.getBiDirectionalDictionary().size()) {
            return null;
        }

        NodePropertyPairContext labelContext = graphEngineContext.getLabelContext();
        BiDirectionalDictionary labelDict = labelContext.getBiDirectionalDictionary();

        // 1. Check from-side occurrences using getRowsForValueOrNull
        RoaringBitmap fromRows = idContext.getFromInvertedIndexColumn().getRowsForValueOrNull(numericId);
        String label = resolveLabel(fromRows, graphEngineContext.getFromDeleted(), labelContext.getFromIntegerColumnarStore(), labelDict);
        if (label != null) {
            return label;
        }

        // 2. Check to-side occurrences using getRowsForValueOrNull
        RoaringBitmap toRows = idContext.getToInvertedIndexColumn().getRowsForValueOrNull(numericId);
        return resolveLabel(toRows, graphEngineContext.getToDeleted(), labelContext.getToIntegerColumnarStore(), labelDict);
    }

    /**
     * Resolves the compact numeric vertex id back to its original source string.
     * Performs an O(1) bounds-checked dictionary lookup.
     *
     * @param numericId compact integer id assigned by the dictionary during ingestion
     * @return the original source string that was ingested, or {@code null} if the id is out of range
     */
    @Nullable
    public synchronized String getSourceId(int numericId) {
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        if (numericId < 0 || numericId >= idContext.getBiDirectionalDictionary().size()) {
            return null;
        }
        return idContext.getBiDirectionalDictionary().getValue(numericId);
    }

    /**
     * Resolves a source string back to its compact numeric vertex id.
     * Performs a non-mutating dictionary lookup — the source string must have been
     * ingested previously, otherwise {@code null} is returned.
     *
     * @param sourceId original source string to look up
     * @return compact numeric vertex id, or {@code null} if the source string was never ingested
     */
    @Nullable
    public synchronized Integer getNumericIdBySourceId(@NotNull String sourceId) {
        int id = graphEngineContext.getIdContext().getBiDirectionalDictionary().getIdIfExists(sourceId);
        return id == -1 ? null : id;
    }


    private String resolveLabel(
            @Nullable RoaringBitmap rows,
            @NotNull RoaringBitmap deleted,
            @NotNull IntegerColumnarStore labelStore,
            @NotNull BiDirectionalDictionary labelDict) {
        if (rows != null && !rows.isEmpty()) {
            RoaringBitmap active = RoaringBitmap.andNot(rows, deleted);
            int rowId = !active.isEmpty() ? active.first() : rows.first();
            int encodedLabel = labelStore.getInt(rowId);
            return labelDict.getValue(encodedLabel);
        }
        return null;
    }

    /**
     * Retrieves display label and ordered attribute lists for a vertex.
     * Searches all active occurrences of this vertex (whether on the from or to side)
     * and decodes attribute values from the columnar store in mapping order.
     *
     * @param numericId encoded integer id of the vertex
     * @return response with the vertex label and attribute lists
     */
    @Nullable
    public synchronized VertexAttributesResponse getVertexAttributes(int numericId) {
        String resolvedId = getSourceId(numericId);
        if(resolvedId == null)
            return null;
        String resolvedLabel = getResolvedVertexLabel(numericId);
        if(resolvedLabel == null)
            return null;

        if (graphEngineContext.getAttributesContext().length == 0) {
            return new VertexAttributesResponse(resolvedId, resolvedLabel, List.of());
        }

        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        RoaringBitmap activeFrom = getActiveRows(idContext.getFromInvertedIndexColumn().getRowsForValueOrNull(numericId), graphEngineContext.getFromDeleted());
        RoaringBitmap activeTo = getActiveRows(idContext.getToInvertedIndexColumn().getRowsForValueOrNull(numericId), graphEngineContext.getToDeleted());

        if (activeFrom.isEmpty() && activeTo.isEmpty()) {
            return new VertexAttributesResponse(resolvedId, resolvedLabel, List.of());
        }

        List<List<String>> uniqueAttributes = java.util.stream.Stream.concat(
                java.util.Arrays.stream(activeFrom.toArray()).mapToObj(rowId -> getAttributes(rowId, true)),
                java.util.Arrays.stream(activeTo.toArray()).mapToObj(rowId -> getAttributes(rowId, false))
        )
        .distinct()
        .toList();

        return new VertexAttributesResponse(resolvedId, resolvedLabel, uniqueAttributes);
    }

    private RoaringBitmap getActiveRows(@Nullable RoaringBitmap rows, @NotNull RoaringBitmap deleted) {
        return (rows == null || rows.isEmpty()) ? new RoaringBitmap() : RoaringBitmap.andNot(rows, deleted);
    }

    private List<String> getAttributes(int rowId, boolean isFromSide) {
        NodePropertyPairContext[] attrContexts = graphEngineContext.getAttributesContext();
        List<String> attrs = new ArrayList<>(attrContexts.length);
        for (NodePropertyPairContext attrContext : attrContexts) {
            IntegerColumnarStore store = isFromSide
                    ? attrContext.getFromIntegerColumnarStore()
                    : attrContext.getToIntegerColumnarStore();
            int encodedVal = store.getInt(rowId);
            String val = attrContext.getBiDirectionalDictionary().getValue(encodedVal);
            attrs.add(val);
        }
        return attrs;
    }

    /**
     * Computes per-vertex edge statistics for every known vertex in the graph.
     * <p>
     * For each vertex numeric id, the method counts active outgoing edges
     * (rows where that vertex appears on the FROM side and neither side is tombstoned)
     * and active incoming edges (rows where that vertex appears on the TO side
     * and neither side is tombstoned). A row is live only when absent from both
     * {@code fromDeleted} and {@code toDeleted}.
     * <p>
     * The returned map is keyed by the compact numeric vertex id assigned by
     * the {@link BiDirectionalDictionary} during ingestion.
     *
     * @return map from numeric vertex id to its {@link GraphNodeStats}, in dictionary-assignment order
     */
    @NotNull
    public synchronized Map<Integer, GraphNodeStats> getVertexStats() {
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        int numIds = idContext.getBiDirectionalDictionary().size();

        Map<Integer, GraphNodeStats> result = new LinkedHashMap<>(numIds);
        for (int vertexId = 0; vertexId < numIds; vertexId++) {
            GraphNodeStats stats = getVertexStats(vertexId);
            if (stats != null) {
                result.put(vertexId, stats);
            }
        }
        return result;
    }

    /**
     * Computes edge statistics for a specific vertex in the graph.
     * <p>
     * Counts active outgoing edges (rows where that vertex appears on the FROM side
     * and neither side of the row is tombstoned) and active incoming edges (rows where
     * that vertex appears on the TO side and neither side is tombstoned). A row is
     * considered live only when it is absent from both {@code fromDeleted} and
     * {@code toDeleted} — a row whose opposite side was tombstoned by a neighbour
     * deletion is a dead edge and must not be counted.
     *
     * @param vertexId encoded integer id of the vertex
     * @return {@link GraphNodeStats} for the vertex, or {@code null} if the vertex id is out of bounds
     */
    @Nullable
    public synchronized GraphNodeStats getVertexStats(int vertexId) {
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        if (vertexId < 0 || vertexId >= idContext.getBiDirectionalDictionary().size()
                || getResolvedVertexLabel(vertexId) == null) {
            return null;
        }

        InvertedIndexColumn fromIndex = idContext.getFromInvertedIndexColumn();
        InvertedIndexColumn toIndex = idContext.getToInvertedIndexColumn();
        // A row is dead when EITHER side is tombstoned, so mask against the union.
        RoaringBitmap eitherDeleted = RoaringBitmap.or(
                graphEngineContext.getFromDeleted(),
                graphEngineContext.getToDeleted());

        int outgoing = activeCount(fromIndex.getRowsForValueOrNull(vertexId), eitherDeleted);
        int incoming = activeCount(toIndex.getRowsForValueOrNull(vertexId), eitherDeleted);
        return new GraphNodeStats(outgoing, incoming);
    }

    /**
     * Returns the number of rows present in {@code rows} but absent from {@code deleted}.
     *
     * @param rows    bitmap of candidate row ids, or {@code null} when none exist
     * @param deleted tombstone bitmap to subtract from the candidate set
     * @return active row count
     */
    private static int activeCount(@Nullable RoaringBitmap rows, @NotNull RoaringBitmap deleted) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return (int) RoaringBitmap.andNot(rows, deleted).getLongCardinality();
    }

    /**
     * Generates the schema metadata for the active graph.
     *
     * @return structural and statistical schema metadata
     * @deprecated Marked as deprecated temporarily for review of how the UI consumes this contract.
     */
    @Deprecated
    @NotNull
    public synchronized GraphSchemaResponse getSchema() {
        GraphMappingSpec spec = graphEngineContext.getMappingSpec();
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        NodePropertyPairContext labelContext = graphEngineContext.getLabelContext();

        var idPairSchema = new GraphSchemaResponse.IdPairSchema(
                spec.idPair().fromColumnName(),
                spec.idPair().toColumnName(),
                idContext.getBiDirectionalDictionary().size()
        );

        var labelPairSpec = spec.labelPair() != null ? spec.labelPair() : spec.idPair();
        var labelPairSchema = new GraphSchemaResponse.LabelPairSchema(
                labelPairSpec.fromColumnName(),
                labelPairSpec.toColumnName(),
                labelContext.getBiDirectionalDictionary().size()
        );

        List<GraphSchemaResponse.AttributeSchema> attributes = new ArrayList<>();
        NodePropertyPairContext[] attributeContexts = graphEngineContext.getAttributesContext();
        for (int i = 0; i < spec.nodeAttributes().size(); i++) {
            NodePropertyMappingSpec attrSpec = spec.nodeAttributes().get(i);
            int uniqueValCount = attributeContexts[i].getBiDirectionalDictionary().size();
            attributes.add(new GraphSchemaResponse.AttributeSchema(
                    attrSpec.attributeName(),
                    attrSpec.fromColumnName(),
                    attrSpec.toColumnName(),
                    uniqueValCount
            ));
        }

        List<GraphSchemaResponse.RelationSchema> relations = new ArrayList<>();
        RelationPropertyContext[] relationContexts = graphEngineContext.getRelations();
        for (int i = 0; i < spec.relations().size(); i++) {
            RelationPropertyMappingSpec relSpec = spec.relations().get(i);
            int uniqueValCount = relationContexts[i].getBiDirectionalDictionary().size();
            relations.add(new GraphSchemaResponse.RelationSchema(
                    relSpec.columnName(),
                    uniqueValCount
            ));
        }

        var storageMetricsSchema = new GraphSchemaResponse.StorageMetricsSchema(
                getIngestedRowCount(),
                idContext.getBiDirectionalDictionary().size()
        );

        return new GraphSchemaResponse(
                idPairSchema,
                labelPairSchema,
                attributes,
                relations,
                storageMetricsSchema
        );
    }

    /**
     * Traverses the graph up to K hops starting from the specified vertex numeric ID,
     * filtering by directed/undirected traversal paths. Returns a subgraph structure
     * detailing all reached vertices and their connecting edges.
     *
     * @param startVertexId encoded integer ID of the starting vertex
     * @param k             maximum hop count for neighborhood expansion
     * @param direction     directional filter (OUTGOING, INCOMING, or BOTH)
     * @return the K-hop neighborhood subgraph
     * @throws IllegalArgumentException if startVertexId is invalid or out of bounds
     */
    @NotNull
        public synchronized KNeighborsResponse getKNeighbors(int startVertexId, int k, @NotNull TraversalDirection direction) {
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        if (startVertexId < 0 || startVertexId >= idContext.getBiDirectionalDictionary().size() 
                || getResolvedVertexLabel(startVertexId) == null) {
            throw new IllegalArgumentException("Starting vertex ID " + startVertexId + " is invalid or has been soft-deleted.");
        }

        if (k < 0) {
            throw new IllegalArgumentException("Hop count K cannot be negative.");
        }

        Set<Integer> visited = new LinkedHashSet<>();
        Set<Integer> discoveredEdges = new LinkedHashSet<>();
        visited.add(startVertexId);

        if (k > 0) {
            Queue<Integer> queue = new LinkedList<>();
            queue.add(startVertexId);

            for (int depth = 0; depth < k; depth++) {
                int levelSize = queue.size();
                if (levelSize == 0) {
                    break;
                }

                for (int i = 0; i < levelSize; i++) {
                    int v = queue.poll();

                    // 1. Process OUTGOING edges (treating v as source/from)
                    if (direction == TraversalDirection.OUTGOING || direction == TraversalDirection.BOTH) {
                        RoaringBitmap fromRows = idContext.getFromInvertedIndexColumn().getRowsForValueOrNull(v);
                        if (fromRows != null && !fromRows.isEmpty()) {
                            RoaringBitmap activeRows = RoaringBitmap.andNot(fromRows, graphEngineContext.getFromDeleted());
                            activeRows.andNot(graphEngineContext.getToDeleted());
                            for (int rowId : activeRows) {
                                discoveredEdges.add(rowId);
                                int targetId = idContext.getToIntegerColumnarStore().getInt(rowId);
                                if (visited.add(targetId)) {
                                    queue.add(targetId);
                                }
                            }
                        }
                    }

                    // 2. Process INCOMING edges (treating v as target/to)
                    if (direction == TraversalDirection.INCOMING || direction == TraversalDirection.BOTH) {
                        RoaringBitmap toRows = idContext.getToInvertedIndexColumn().getRowsForValueOrNull(v);
                        if (toRows != null && !toRows.isEmpty()) {
                            RoaringBitmap activeRows = RoaringBitmap.andNot(toRows, graphEngineContext.getToDeleted());
                            activeRows.andNot(graphEngineContext.getFromDeleted());
                            for (int rowId : activeRows) {
                                discoveredEdges.add(rowId);
                                int sourceId = idContext.getFromIntegerColumnarStore().getInt(rowId);
                                if (visited.add(sourceId)) {
                                    queue.add(sourceId);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Build vertices dictionary map
        Map<Integer, String> vertices = new LinkedHashMap<>(visited.size());
        for (int vid : visited) {
            String label = getResolvedVertexLabel(vid);
            if (label != null) {
                vertices.put(vid, label);
            }
        }

        // Build edges list (deduplicated to prevent duplicate identical edge drawings in the UI)
        Set<GraphEdgeResponse> uniqueEdges = new LinkedHashSet<>();
        for (int rowId : discoveredEdges) {
            Integer fromVertexId = graphEngineContext.getFromDeleted().contains(rowId) ? null
                    : idContext.getFromIntegerColumnarStore().getInt(rowId);
            Integer toVertexId = graphEngineContext.getToDeleted().contains(rowId) ? null
                    : idContext.getToIntegerColumnarStore().getInt(rowId);
            uniqueEdges.add(new GraphEdgeResponse(fromVertexId, toVertexId, decodeRelationValues(rowId)));
        }

        return new KNeighborsResponse(vertices, List.copyOf(uniqueEdges));
    }

    /**
     * Resolves and returns the detailed representation of a specific vertex by its numeric ID.
     * Checks if the vertex is active (has a valid source ID and label).
     *
     * @param vertexId the numeric integer ID of the vertex
     * @return the {@link VertexDetailsResponse} if active, or {@code null} if the vertex is invalid or inactive
     */
    @Nullable
    public synchronized VertexDetailsResponse getVertexDetails(int vertexId) {
        String sourceId = getSourceId(vertexId);
        String label = getResolvedVertexLabel(vertexId);
        if (sourceId != null && label != null) {
            return new VertexDetailsResponse(vertexId, sourceId, label);
        }
        return null;
    }

    /**
     * Resolves and returns the detailed representation of the first active vertex
     * loaded in the graph engine. Checks vertices sequentially by numeric ID to find
     * the first non-deleted occurrence.
     *
     * @return the first available {@link VertexDetailsResponse}, or {@code null} if the graph is empty
     */
    @Nullable
    public synchronized VertexDetailsResponse getFirstVertexDetails() {
        return getNextVertexDetails(-1);
    }

    /**
     * Resolves and returns the detailed representation of the next active vertex
     * following the specified vertex ID, returning {@code null} if the end of the vertex range is reached
     * (i.e. if it is the last active node).
     *
     * @param currentVertexId the numeric integer ID of the current vertex (use -1 to search from the beginning)
     * @return the next available {@link VertexDetailsResponse}, or {@code null} if no further active nodes exist
     */
    @Nullable
    public synchronized VertexDetailsResponse getNextVertexDetails(int currentVertexId) {
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        int numIds = idContext.getBiDirectionalDictionary().size();
        if (numIds == 0) {
            return null;
        }

        for (int vertexId = currentVertexId + 1; vertexId < numIds; vertexId++) {
            VertexDetailsResponse details = getVertexDetails(vertexId);
            if (details != null) {
                return details;
            }
        }
        return null;
    }

    /**
     * Soft-deletes a vertex according to the parameters in the {@link com.self.help.input.VertexDeleteRequest}.
     * Supports optional recursive cascades along active downstream and upstream edges.
     * Soft-tombstones connected edges by nullifying the deleted node's side in those rows,
     * converting them into single-vertex active rows.
     *
     * @param request deletion configuration payload
     * @return true if at least one active vertex was successfully soft-deleted, false otherwise
     */
    private java.util.Set<Integer> collectVerticesToDelete(@NotNull com.self.help.input.VertexDeleteRequest request) {
        Integer rootId = request.nodeId();
        if (rootId == null) {
            return java.util.Collections.emptySet();
        }

        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        int numIds = idContext.getBiDirectionalDictionary().size();
        if (rootId < 0 || rootId >= numIds || getResolvedVertexLabel(rootId) == null) {
            return java.util.Collections.emptySet();
        }

        java.util.Set<Integer> verticesToDelete = new java.util.LinkedHashSet<>();
        verticesToDelete.add(rootId);

        boolean changed = true;
        while (changed) {
            changed = false;
            
            java.util.Set<Integer> downstreamCandidates = new java.util.LinkedHashSet<>();
            java.util.Set<Integer> upstreamCandidates = new java.util.LinkedHashSet<>();

            for (int currentId : verticesToDelete) {
                // Outgoing downstream candidates
                if (request.downStream()) {
                    RoaringBitmap fromRows = idContext.getFromInvertedIndexColumn().getRowsForValueOrNull(currentId);
                    RoaringBitmap activeFrom = getActiveRows(fromRows, graphEngineContext.getFromDeleted());
                    for (int rowId : activeFrom) {
                        if (!graphEngineContext.getToDeleted().contains(rowId)) {
                            int toId = idContext.getToIntegerColumnarStore().getInt(rowId);
                            if (toId >= 0 && toId < numIds && getResolvedVertexLabel(toId) != null && !verticesToDelete.contains(toId)) {
                                downstreamCandidates.add(toId);
                            }
                        }
                    }
                }

                // Incoming upstream candidates
                if (request.upstream()) {
                    RoaringBitmap toRows = idContext.getToInvertedIndexColumn().getRowsForValueOrNull(currentId);
                    RoaringBitmap activeTo = getActiveRows(toRows, graphEngineContext.getToDeleted());
                    for (int rowId : activeTo) {
                        if (!graphEngineContext.getFromDeleted().contains(rowId)) {
                            int fromId = idContext.getFromIntegerColumnarStore().getInt(rowId);
                            if (fromId >= 0 && fromId < numIds && getResolvedVertexLabel(fromId) != null && !verticesToDelete.contains(fromId)) {
                                upstreamCandidates.add(fromId);
                            }
                        }
                    }
                }
            }

            // Verify downstream candidates: all active incoming must come from verticesToDelete set
            for (int candidate : downstreamCandidates) {
                if (allIncomingFromDeleted(candidate, verticesToDelete, idContext, numIds)) {
                    verticesToDelete.add(candidate);
                    changed = true;
                    break;
                }
            }

            if (changed) continue;

            // Verify upstream candidates: all active outgoing must go to verticesToDelete set
            for (int candidate : upstreamCandidates) {
                if (allOutgoingToDeleted(candidate, verticesToDelete, idContext, numIds)) {
                    verticesToDelete.add(candidate);
                    changed = true;
                    break;
                }
            }
        }

        return verticesToDelete;
    }

    public synchronized boolean deleteVertex(@NotNull com.self.help.input.VertexDeleteRequest request) {
        java.util.Set<Integer> verticesToDelete = collectVerticesToDelete(request);
        if (verticesToDelete.isEmpty()) {
            return false;
        }

        NodePropertyPairContext idContext = graphEngineContext.getIdContext();

        // Log the entities to be deleted
        System.out.println("=== Deletion Plan ===");
        System.out.println("Vertices to be deleted:");
        for (int vId : verticesToDelete) {
            String sourceId = getSourceId(vId);
            String label = getResolvedVertexLabel(vId);
            System.out.println("  - Vertex ID: " + vId + " (SourceId: " + sourceId + ", Label: " + label + ")");
        }
        
        System.out.println("Edges affected (soft-deleted or nullified):");
        java.util.Set<Integer> affectedRows = new java.util.LinkedHashSet<>();
        for (int vId : verticesToDelete) {
            RoaringBitmap fromRows = idContext.getFromInvertedIndexColumn().getRowsForValueOrNull(vId);
            if (fromRows != null) {
                for (int rId : fromRows) {
                    affectedRows.add(rId);
                }
            }
            RoaringBitmap toRows = idContext.getToInvertedIndexColumn().getRowsForValueOrNull(vId);
            if (toRows != null) {
                for (int rId : toRows) {
                    affectedRows.add(rId);
                }
            }
        }
        for (int rId : affectedRows) {
            int fromVal = idContext.getFromIntegerColumnarStore().getInt(rId);
            int toVal = idContext.getToIntegerColumnarStore().getInt(rId);
            String fromStr = getSourceId(fromVal);
            String toStr = getSourceId(toVal);
            System.out.println("  - Edge Row ID: " + rId + " (" + fromStr + " -> " + toStr + ")");
        }
        System.out.println("=====================");

        // 2. Perform soft deletion on the collected vertices & physically update inverted indexes
        boolean anyDeleted = false;
        for (int vId : verticesToDelete) {
            // Nullify FROM side of all occurrences
            RoaringBitmap fromRows = idContext.getFromInvertedIndexColumn().getRowsForValueOrNull(vId);
            if (fromRows != null && !fromRows.isEmpty()) {
                graphEngineContext.getFromDeleted().or(fromRows);
                fromRows.clear();
                anyDeleted = true;
            }

            // Nullify TO side of all occurrences
            RoaringBitmap toRows = idContext.getToInvertedIndexColumn().getRowsForValueOrNull(vId);
            if (toRows != null && !toRows.isEmpty()) {
                graphEngineContext.getToDeleted().or(toRows);
                toRows.clear();
                anyDeleted = true;
            }
        }

        return anyDeleted;
    }

    /**
     * Calculates and returns a map of all vertex IDs and their labels that would be impacted
     * (deleted) if the specified deletion configuration request was executed.
     *
     * @param request deletion configuration payload
     * @return map from numeric vertex ID to display label of impacted vertices
     */
    public synchronized java.util.Map<Integer, String> getImpactedVertices(@NotNull com.self.help.input.VertexDeleteRequest request) {
        java.util.Set<Integer> verticesToDelete = collectVerticesToDelete(request);
        if (verticesToDelete.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        java.util.Map<Integer, String> impacted = new java.util.LinkedHashMap<>(verticesToDelete.size());
        for (int vId : verticesToDelete) {
            String label = getResolvedVertexLabel(vId);
            if (label != null) {
                impacted.put(vId, label);
            }
        }
        return impacted;
    }

    private boolean allIncomingFromDeleted(int vId, java.util.Set<Integer> deletedSet, NodePropertyPairContext idContext, int numIds) {
        RoaringBitmap toRows = idContext.getToInvertedIndexColumn().getRowsForValueOrNull(vId);
        RoaringBitmap activeTo = getActiveRows(toRows, graphEngineContext.getToDeleted());
        for (int rowId : activeTo) {
            if (!graphEngineContext.getFromDeleted().contains(rowId)) {
                int fromId = idContext.getFromIntegerColumnarStore().getInt(rowId);
                if (fromId >= 0 && fromId < numIds && getResolvedVertexLabel(fromId) != null && !deletedSet.contains(fromId)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean allOutgoingToDeleted(int vId, java.util.Set<Integer> deletedSet, NodePropertyPairContext idContext, int numIds) {
        RoaringBitmap fromRows = idContext.getFromInvertedIndexColumn().getRowsForValueOrNull(vId);
        RoaringBitmap activeFrom = getActiveRows(fromRows, graphEngineContext.getFromDeleted());
        for (int rowId : activeFrom) {
            if (!graphEngineContext.getToDeleted().contains(rowId)) {
                int toId = idContext.getToIntegerColumnarStore().getInt(rowId);
                if (toId >= 0 && toId < numIds && getResolvedVertexLabel(toId) != null && !deletedSet.contains(toId)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Soft-deletes a specific edge by its RawDataStore row index.
     *
     * @param rowId the index of the row to delete
     * @return true if the edge was active and successfully deleted, false otherwise
     */
    public synchronized boolean deleteEdge(int rowId) {
        if (rowId < 0 || rowId >= getIngestedRowCount()) {
            return false;
        }
        boolean alreadyFromDeleted = graphEngineContext.getFromDeleted().contains(rowId);
        boolean alreadyToDeleted = graphEngineContext.getToDeleted().contains(rowId);
        if (alreadyFromDeleted && alreadyToDeleted) {
            return false; // already fully deleted
        }

        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        int fromVertexId = idContext.getFromIntegerColumnarStore().getInt(rowId);
        int toVertexId = idContext.getToIntegerColumnarStore().getInt(rowId);

        // Log edge deletion
        String fromStr = getSourceId(fromVertexId);
        String toStr = getSourceId(toVertexId);
        System.out.println("Deleting Edge: RowId=" + rowId + " (" + fromStr + " -> " + toStr + ")");

        // Remove row index from inverted index bitmaps
        idContext.getFromInvertedIndexColumn().removeRowFromValue(fromVertexId, rowId);
        idContext.getToInvertedIndexColumn().removeRowFromValue(toVertexId, rowId);

        graphEngineContext.getFromDeleted().add(rowId);
        graphEngineContext.getToDeleted().add(rowId);
        return true;
    }

    /**
     * Soft-deletes all active edges connecting a specific FROM node to a specific TO node.
     *
     * @param fromVertexId numeric ID of the source node
     * @param toVertexId   numeric ID of the target node
     * @return true if at least one active edge was successfully deleted, false otherwise
     */
    public synchronized boolean deleteEdge(int fromVertexId, int toVertexId) {
        NodePropertyPairContext idContext = graphEngineContext.getIdContext();
        int numIds = idContext.getBiDirectionalDictionary().size();
        if (fromVertexId < 0 || fromVertexId >= numIds || toVertexId < 0 || toVertexId >= numIds) {
            return false;
        }

        RoaringBitmap fromRows = idContext.getFromInvertedIndexColumn().getRowsForValueOrNull(fromVertexId);
        RoaringBitmap toRows = idContext.getToInvertedIndexColumn().getRowsForValueOrNull(toVertexId);
        if (fromRows == null || toRows == null) {
            return false;
        }

        // Intersect outgoing rows of FROM node and incoming rows of TO node
        RoaringBitmap matchingRows = RoaringBitmap.and(fromRows, toRows);
        if (matchingRows.isEmpty()) {
            return false;
        }

        boolean anyDeleted = false;
        for (int rowId : matchingRows) {
            if (deleteEdge(rowId)) {
                anyDeleted = true;
            }
        }
        return anyDeleted;
    }
}
