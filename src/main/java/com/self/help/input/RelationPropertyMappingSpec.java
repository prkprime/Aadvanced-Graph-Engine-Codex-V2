package com.self.help.input;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Describes a relation column carried by an edge between two nodes.
 */
public record RelationPropertyMappingSpec(@NotNull String columnName) implements java.io.Serializable {
    public RelationPropertyMappingSpec {
        Objects.requireNonNull(columnName, "columnName");
    }
}
