package com.self.help.output;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Row-wise edge response hydrated from the encoded graph stores.
 *
 * @param fromVertexId source vertex id for the edge row
 * @param toVertexId target vertex id for the edge row
 * @param relations relation values in mapping-spec order
 */
public record GraphEdgeResponse(
        @Nullable String fromVertexId,
        @Nullable String toVertexId,
        @NotNull List<@Nullable String> relations) implements java.io.Serializable {
    public GraphEdgeResponse {
        Objects.requireNonNull(relations, "relations");
        relations = Collections.unmodifiableList(new ArrayList<>(relations));
    }
}
