package com.self.help.output;

/**
 * Detailed representation of a single vertex including its compact ID,
 * original source ID string, and resolved display label.
 */
public record VertexDetailsResponse(
        int id,
        String sourceId,
        String sourceLabel
) implements java.io.Serializable {}
