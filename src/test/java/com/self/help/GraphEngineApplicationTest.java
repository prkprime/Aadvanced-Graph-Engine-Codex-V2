package com.self.help;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.self.help.input.GraphMappingSpec;
import com.self.help.legacy.RawDataStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GraphEngineApplicationTest {
    @Autowired
    private RawDataStore graphRawDataStore;

    @Autowired
    private GraphIngestionEngine graphIngestionEngine;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void contextLoads() {
    }

    @Test
    void startupRowsAreIngestedIntoGraph() {
        int expectedRows = StartupGraphDataConfiguration.startupRowCount();

        assertEquals(expectedRows, graphRawDataStore.getSize());
        assertEquals(expectedRows, graphIngestionEngine.getIngestedRowCount());
    }

    @Test
    void exposesVerticesEndpoint() throws Exception {
        // Numeric IDs are assigned in first-seen ingestion order:
        // AUTH=0, USER_DB=1, TOKEN_CACHE=2, API=3, ORDER=4, ..., ANALYTICS=15
        mockMvc.perform(get("/api/v1/graphs/default/vertices"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(16)))
                .andExpect(jsonPath("$['0']").value("Authentication Service"))   // AUTH
                .andExpect(jsonPath("$['1']").value("User Database"))             // USER_DB
                .andExpect(jsonPath("$['15']").value("Analytics Pipeline"));     // ANALYTICS
    }

    @Test
    void exposesEdgesEndpoint() throws Exception {
        // Row 0: AUTH(0) -> USER_DB(1), Row 14: API(3) -> ANALYTICS(15)
        mockMvc.perform(get("/api/v1/graphs/default/edges"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(StartupGraphDataConfiguration.startupRowCount())))
                .andExpect(jsonPath("$[0].fromVertexId").value(0))    // AUTH
                .andExpect(jsonPath("$[0].toVertexId").value(1))      // USER_DB
                .andExpect(jsonPath("$[0].relations[0]").value("reads"))
                .andExpect(jsonPath("$[0].relations[1]").value("high"))
                .andExpect(jsonPath("$[14].fromVertexId").value(3))   // API
                .andExpect(jsonPath("$[14].toVertexId").value(15))    // ANALYTICS
                .andExpect(jsonPath("$[14].relations[0]").value("streams"))
                .andExpect(jsonPath("$[14].relations[1]").value("medium"));
    }

    @Test
    void exposesDictionaryEndpoint() throws Exception {
        // Numeric IDs are assigned in first-seen ingestion order:
        // AUTH=0, USER_DB=1, TOKEN_CACHE=2, API=3, ORDER=4, ..., ANALYTICS=15
        mockMvc.perform(get("/api/v1/graphs/default/dictionary"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(16)))
                .andExpect(jsonPath("$['0']").value("Authentication Service"))  // AUTH
                .andExpect(jsonPath("$['1']").value("User Database"))            // USER_DB
                .andExpect(jsonPath("$['15']").value("Analytics Pipeline"));    // ANALYTICS
    }

    @Test
    @SuppressWarnings("deprecation")
    void exposesSchemaEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/graphs/default/schema"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.idPair.fromColumn").value("fromId"))
                .andExpect(jsonPath("$.idPair.toColumn").value("toId"))
                .andExpect(jsonPath("$.idPair.uniqueVertexCount").value(16))
                .andExpect(jsonPath("$.labelPair.fromColumn").value("fromLabel"))
                .andExpect(jsonPath("$.labelPair.toColumn").value("toLabel"))
                .andExpect(jsonPath("$.labelPair.uniqueLabelCount").value(16))
                .andExpect(jsonPath("$.attributes", hasSize(1)))
                .andExpect(jsonPath("$.attributes[0].name").value("type"))
                .andExpect(jsonPath("$.attributes[0].uniqueValueCount").value(6))
                .andExpect(jsonPath("$.relations", hasSize(2)))
                .andExpect(jsonPath("$.relations[0].name").value("relation"))
                .andExpect(jsonPath("$.relations[0].uniqueValueCount").value(8))
                .andExpect(jsonPath("$.relations[1].name").value("priority"))
                .andExpect(jsonPath("$.relations[1].uniqueValueCount").value(3))
                .andExpect(jsonPath("$.storageMetrics.ingestedRowCount").value(15))
                .andExpect(jsonPath("$.storageMetrics.activeVertexCount").value(16));
    }

    private GraphMappingSpec buildTestSchema() {
        return GraphMappingSpec.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .addAttribute("type", "fromType", "toType")
                .addRelation("relation")
                .addRelation("priority")
                .build();
    }

    @Test
    void exposesDictionaryLookupEndpoint() throws Exception {
        GraphMappingSpec schema = buildTestSchema();
        String schemaJson = objectMapper.writeValueAsString(schema);
        String encodedSchema = java.net.URLEncoder.encode(schemaJson, java.nio.charset.StandardCharsets.UTF_8);

        String requestJson = """
                {
                  "targetType": "ATTRIBUTE",
                  "name": "type"
                }
                """;

        mockMvc.perform(post("/api/v1/graphs/default/dictionary/lookup/" + encodedSchema)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*").value(hasSize(6)));
    }

    @Test
    void exposesDictionaryLookupEndpointForId() throws Exception {
        GraphMappingSpec schema = buildTestSchema();
        String schemaJson = objectMapper.writeValueAsString(schema);
        String encodedSchema = java.net.URLEncoder.encode(schemaJson, java.nio.charset.StandardCharsets.UTF_8);

        String requestJson = """
                {
                  "targetType": "ID"
                }
                """;

        mockMvc.perform(post("/api/v1/graphs/default/dictionary/lookup/" + encodedSchema)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*").value(hasSize(16)));
    }

    @Test
    void exposesVertexAttributesEndpoint() throws Exception {
        int numericId = graphIngestionEngine.getGraphEngineContext()
                .getIdContext()
                .getBiDirectionalDictionary()
                .getIdIfExists("AUTH");

        mockMvc.perform(get("/api/v1/graphs/default/vertices/" + numericId + "/attributes"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.resolvedId").value("AUTH"))
                .andExpect(jsonPath("$.resolvedLabel").value("Authentication Service"))
                .andExpect(jsonPath("$.resolvedAttributes", hasSize(1)))
                .andExpect(jsonPath("$.resolvedAttributes[0][0]").value("service"));
    }

    @Test
    void exposesStatsEndpoint() throws Exception {
        // Numeric ID 0 (AUTH) should have outgoingEdgeCount=2, incomingEdgeCount=1, totalEdgeCount=3
        mockMvc.perform(get("/api/v1/graphs/default/stats"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*").value(hasSize(16)))
                .andExpect(jsonPath("$['0'].outgoingEdgeCount").value(2))
                .andExpect(jsonPath("$['0'].incomingEdgeCount").value(1))
                .andExpect(jsonPath("$['0'].totalEdgeCount").value(3));
    }

    @Test
    void exposesVertexStatsEndpoint() throws Exception {
        int numericId = graphIngestionEngine.getGraphEngineContext()
                .getIdContext()
                .getBiDirectionalDictionary()
                .getIdIfExists("AUTH");

        mockMvc.perform(get("/api/v1/graphs/default/vertices/" + numericId + "/stats"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.outgoingEdgeCount").value(2))
                .andExpect(jsonPath("$.incomingEdgeCount").value(1))
                .andExpect(jsonPath("$.totalEdgeCount").value(3));
    }

    @Test
    void exposesKNeighborsDefaultBoth() throws Exception {
        int numericId = graphIngestionEngine.getGraphEngineContext()
                .getIdContext()
                .getBiDirectionalDictionary()
                .getIdIfExists("AUTH");

        // Traversal K=1, Direction=BOTH (default):
        // Vertices reached: AUTH (0), USER_DB (1), TOKEN_CACHE (2), API (3)
        mockMvc.perform(get("/api/v1/graphs/default/vertices/" + numericId + "/neighbors")
                        .param("k", "1")
                        .param("direction", "BOTH"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.vertices", aMapWithSize(4)))
                .andExpect(jsonPath("$.vertices['0']").value("Authentication Service"))
                .andExpect(jsonPath("$.vertices['1']").value("User Database"))
                .andExpect(jsonPath("$.vertices['2']").value("Token Cache"))
                .andExpect(jsonPath("$.vertices['3']").value("Public API"))
                .andExpect(jsonPath("$.edges", hasSize(3)));
    }

    @Test
    void exposesKNeighborsOutgoingOnly() throws Exception {
        int numericId = graphIngestionEngine.getGraphEngineContext()
                .getIdContext()
                .getBiDirectionalDictionary()
                .getIdIfExists("AUTH");

        // Traversal K=1, Direction=OUTGOING:
        // Vertices reached: AUTH (0), USER_DB (1), TOKEN_CACHE (2)
        mockMvc.perform(get("/api/v1/graphs/default/vertices/" + numericId + "/neighbors")
                        .param("k", "1")
                        .param("direction", "OUTGOING"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.vertices", aMapWithSize(3)))
                .andExpect(jsonPath("$.vertices['0']").value("Authentication Service"))
                .andExpect(jsonPath("$.vertices['1']").value("User Database"))
                .andExpect(jsonPath("$.vertices['2']").value("Token Cache"))
                .andExpect(jsonPath("$.vertices['3']").doesNotExist())
                .andExpect(jsonPath("$.edges", hasSize(2)))
                .andExpect(jsonPath("$.edges[0].fromVertexId").value(0))
                .andExpect(jsonPath("$.edges[0].toVertexId").value(1))
                .andExpect(jsonPath("$.edges[1].fromVertexId").value(0))
                .andExpect(jsonPath("$.edges[1].toVertexId").value(2));
    }

    @Test
    void exposesKNeighborsIncomingOnly() throws Exception {
        int numericId = graphIngestionEngine.getGraphEngineContext()
                .getIdContext()
                .getBiDirectionalDictionary()
                .getIdIfExists("AUTH");

        // Traversal K=1, Direction=INCOMING:
        // Vertices reached: AUTH (0), API (3)
        mockMvc.perform(get("/api/v1/graphs/default/vertices/" + numericId + "/neighbors")
                        .param("k", "1")
                        .param("direction", "INCOMING"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.vertices", aMapWithSize(2)))
                .andExpect(jsonPath("$.vertices['0']").value("Authentication Service"))
                .andExpect(jsonPath("$.vertices['3']").value("Public API"))
                .andExpect(jsonPath("$.vertices['1']").doesNotExist())
                .andExpect(jsonPath("$.edges", hasSize(1)))
                .andExpect(jsonPath("$.edges[0].fromVertexId").value(3))
                .andExpect(jsonPath("$.edges[0].toVertexId").value(0));
    }
}
