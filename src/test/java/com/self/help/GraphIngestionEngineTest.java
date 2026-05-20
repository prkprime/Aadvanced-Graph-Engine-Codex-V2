package com.self.help;

import com.self.help.input.GraphMappingSpec;
import com.self.help.legacy.RawDataStore;
import com.self.help.output.GraphEdgeResponse;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;
import com.self.help.util.GraphMappingSchemaValidator;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GraphIngestionEngineTest {

    @Test
    public void ingestsRowsFromConfiguredRawStore() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "fromArea", "toCity", "toArea", "medium"));
        store.ingestRow(new String[]{"Mumbai", null, "Pune", null, "byRoad"});
        store.ingestRow(new String[]{"Pune", null, "Solapur", null, "byRoad"});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .addRelation("medium")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);

        assertDoesNotThrow(() -> {
            engine.ingest(0);
            engine.ingest(1, store);
        });
    }

    @Test
    public void rejectsRowsFromDifferentRawStoreInstance() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "toCity"));
        store.ingestRow(new String[]{"Mumbai", "Pune"});

        RawDataStore otherStore = new RawDataStore(List.of("fromCity", "toCity"));
        otherStore.ingestRow(new String[]{"Pune", "Solapur"});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);

        assertThrows(IllegalArgumentException.class, () -> engine.ingest(0, otherStore));
    }

    @Test
    public void returnsRowWiseEdgesFromEncodedStores() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "toCity", "medium", "priority"));
        store.ingestRow(new String[]{"Mumbai", "Pune", "road", "high"});
        store.ingestRow(new String[]{"Pune", "Solapur", "rail", null});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .addRelation("medium")
                .addRelation("priority")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);
        engine.ingest(0);
        engine.ingest(1);

        List<GraphEdgeResponse> edges = engine.getEdges();

        assertEquals(2, edges.size());
        assertEquals(new GraphEdgeResponse("Mumbai", "Pune", List.of("road", "high")), edges.get(0));
        assertEquals("Pune", edges.get(1).fromVertexId());
        assertEquals("Solapur", edges.get(1).toVertexId());
        assertEquals("rail", edges.get(1).relations().get(0));
        assertEquals(null, edges.get(1).relations().get(1));
    }

    @Test
    public void encodesNullValuesAsStableDictionaryEntries() {
        BiDirectionalDictionary dictionary = new BiDirectionalDictionary();

        int firstId = dictionary.getOrEncode(null);
        int secondId = dictionary.getOrEncode(null);

        assertEquals(firstId, secondId);
        assertEquals(1, dictionary.size());
        assertEquals(firstId, dictionary.getIdIfExists(null));
    }

    @Test
    public void duplicateRawColumnsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RawDataStore(List.of("city", "city")));
    }

    @Test
    public void duplicateMappedAttributesAreRejected() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "fromRegion", "toCity", "toRegion"));
        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .addAttribute("region1", "fromRegion", "toRegion")
                .addAttribute("region2", "fromRegion", "toRegion")
                .build();

        assertThrows(IllegalArgumentException.class, () -> GraphMappingSchemaValidator.validate(store, spec));
    }

    @Test
    public void intersectIntoClearsCandidatesWhenValueIsAbsent() {
        InvertedIndexColumn index = new InvertedIndexColumn();
        RoaringBitmap candidateRows = RoaringBitmap.bitmapOf(1, 2, 3);

        boolean hasMatches = index.intersectInto(candidateRows, 42);

        assertTrue(candidateRows.isEmpty());
        assertFalse(hasMatches);
    }

    @Test
    public void skipsCompletelyNullRowsDuringIngestion() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "toCity", "medium"));
        store.ingestRow(new String[]{null, null, "byRoad"});
        store.ingestRow(new String[]{"Mumbai", "Pune", "byRoad"});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .addRelation("medium")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);

        engine.ingest(0); // Should be skipped
        engine.ingest(1); // Should be ingested as internal row index 0

        assertEquals(1, engine.getIngestedRowCount());
        List<GraphEdgeResponse> edges = engine.getEdges();
        assertEquals(1, edges.size());
        assertEquals(new GraphEdgeResponse("Mumbai", "Pune", List.of("byRoad")), edges.get(0));
        assertTrue(engine.getGraphEngineContext().getFromDeleted().isEmpty());
        assertTrue(engine.getGraphEngineContext().getToDeleted().isEmpty());
    }

    @Test
    public void ingestsPartiallyNullRowsAndTracksTombstones() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "toCity", "medium"));
        // Row 0: FROM_ID is null (tombstone)
        store.ingestRow(new String[]{null, "Pune", "byRoad"});
        // Row 1: TO_ID is null (tombstone)
        store.ingestRow(new String[]{"Mumbai", null, "byRoad"});
        // Row 2: Standard row
        store.ingestRow(new String[]{"Mumbai", "Pune", "byRoad"});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .addRelation("medium")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);

        engine.ingest(0); // Internal row 0
        engine.ingest(1); // Internal row 1
        engine.ingest(2); // Internal row 2

        assertEquals(3, engine.getIngestedRowCount());

        // Check tombstones in GraphEngineContext
        assertTrue(engine.getGraphEngineContext().getFromDeleted().contains(0));
        assertFalse(engine.getGraphEngineContext().getFromDeleted().contains(1));
        assertFalse(engine.getGraphEngineContext().getFromDeleted().contains(2));

        assertTrue(engine.getGraphEngineContext().getToDeleted().contains(1));
        assertFalse(engine.getGraphEngineContext().getToDeleted().contains(0));
        assertFalse(engine.getGraphEngineContext().getToDeleted().contains(2));

        // Verify that the vertex dictionary correctly skips the null vertex ID key
        java.util.Map<String, String> vertexDict = engine.getVertexDictionary();
        assertFalse(vertexDict.containsKey(null));
        assertTrue(vertexDict.containsKey("Mumbai"));
        assertTrue(vertexDict.containsKey("Pune"));

        // Verify edges response has null IDs and null relation values
        List<GraphEdgeResponse> edges = engine.getEdges();
        assertEquals(3, edges.size());

        // Row 0 (FROM_ID is null): relations are nullified
        assertEquals(null, edges.get(0).fromVertexId());
        assertEquals("Pune", edges.get(0).toVertexId());
        assertEquals(java.util.Collections.singletonList(null), edges.get(0).relations());

        // Row 1 (TO_ID is null): relations are nullified
        assertEquals("Mumbai", edges.get(1).fromVertexId());
        assertEquals(null, edges.get(1).toVertexId());
        assertEquals(java.util.Collections.singletonList(null), edges.get(1).relations());

        // Row 2 (Standard row): relations preserved
        assertEquals("Mumbai", edges.get(2).fromVertexId());
        assertEquals("Pune", edges.get(2).toVertexId());
        assertEquals(List.of("byRoad"), edges.get(2).relations());
    }

    @Test
    public void treatsStringBlanksAsUniqueValidIdentifiers() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "toCity", "medium"));
        // Row 0: FROM_ID is empty string "" (not null)
        store.ingestRow(new String[]{"", "Pune", "byRoad"});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .addRelation("medium")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);

        engine.ingest(0);

        assertEquals(1, engine.getIngestedRowCount());
        // Should not track as tombstone
        assertTrue(engine.getGraphEngineContext().getFromDeleted().isEmpty());
        assertTrue(engine.getGraphEngineContext().getToDeleted().isEmpty());

        List<GraphEdgeResponse> edges = engine.getEdges();
        assertEquals(1, edges.size());
        assertEquals("", edges.get(0).fromVertexId());
        assertEquals("Pune", edges.get(0).toVertexId());
        assertEquals(List.of("byRoad"), edges.get(0).relations());
    }
}
