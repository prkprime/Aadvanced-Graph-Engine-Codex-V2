package com.self.help;

import com.self.help.input.MappingSpec;
import com.self.help.input.NodeSpec;
import com.self.help.legacy.RawDataStore;
import com.self.help.output.GraphEdgeResponse;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;
import com.self.help.util.MappingSpecUtil;
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

        NodeSpec fromCity = new NodeSpec("fromCity", null, null);
        NodeSpec toCity = new NodeSpec("toCity", null, null);

        MappingSpec spec = new MappingSpec(fromCity, toCity, List.of("medium"));
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

        MappingSpec spec = new MappingSpec(
                new NodeSpec("fromCity", null, null),
                new NodeSpec("toCity", null, null),
                List.of());
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);

        assertThrows(IllegalArgumentException.class, () -> engine.ingest(0, otherStore));
    }

    @Test
    public void returnsRowWiseEdgesFromEncodedStores() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "toCity", "medium", "priority"));
        store.ingestRow(new String[]{"Mumbai", "Pune", "road", "high"});
        store.ingestRow(new String[]{"Pune", "Solapur", "rail", null});

        MappingSpec spec = new MappingSpec(
                new NodeSpec("fromCity", null, null),
                new NodeSpec("toCity", null, null),
                List.of("medium", "priority"));
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
        NodeSpec fromCity = new NodeSpec("fromCity", null, List.of("fromRegion", "fromRegion"));
        NodeSpec toCity = new NodeSpec("toCity", null, List.of("toRegion", "toRegion"));
        MappingSpec spec = new MappingSpec(fromCity, toCity, List.of());

        assertThrows(IllegalArgumentException.class, () -> MappingSpecUtil.validateSpec(store, spec));
    }

    @Test
    public void intersectIntoClearsCandidatesWhenValueIsAbsent() {
        InvertedIndexColumn index = new InvertedIndexColumn();
        RoaringBitmap candidateRows = RoaringBitmap.bitmapOf(1, 2, 3);

        boolean hasMatches = index.intersectInto(candidateRows, 42);

        assertTrue(candidateRows.isEmpty());
        assertFalse(hasMatches);
    }
}
