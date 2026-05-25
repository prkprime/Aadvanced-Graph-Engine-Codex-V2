package com.self.help.output;

import java.util.List;
import java.util.Map;

/**
 * Subgraph response representing the K-hop neighborhood of a starting vertex,
 * containing all reached vertices and their connecting edges.
 */
public record KNeighborsResponse(
        Map<Integer, String> vertices,
        List<GraphEdgeResponse> edges
) implements java.io.Serializable {}
