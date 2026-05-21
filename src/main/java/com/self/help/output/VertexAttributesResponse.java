package com.self.help.output;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Response carrying resolved id, display label and ordered attribute lists for a vertex.
 */
public record VertexAttributesResponse(
        @NotNull String resolvedId,
        @NotNull String resolvedLabel,
        @NotNull List<List<String>> resolvedAttributes
) implements java.io.Serializable {
    public VertexAttributesResponse {
        Objects.requireNonNull(resolvedAttributes, "attributes");
        List<List<String>> copy = new ArrayList<>(resolvedAttributes.size());
        for (List<String> inner : resolvedAttributes) {
            copy.add(inner != null ? Collections.unmodifiableList(new ArrayList<>(inner)) : Collections.emptyList());
        }
        resolvedAttributes = Collections.unmodifiableList(copy);
    }
}
