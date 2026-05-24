package com.self.help;

import com.self.help.enums.TraversalDirection;
import com.self.help.input.DictionaryQueryRequest;
import com.self.help.input.GraphMappingSpec;
import com.self.help.output.GraphEdgeResponse;
import com.self.help.output.GraphNodeStats;
import com.self.help.output.GraphSchemaResponse;
import com.self.help.output.KNeighborsResponse;
import com.self.help.output.VertexAttributesResponse;
import com.self.help.output.VertexDetailsResponse;
import com.self.help.storage.BiDirectionalDictionary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.self.help.input.VertexDeleteRequest;
import com.self.help.output.DeleteResponse;

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
     * Returns structural layout and statistical schema metadata for the graph.
     * <p>
     * Describes how raw columns are mapped to vertex ID, label, vertex-level attributes,
     * and edge-level relation columns, along with unique dictionary cardinality counts.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return structural and statistical schema metadata
     * @deprecated Marked as deprecated temporarily for review of how the UI consumes this contract.
     */
    @Deprecated
    @GetMapping("/api/v1/graphs/{graphId}/schema")
    public GraphSchemaResponse getSchema(@PathVariable String graphId) {
        return graphIngestionEngine.getSchema();
    }

    /**
     * Returns per-vertex edge statistics for every vertex in the graph.
     * <p>
     * The response is keyed by the compact numeric vertex id (dictionary-assigned
     * during ingestion) and maps to a {@link GraphNodeStats} describing the
     * number of active outgoing edges, active incoming edges, and their total.
     * Tombstoned (null-side) rows are excluded from both counts.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return map from numeric vertex id to its edge statistics
     */
    @GetMapping("/api/v1/graphs/{graphId}/stats")
    public Map<Integer, GraphNodeStats> getStats(@PathVariable String graphId) {
        return graphIngestionEngine.getVertexStats();
    }

    /**
     * Returns edge statistics for a specific vertex in the graph.
     * <p>
     * The response describes the {@link GraphNodeStats} of the given vertex,
     * including active outgoing edges, active incoming edges, and their sum.
     * Tombstoned (null-side) rows are excluded from both counts.
     *
     * @param graphId  logical graph identifier supplied in the URL
     * @param vertexId the numeric integer id of the vertex to lookup
     * @return the {@link GraphNodeStats} for the vertex, or {@code null} if the vertex is not found
     */
    @GetMapping("/api/v1/graphs/{graphId}/vertices/{vertexId}/stats")
    public GraphNodeStats getStats(
            @PathVariable String graphId,
            @PathVariable int vertexId) {
        return graphIngestionEngine.getVertexStats(vertexId);
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

    /**
     * Retrieves the K-hop neighborhood subgraph for a vertex.
     * <p>
     * Follows forward (outgoing), backward (incoming), or both edge paths to discover
     * all reached vertices and their connecting edges up to K steps.
     *
     * @param graphId   logical graph identifier supplied in the URL
     * @param numericId the numeric integer ID of the starting vertex
     * @param k         maximum hop count for neighborhood expansion (defaults to 1)
     * @param direction traversal path direction (defaults to BOTH)
     * @return the neighbors subgraph response
     */
    @GetMapping("/api/v1/graphs/{graphId}/vertices/{numericId}/neighbors")
    public KNeighborsResponse getKNeighbors(
            @PathVariable String graphId,
            @PathVariable int numericId,
            @RequestParam(defaultValue = "1") int k,
            @RequestParam(defaultValue = "BOTH") TraversalDirection direction) {
        return graphIngestionEngine.getKNeighbors(numericId, k, direction);
    }

    /**
     * Retrieves the detailed representation of a specific vertex by its numeric ID.
     * Checks if the vertex is active (has a valid source ID and label).
     *
     * @param graphId  logical graph identifier supplied in the URL
     * @param vertexId the numeric integer ID of the vertex
     * @return the vertex details, or {@code null} if the vertex is invalid or inactive
     */
    @GetMapping("/api/v1/graphs/{graphId}/vertices/{vertexId}")
    public VertexDetailsResponse getVertexDetails(
            @PathVariable String graphId,
            @PathVariable int vertexId) {
        return graphIngestionEngine.getVertexDetails(vertexId);
    }

    /**
     * Retrieves the detailed representation of the first active (non-deleted) vertex
     * in the graph database. Useful for initial UI focus or default dashboard selections.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @return the first available vertex details, or {@code null} if no active nodes exist
     */
    @GetMapping("/api/v1/graphs/{graphId}/vertices/first")
    public VertexDetailsResponse getFirstVertexDetails(@PathVariable String graphId) {
        return graphIngestionEngine.getFirstVertexDetails();
    }

    /**
     * Retrieves the detailed representation of the next active (non-deleted) vertex
     * following the specified vertex ID, wrapping around cyclically if needed.
     * Useful when the currently selected vertex has no active connections (tombstoned).
     *
     * @param graphId  logical graph identifier supplied in the URL
     * @param vertexId the numeric integer ID of the current vertex
     * @return the next available vertex details, or {@code null} if no other active nodes exist
     */
    @GetMapping("/api/v1/graphs/{graphId}/vertices/{vertexId}/next")
    public VertexDetailsResponse getNextVertexDetails(
            @PathVariable String graphId,
            @PathVariable int vertexId) {
        return graphIngestionEngine.getNextVertexDetails(vertexId);
    }

    /**
     * Soft-deletes a vertex according to the parameters in the {@link VertexDeleteRequest} body.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @param request deletion configuration payload
     * @return deletion status response
     */
    @PostMapping("/api/v1/graphs/{graphId}/vertices/delete")
    public DeleteResponse deleteVertex(
            @PathVariable String graphId,
            @RequestBody VertexDeleteRequest request) {
        boolean success = graphIngestionEngine.deleteVertex(request);
        String message = success ? "Vertex deleted successfully." : "Failed to delete vertex (invalid ID or already deleted).";
        return new DeleteResponse(success, message);
    }

    /**
     * Calculates and returns the map of impacted vertices (with their IDs and labels)
     * if the specified deletion configuration request were to be executed.
     *
     * @param graphId logical graph identifier supplied in the URL
     * @param request deletion configuration payload
     * @return map of impacted vertex ID to display label
     */
    @PostMapping("/api/v1/graphs/{graphId}/vertices/impacted")
    public Map<Integer, String> getImpactedVertices(
            @PathVariable String graphId,
            @RequestBody VertexDeleteRequest request) {
        return graphIngestionEngine.getImpactedVertices(request);
    }

    /**
     * Soft-deletes a specific edge by its RawDataStore row index.
     *
     * @param graphId logical graph identifier
     * @param rowId   the raw row ID of the edge
     * @return deletion status response
     */
    @DeleteMapping("/api/v1/graphs/{graphId}/edges/{rowId}")
    public DeleteResponse deleteEdge(
            @PathVariable String graphId,
            @PathVariable int rowId) {
        boolean success = graphIngestionEngine.deleteEdge(rowId);
        String message = success ? "Edge deleted successfully." : "Failed to delete edge (invalid rowId or already deleted).";
        return new DeleteResponse(success, message);
    }

    /**
     * Soft-deletes all active edges between specific FROM and TO vertices.
     *
     * @param graphId logical graph identifier
     * @param fromId  numeric integer ID of the source vertex
     * @param toId    numeric integer ID of the target vertex
     * @return deletion status response
     */
    @DeleteMapping("/api/v1/graphs/{graphId}/edges")
    public DeleteResponse deleteEdge(
            @PathVariable String graphId,
            @RequestParam int fromId,
            @RequestParam int toId) {
        boolean success = graphIngestionEngine.deleteEdge(fromId, toId);
        String message = success ? "Edges between specified nodes deleted successfully." : "No active edges found between specified nodes.";
        return new DeleteResponse(success, message);
    }
}
