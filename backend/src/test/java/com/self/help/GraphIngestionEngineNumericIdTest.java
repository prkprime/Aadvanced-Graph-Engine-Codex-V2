package com.self.help;

import com.self.help.input.GraphMappingSpec;
import com.self.help.legacy.RawDataStore;
import com.self.help.output.GraphEdgeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that verify the numeric-ID-as-primary-key contract introduced to expose
 * compact dictionary-assigned integers to the UI instead of raw source strings.
 *
 * <p>The design: each source string ingested via {@link GraphIngestionEngine} is
 * assigned a stable compact {@code int} by the internal {@link com.self.help.storage.BiDirectionalDictionary}.
 * That integer is now the primary vertex identifier returned by {@link GraphIngestionEngine#getEdges()}
 * and {@link GraphIngestionEngine#getVertexDictionary()}. The original source string is
 * a secondary field accessible via {@link GraphIngestionEngine#getSourceId(int)} and
 * reverse-searchable via {@link GraphIngestionEngine#getNumericIdBySourceId(String)}.
 */
public class GraphIngestionEngineNumericIdTest {

    private RawDataStore store;
    private GraphIngestionEngine engine;

    /**
     * Shared setup: three-city graph (Mumbai=0, Delhi=1, Pune=2) with a Train/Bus/Flight medium.
     *
     * <pre>
     * fromCity | toCity | medium
     * ---------|--------|-------
     * Mumbai   | Delhi  | Train
     * Mumbai   | Pune   | Bus
     * Delhi    | Pune   | Flight
     * </pre>
     */
    @BeforeEach
    void setUp() {
        store = new RawDataStore(List.of("fromCity", "toCity", "medium"));
        store.ingestRow(new String[]{"Mumbai", "Delhi",  "Train"});
        store.ingestRow(new String[]{"Mumbai", "Pune",   "Bus"});
        store.ingestRow(new String[]{"Delhi",  "Pune",   "Flight"});

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .addRelation("medium")
                .build();
        engine = new GraphIngestionEngine(store, spec);
        engine.ingest(0);
        engine.ingest(1);
        engine.ingest(2);

        System.out.println("\n=== Graph: fromCity | toCity | medium ===");
        System.out.println("  Mumbai(0) --[Train]--> Delhi(1)");
        System.out.println("  Mumbai(0) --[Bus]----> Pune(2)");
        System.out.println("  Delhi(1)  --[Flight]-> Pune(2)");
        System.out.println("=========================================\n");
    }

    // -------------------------------------------------------------------------
    // getEdges() — numeric IDs as primary key
    // -------------------------------------------------------------------------

    @Test
    void edgesCarryCompactNumericVertexIds() {
        List<GraphEdgeResponse> edges = engine.getEdges();

        System.out.println("=== [edgesCarryCompactNumericVertexIds] ===");
        edges.forEach(e -> System.out.println("  " + e.fromVertexId() + " -> " + e.toVertexId() + " " + e.relations()));
        System.out.println("===========================================\n");

        assertEquals(3, edges.size());

        // Mumbai=0, Delhi=1, Pune=2 (dictionary-assignment order)
        assertEquals(Integer.valueOf(0), edges.get(0).fromVertexId()); // Mumbai
        assertEquals(Integer.valueOf(1), edges.get(0).toVertexId());   // Delhi
        assertEquals(List.of("Train"),   edges.get(0).relations());

        assertEquals(Integer.valueOf(0), edges.get(1).fromVertexId()); // Mumbai
        assertEquals(Integer.valueOf(2), edges.get(1).toVertexId());   // Pune
        assertEquals(List.of("Bus"),     edges.get(1).relations());

        assertEquals(Integer.valueOf(1), edges.get(2).fromVertexId()); // Delhi
        assertEquals(Integer.valueOf(2), edges.get(2).toVertexId());   // Pune
        assertEquals(List.of("Flight"),  edges.get(2).relations());
    }

    @Test
    void edgesFromVertexIdTypeIsInteger() {
        List<GraphEdgeResponse> edges = engine.getEdges();
        Object fromVertexId = edges.get(0).fromVertexId();
        assertNotNull(fromVertexId);
        assertEquals(Integer.class, fromVertexId.getClass());
    }

    // -------------------------------------------------------------------------
    // getVertexDictionary() — Map<Integer, String>
    // -------------------------------------------------------------------------

    @Test
    void vertexDictionaryKeyedByNumericId() {
        Map<Integer, String> dict = engine.getVertexDictionary();

        System.out.println("=== [vertexDictionaryKeyedByNumericId] ===");
        dict.forEach((k, v) -> System.out.println("  " + k + " => " + v));
        System.out.println("==========================================\n");

        assertEquals(3, dict.size());
        // When no explicit labelPair, label falls back to the id value
        assertEquals("Mumbai", dict.get(0));
        assertEquals("Delhi",  dict.get(1));
        assertEquals("Pune",   dict.get(2));
    }

    @Test
    void vertexDictionaryOrderFollowsDictionaryAssignment() {
        Map<Integer, String> dict = engine.getVertexDictionary();
        // Mumbai ingested first (row 0 from-side) → 0, Delhi next (row 0 to-side) → 1, Pune (row 1 to-side) → 2
        List<Integer> keys = new java.util.ArrayList<>(dict.keySet());
        assertEquals(List.of(0, 1, 2), keys);
    }

    // -------------------------------------------------------------------------
    // getSourceId() — numeric → original source string
    // -------------------------------------------------------------------------

    @Test
    void getSourceIdResolvesNumericIdBackToOriginalString() {
        System.out.println("=== [getSourceIdResolvesNumericIdBackToOriginalString] ===");
        System.out.println("  getSourceId(0) => " + engine.getSourceId(0));
        System.out.println("  getSourceId(1) => " + engine.getSourceId(1));
        System.out.println("  getSourceId(2) => " + engine.getSourceId(2));
        System.out.println("==========================================================\n");

        assertEquals("Mumbai", engine.getSourceId(0));
        assertEquals("Delhi",  engine.getSourceId(1));
        assertEquals("Pune",   engine.getSourceId(2));
    }

    @Test
    void getSourceIdReturnsNullForOutOfRangeIds() {
        assertNull(engine.getSourceId(-1));
        assertNull(engine.getSourceId(9999));
    }

    // -------------------------------------------------------------------------
    // getNumericIdBySourceId() — original source string → numeric
    // -------------------------------------------------------------------------

    @Test
    void getNumericIdBySourceIdResolvesKnownSourceStrings() {
        System.out.println("=== [getNumericIdBySourceIdResolvesKnownSourceStrings] ===");
        System.out.println("  getNumericIdBySourceId(\"Mumbai\") => " + engine.getNumericIdBySourceId("Mumbai"));
        System.out.println("  getNumericIdBySourceId(\"Delhi\")  => " + engine.getNumericIdBySourceId("Delhi"));
        System.out.println("  getNumericIdBySourceId(\"Pune\")   => " + engine.getNumericIdBySourceId("Pune"));
        System.out.println("==========================================================\n");

        assertEquals(Integer.valueOf(0), engine.getNumericIdBySourceId("Mumbai"));
        assertEquals(Integer.valueOf(1), engine.getNumericIdBySourceId("Delhi"));
        assertEquals(Integer.valueOf(2), engine.getNumericIdBySourceId("Pune"));
    }

    @Test
    void getNumericIdBySourceIdReturnsNullForUnknownSourceString() {
        assertNull(engine.getNumericIdBySourceId("Kolkata"));
        assertNull(engine.getNumericIdBySourceId(""));
        assertNull(engine.getNumericIdBySourceId("mumbai")); // case-sensitive
    }

    @Test
    void sourceIdRoundTrip() {
        // getNumericIdBySourceId → getSourceId must be a perfect round-trip
        for (String city : List.of("Mumbai", "Delhi", "Pune")) {
            Integer numericId = engine.getNumericIdBySourceId(city);
            assertNotNull(numericId, "Expected numeric id for: " + city);
            assertEquals(city, engine.getSourceId(numericId),
                    "Round-trip failed for: " + city);
        }
    }

    // -------------------------------------------------------------------------
    // Tombstone consistency — partial rows still produce null numeric IDs
    // -------------------------------------------------------------------------

    @Test
    void tombstonedSideProducesNullNumericIdInEdges() {
        RawDataStore partialStore = new RawDataStore(List.of("fromCity", "toCity", "medium"));
        partialStore.ingestRow(new String[]{null,     "Pune",   "Bus"});   // from-null row
        partialStore.ingestRow(new String[]{"Mumbai", null,     "Train"}); // to-null row
        partialStore.ingestRow(new String[]{"Mumbai", "Pune",   "Road"});  // standard row

        GraphMappingSpec spec = GraphMappingSpec.builder()
                .idPair("fromCity", "toCity")
                .addRelation("medium")
                .build();
        GraphIngestionEngine partialEngine = new GraphIngestionEngine(partialStore, spec);
        partialEngine.ingest(0);
        partialEngine.ingest(1);
        partialEngine.ingest(2);

        List<GraphEdgeResponse> edges = partialEngine.getEdges();

        System.out.println("=== [tombstonedSideProducesNullNumericIdInEdges] ===");
        edges.forEach(e -> System.out.println("  from=" + e.fromVertexId() + " to=" + e.toVertexId() + " " + e.relations()));
        System.out.println("====================================================\n");

        // Row 0: from-side tombstoned → fromVertexId is null, toVertexId is a numeric int
        assertNull(edges.get(0).fromVertexId());
        assertNotNull(edges.get(0).toVertexId());

        // Row 1: to-side tombstoned → toVertexId is null, fromVertexId is a numeric int
        assertNotNull(edges.get(1).fromVertexId());
        assertNull(edges.get(1).toVertexId());

        // Row 2: standard row → both are numeric ints
        assertNotNull(edges.get(2).fromVertexId());
        assertNotNull(edges.get(2).toVertexId());
    }
}
