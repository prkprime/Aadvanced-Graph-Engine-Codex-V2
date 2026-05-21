package com.self.help;

import com.self.help.input.DictionaryQueryRequest;
import com.self.help.input.GraphMappingSpec;
import com.self.help.output.GraphEdgeResponse;
import com.self.help.output.VertexAttributesResponse;
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
     * The response is a dictionary-style view mapping each vertex's compact numeric
     * id (assigned by the internal {@code BiDirectionalDictionary} during ingestion)
     * to its display label.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return numeric vertex id to vertex label mapping
     */
    @GetMapping("/api/v1/graphs/{graphId}/vertices")
    public Map<Integer, String> getVertices(@PathVariable String graphId) {
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
     * The dictionary maps each vertex's compact numeric id to its display label.
     * The UI loads this once as a lookup table and resolves edge endpoints
     * ({@code fromVertexId} / {@code toVertexId} integers from the edge API)
     * to human-readable labels without requiring labels to be repeated in every
     * edge payload.
     * <p>
     * Intended response shape:
     * <pre>
     * {
     *   "0": "Authentication Service",
     *   "1": "User Database"
     * }
     * </pre>
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return numeric vertex id to vertex label mapping
     */
    @GetMapping("/api/v1/graphs/{graphId}/dictionary")
    public Map<Integer, String> getVertexIdToLabelMap(@PathVariable String graphId) {
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
    @PostMapping("/api/v1/graphs/{graphId}/dictionary/lookup/{graphMappingSchema}")
    public Map<Integer, String> lookupDictionary(
            @PathVariable GraphMappingSpec graphMappingSchema,
            @PathVariable String graphId,
            @RequestBody DictionaryQueryRequest request) {
        BiDirectionalDictionary dictionary = graphIngestionEngine.getDictionaryFor(
                graphMappingSchema,
                request.targetType(),
                request.name()
        );
        return dictionary.asMap();
    }

    /**
     * Retrieves the display label and list of attribute lists for a vertex by its numeric id.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @param numericId the numeric integer id of the vertex to lookup
     * @return vertex attributes response
     */
    @GetMapping("/api/v1/graphs/{graphId}/vertices/{numericId}/attributes")
    public VertexAttributesResponse getVertexAttributes(
            @PathVariable String graphId,
            @PathVariable int numericId) {
        return graphIngestionEngine.getVertexAttributes(numericId);
    }
}
