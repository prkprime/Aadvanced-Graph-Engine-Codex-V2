package com.self.help;

import com.self.help.input.DictionaryQueryRequest;
import com.self.help.input.GraphMappingSchema;
import com.self.help.output.GraphEdgeResponse;
import com.self.help.storage.BiDirectionalDictionary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST entry point for graph read APIs.
 * <p>
 * The controller is currently a stub layer that defines the public HTTP
 * contract before the query implementation is wired underneath it. Each route
 * is scoped by {@code graphId}, which represents the logical graph instance to
 * query once the service supports multiple loaded graphs. Until that registry
 * exists, callers can use a placeholder value such as {@code default}.
 */
@RestController
@RequiredArgsConstructor
public class GraphQueryController {
    private final GraphIngestionEngine graphIngestionEngine;

    /**
     * Returns the vertices for a graph.
     * <p>
     * The response is a dictionary-style view of vertex identifiers and their
     * display labels.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return vertex id to vertex label mapping
     */
    @GetMapping("/api/v1/graphs/{graphId}/vertices")
    public Map<String, String> getVertices(@PathVariable String graphId) {
        return graphIngestionEngine.getVertexDictionary();
    }

    /**
     * Returns the edges for a graph.
     * <p>
     * The response is a row-wise list of graph edges, including the source
     * vertex id, target vertex id, and relation values.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return row-wise edge response
     */
    @GetMapping("/api/v1/graphs/{graphId}/edges")
    public List<GraphEdgeResponse> getEdges(@PathVariable String graphId) {
        return graphIngestionEngine.getEdges();
    }

    /**
     * Returns the vertex dictionary used by UI renderers.
     * <p>
     * The dictionary is the stable mapping from vertex id to vertex label. The
     * UI can use this response to hydrate graph payloads that carry compact
     * vertex identifiers, without requiring each edge response to repeat display
     * labels.
     * <p>
     * Intended response shape:
     * <pre>
     * {
     *   "AUTH": "Authentication Service",
     *   "USER_DB": "User Database"
     * }
     * </pre>
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return vertex id to vertex label mapping
     */
    @GetMapping("/api/v1/graphs/{graphId}/dictionary")
    public Map<String, String> getVertexIdToLabelMap(@PathVariable String graphId) {
        return graphIngestionEngine.getVertexDictionary();
    }

    /**
     * Returns the schema metadata for a graph.
     * <p>
     * The intended response should describe how raw columns are mapped into the
     * graph model, including from-node columns, to-node columns, relation
     * columns, and any supported attributes. The current method returns an empty
     * stub payload.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return placeholder schema response
     */
    @GetMapping("/api/v1/graphs/{graphId}/schema")
    public Map<String, Object> getSchema(@PathVariable String graphId) {
        return Collections.emptyMap();
    }

    /**
     * Returns aggregate statistics for a graph.
     * <p>
     * The intended response should include lightweight counts such as vertex
     * count, edge count, relation count, and eventually ingestion or storage
     * metrics. The current method returns an empty stub payload.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return placeholder statistics response
     */
    @GetMapping("/api/v1/graphs/{graphId}/stats")
    public Map<String, Object> getStats(@PathVariable String graphId) {
        return Collections.emptyMap();
    }

    /**
     * Looks up and returns the dictionary mapping for the specified schema and target column.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @param request payload containing the schema, target type, and target name
     * @return map from dictionary integer id to original string value
     */
    @PostMapping("/api/v1/graphs/{graphId}/dictionary/lookup")
    public Map<Integer, String> lookupDictionary(
            @PathVariable String graphId,
            @RequestBody DictionaryQueryRequest request) {
        BiDirectionalDictionary dictionary = graphIngestionEngine.getDictionaryFor(
                request.schema(),
                request.targetType(),
                request.name()
        );
        return dictionary.asMap();
    }
}
