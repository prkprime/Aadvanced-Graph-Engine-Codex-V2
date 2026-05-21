package com.self.help.output;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Response carrying resolved display label and ordered attribute lists for a vertex.
 */
public record VertexAttributesResponse(
        @Nullable String label,
        @NotNull List<List<String>> attributes
) implements java.io.Serializable {
    public VertexAttributesResponse {
        Objects.requireNonNull(attributes, "attributes");
        List<List<String>> copy = new ArrayList<>(attributes.size());
        for (List<String> inner : attributes) {
            copy.add(inner != null ? Collections.unmodifiableList(new ArrayList<>(inner)) : Collections.emptyList());
        }
        attributes = Collections.unmodifiableList(copy);
    }
}
