package com.self.help;

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
        mockMvc.perform(get("/api/v1/graphs/default/vertices"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(16)))
                .andExpect(jsonPath("$['AUTH']").value("Authentication Service"))
                .andExpect(jsonPath("$['USER_DB']").value("User Database"))
                .andExpect(jsonPath("$['ANALYTICS']").value("Analytics Pipeline"));
    }

    @Test
    void exposesEdgesEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/graphs/default/edges"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(StartupGraphDataConfiguration.startupRowCount())))
                .andExpect(jsonPath("$[0].fromVertexId").value("AUTH"))
                .andExpect(jsonPath("$[0].toVertexId").value("USER_DB"))
                .andExpect(jsonPath("$[0].relations[0]").value("reads"))
                .andExpect(jsonPath("$[0].relations[1]").value("high"))
                .andExpect(jsonPath("$[14].fromVertexId").value("API"))
                .andExpect(jsonPath("$[14].toVertexId").value("ANALYTICS"))
                .andExpect(jsonPath("$[14].relations[0]").value("streams"))
                .andExpect(jsonPath("$[14].relations[1]").value("medium"));
    }

    @Test
    void exposesDictionaryEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/graphs/default/dictionary"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(16)))
                .andExpect(jsonPath("$['AUTH']").value("Authentication Service"))
                .andExpect(jsonPath("$['USER_DB']").value("User Database"))
                .andExpect(jsonPath("$['ANALYTICS']").value("Analytics Pipeline"));
    }
}
