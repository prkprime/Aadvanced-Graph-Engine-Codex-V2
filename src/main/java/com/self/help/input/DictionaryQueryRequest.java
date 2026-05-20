package com.self.help.input;

import com.self.help.enums.MappingTargetType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;

/**
 * Request payload for dictionary lookup.
 */
public record DictionaryQueryRequest(
        @NotNull MappingTargetType targetType,
        @Nullable String name
) implements java.io.Serializable {
    public DictionaryQueryRequest {
        Objects.requireNonNull(targetType, "targetType");
    }
}
