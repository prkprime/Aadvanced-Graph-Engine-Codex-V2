package com.self.help.input;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Maps a node property or attribute to its FROM-node and TO-node source columns.
 */
public record NodePropertyMappingSpec(
        @NotNull String attributeName,
        @NotNull String fromColumnName,
        @NotNull String toColumnName
) implements java.io.Serializable {
    public NodePropertyMappingSpec {
        Objects.requireNonNull(fromColumnName, "fromColumnName");
        Objects.requireNonNull(toColumnName, "toColumnName");
    }
}
