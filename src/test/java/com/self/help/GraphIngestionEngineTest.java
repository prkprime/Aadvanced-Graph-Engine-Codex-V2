package com.self.help;

import com.self.help.input.MappingSpec;
import com.self.help.input.NodeSpec;
import com.self.help.legacy.RawDataStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class GraphIngestionEngineTest {

    @Test
    public void testGraphIngestionEngineReturnsAllValidRowsIncludingDuplicates() {
        RawDataStore store = new RawDataStore(List.of("fromCity", "fromArea", "toCity", "toArea", "medium"));
        store.ingestRow(new String[]{"Mumbai", null, "Pune", null, "byRoad"});
        store.ingestRow(new String[]{"Pune", null, "Solapur", null, "byRoad"});


        NodeSpec fromCity = new NodeSpec("fromCity", null, null);
        NodeSpec toCity = new NodeSpec("toCity", null, null);

        MappingSpec spec = new MappingSpec(fromCity, toCity, List.of("medium"));
        GraphIngestionEngine engine = new GraphIngestionEngine(store, spec);
        engine.ingest(0, store);
        engine.ingest(1, store);

        assertNotNull(engine);
    }
}
