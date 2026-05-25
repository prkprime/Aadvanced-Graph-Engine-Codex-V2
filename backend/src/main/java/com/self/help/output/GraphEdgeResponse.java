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
 * @param fromVertexId compact numeric vertex id (dictionary-assigned) for the from-side of the edge row,
 *                     or {@code null} when the from-node was absent (partial row)
 * @param toVertexId   compact numeric vertex id (dictionary-assigned) for the to-side of the edge row,
 *                     or {@code null} when the to-node was absent (partial row)
 * @param relations    relation values in mapping-spec order
 */
public record GraphEdgeResponse(
        @Nullable Integer fromVertexId,
        @Nullable Integer toVertexId,
        @NotNull List<@Nullable String> relations) implements java.io.Serializable {
    public GraphEdgeResponse {
        Objects.requireNonNull(relations, "relations");
        relations = Collections.unmodifiableList(new ArrayList<>(relations));
    }
}
