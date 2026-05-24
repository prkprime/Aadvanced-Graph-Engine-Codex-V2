package com.self.help.input;

import java.io.Serializable;

/**
 * Request payload model representing the configuration for a vertex delete mutation.
 * Supports cascading deletions upstream and downstream along active graph edges.
 */
public record VertexDeleteRequest(
    Integer nodeId,
    boolean downStream,
    boolean upstream
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
