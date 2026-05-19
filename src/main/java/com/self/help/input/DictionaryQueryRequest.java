package com.self.help.input;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;

/**
 * Request payload for dictionary lookup.
 */
public record DictionaryQueryRequest(
        @NotNull GraphMappingSchema schema,
        @NotNull MappingTargetType targetType,
        @Nullable String name
) {
    public DictionaryQueryRequest {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(targetType, "targetType");
    }
}
