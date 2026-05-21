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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        System.out.println("\n=== [returnsRowWiseEdgesFromEncodedStores] ===");
        System.out.println("Graph Structure:");
        System.out.println("  Mumbai --[road, high]--> Pune");
        System.out.println("  Pune   --[rail, null]--> Solapur\n");
        System.out.println("Edges Output:");
        for (int i = 0; i < edges.size(); i++) {
            GraphEdgeResponse edge = edges.get(i);
            System.out.println("  - Edge " + i + ": " + edge.fromVertexId() + " -> " + edge.toVertexId() + " " + edge.relations());
        }
        System.out.println("==============================================\n");

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

    @Test
    public void testGetVertexDictionaryOptimizationAndCorrectness() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "toCity", "fromLabel", "toLabel"));
        // Standard edge with matching labels
        store.ingestRow(new String[]{"Mumbai", "Pune", "CityLabel", "CityLabel"});
        // Edge with duplicate vertices to test first-seen preservation
        store.ingestRow(new String[]{"Mumbai", "Delhi", "CityLabel", "CapitalLabel"});
        // Partial row with from deleted (null)
        store.ingestRow(new String[]{null, "Bangalore", null, "TechLabel"});
        // Partial row with to deleted (null)
        store.ingestRow(new String[]{"Kolkata", null, "HeritageLabel", null});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .labelPair("fromLabel", "toLabel")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);

        engine.ingest(0);
        engine.ingest(1);
        engine.ingest(2);
        engine.ingest(3);

        java.util.Map<String, String> vertexDict = engine.getVertexDictionary();

        System.out.println("\n=== [testGetVertexDictionaryOptimizationAndCorrectness] ===");
        System.out.println("Graph Structure:");
        System.out.println("  Mumbai (CityLabel) -> Pune (CityLabel)");
        System.out.println("  Mumbai (CityLabel) -> Delhi (CapitalLabel)");
        System.out.println("  (null)             -> Bangalore (TechLabel)");
        System.out.println("  Kolkata (HeritageLabel) -> (null)\n");
        System.out.println("Vertex Dictionary Output:");
        vertexDict.forEach((k, v) -> System.out.println("  " + k + " => " + v));
        System.out.println("============================================================\n");

        // 1. Verify correct sizes (skips nulls, maps each unique vertex exactly once)
        assertEquals(5, vertexDict.size());

        // 2. Verify all keys mapped to their correct labels
        assertEquals("CityLabel", vertexDict.get("Mumbai"));
        assertEquals("CityLabel", vertexDict.get("Pune"));
        assertEquals("CapitalLabel", vertexDict.get("Delhi"));
        assertEquals("TechLabel", vertexDict.get("Bangalore"));
        assertEquals("HeritageLabel", vertexDict.get("Kolkata"));

        // 3. Verify exact first-seen insertion order
        // Order seen: Mumbai (row 0 from) -> Pune (row 0 to) -> Delhi (row 1 to) -> Bangalore (row 2 to) -> Kolkata (row 3 from)
        List<String> expectedOrder = List.of("Mumbai", "Pune", "Delhi", "Bangalore", "Kolkata");
        List<String> actualOrder = new java.util.ArrayList<>(vertexDict.keySet());
        assertEquals(expectedOrder, actualOrder);
    }

    @Test
    public void testGetVertexAttributesCorrectness() {
        RawDataStore store = new RawDataStore(List.of("fromId", "toId", "fromLabel", "toLabel", "fromAttr", "toAttr"));
        store.ingestRow(new String[]{"V1", "V2", "LabelV1", "LabelV2", "Attr1_V1", "Attr1_V2"});
        store.ingestRow(new String[]{"V3", "V1", "LabelV3", "LabelV1", "Attr2_V3", "Attr2_V1"});
        store.ingestRow(new String[]{"V1", null, "LabelV1", null, "AttrDeleted", null}); // toDeleted row, from side active
        store.ingestRow(new String[]{null, "V1", null, "LabelV1", null, "AttrDeleted2"}); // fromDeleted row, to side active
        store.ingestRow(new String[]{"V1", "V1", "LabelV1", "LabelV1", "AttrSelf_From", "AttrSelf_To"}); // Self loop

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .addAttribute("attr", "fromAttr", "toAttr")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);

        engine.ingest(0);
        engine.ingest(1);
        engine.ingest(2);
        engine.ingest(3);
        engine.ingest(4);

        int numericId = engine.getGraphEngineContext().getIdContext().getBiDirectionalDictionary().getIdIfExists("V1");
        com.self.help.output.VertexAttributesResponse response = engine.getVertexAttributes(numericId);

        System.out.println("\n=== [testGetVertexAttributesCorrectness] ===");
        System.out.println("Graph Structure:");
        System.out.println("  V1 (LabelV1) --[Attr1_V1, Attr1_V2]--> V2 (LabelV2)");
        System.out.println("  V3 (LabelV3) --[Attr2_V3, Attr2_V1]--> V1 (LabelV1)");
        System.out.println("  V1 (LabelV1) --[AttrDeleted, null]--> (null)");
        System.out.println("  (null)       --[null, AttrDeleted2]--> V1 (LabelV1)");
        System.out.println("  V1 (LabelV1) --[AttrSelf_From, AttrSelf_To]--> V1 (LabelV1)\n");
        System.out.println("Vertex Attributes Output for V1 (LabelV1):");
        response.resolvedAttributes().forEach(a -> System.out.println("  - " + a));
        System.out.println("=============================================\n");

        assertEquals("V1", response.resolvedId());
        assertEquals("LabelV1", response.resolvedLabel());
        List<List<String>> expectedAttrs = List.of(
                List.of("Attr1_V1"),
                List.of("AttrDeleted"),
                List.of("AttrSelf_From"),
                List.of("Attr2_V1"),
                List.of("AttrDeleted2"),
                List.of("AttrSelf_To")
        );
        assertEquals(expectedAttrs, response.resolvedAttributes());
    }

    @Test
    public void testGetVertexLabelCorrectness() {
        RawDataStore store = new RawDataStore(List.of("fromId", "toId", "fromLabel", "toLabel"));
        store.ingestRow(new String[]{"V1", "V2", "LabelV1", "LabelV2"});
        store.ingestRow(new String[]{"V3", "V4", "LabelV3", "LabelV4"});
        store.ingestRow(new String[]{null, "V5", null, "LabelV5"}); // partial row

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);

        engine.ingest(0);
        engine.ingest(1);
        engine.ingest(2);

        int idV1 = engine.getGraphEngineContext().getIdContext().getBiDirectionalDictionary().getIdIfExists("V1");
        int idV5 = engine.getGraphEngineContext().getIdContext().getBiDirectionalDictionary().getIdIfExists("V5");

        System.out.println("\n=== [testGetVertexLabelCorrectness] ===");
        System.out.println("Graph Structure:");
        System.out.println("  V1 (LabelV1) -> V2 (LabelV2)");
        System.out.println("  V3 (LabelV3) -> V4 (LabelV4)");
        System.out.println("  (null)       -> V5 (LabelV5)\n");
        System.out.println("Vertex Labels Output:");
        System.out.println("  V1 => " + engine.getResolvedVertexLabel(idV1));
        System.out.println("  V5 => " + engine.getResolvedVertexLabel(idV5));
        System.out.println("========================================\n");

        assertEquals("LabelV1", engine.getResolvedVertexLabel(idV1));
        assertEquals("LabelV5", engine.getResolvedVertexLabel(idV5));

        // Test out-of-bounds inputs to verify robust null-checking
        assertEquals(null, engine.getResolvedVertexLabel(-1));
        assertEquals(null, engine.getResolvedVertexLabel(9999));
    }

    @Test
    public void testGetVertexAttributesNullLabel() {
        RawDataStore store = new RawDataStore(List.of("fromId", "toId", "fromLabel", "toLabel"));
        store.ingestRow(new String[]{"V1", "V2", "LabelV1", "LabelV2"});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);
        engine.ingest(0);

        // Test with invalid / unregistered IDs
        assertNull(engine.getVertexAttributes(-1));
        assertNull(engine.getVertexAttributes(9999));
    }

    @Test
    public void testGetVertexAttributesTombstoned() {
        RawDataStore store = new RawDataStore(List.of("fromId", "toId", "fromLabel", "toLabel", "fromAttr", "toAttr"));
        store.ingestRow(new String[]{"V1", "V2", "LabelV1", "LabelV2", "Attr1", "Attr2"});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .addAttribute("attr", "fromAttr", "toAttr")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);
        engine.ingest(0);

        int numericId = engine.getGraphEngineContext().getIdContext().getBiDirectionalDictionary().getIdIfExists("V1");

        // Manually tombstone the only row (row 0)
        engine.getGraphEngineContext().getFromDeleted().add(0);

        com.self.help.output.VertexAttributesResponse response = engine.getVertexAttributes(numericId);
        assertNotNull(response);
        assertEquals("V1", response.resolvedId());
        assertEquals("LabelV1", response.resolvedLabel());
        assertTrue(response.resolvedAttributes().isEmpty());
    }

    @Test
    public void testGetVertexAttributesNoConfiguredAttributes() {
        RawDataStore store = new RawDataStore(List.of("fromId", "toId", "fromLabel", "toLabel"));
        store.ingestRow(new String[]{"V1", "V2", "LabelV1", "LabelV2"});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .build();
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);
        engine.ingest(0);

        int numericId = engine.getGraphEngineContext().getIdContext().getBiDirectionalDictionary().getIdIfExists("V1");

        com.self.help.output.VertexAttributesResponse response = engine.getVertexAttributes(numericId);
        assertNotNull(response);
        assertEquals("V1", response.resolvedId());
        assertEquals("LabelV1", response.resolvedLabel());
        assertTrue(response.resolvedAttributes().isEmpty());
    }
}


