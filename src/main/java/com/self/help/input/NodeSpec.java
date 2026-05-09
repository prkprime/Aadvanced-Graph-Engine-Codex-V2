package com.self.help.input;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Describes how one node side of an edge is mapped from raw source columns.
 * A node has an id column, a label column, and zero or more attribute columns.
 * If no label column is supplied, the id column also acts as the label column.
 */
public record NodeSpec(@NotNull String idColumnName, @Nullable String labelColumnName, @Nullable List<String> attributes) {
    /**
     * Creates a node mapping from raw source columns.
     * When no label column is supplied, the id column is also used as the label
     * column. When no attributes are supplied, the node has no attribute columns.
     *
     * @param idColumnName source column containing the node id
     * @param labelColumnName source column containing the node label, or {@code null} to reuse the id column
     * @param attributes source columns containing node attributes, or {@code null} for no attributes
     */
    public NodeSpec(@NotNull String idColumnName, @Nullable String labelColumnName, @Nullable List<String> attributes) {
        this.idColumnName = idColumnName;
        this.labelColumnName = Objects.requireNonNullElse(labelColumnName, idColumnName);
        this.attributes = Objects.requireNonNullElse(attributes, Collections.emptyList());
    }

    /**
     * Returns the source column used as the node label.
     *
     * @return label column name, defaulting to the id column name when no label was supplied
     */
    @NotNull
    public String getLabelColumnName() {
        return labelColumnName;
    }

    /**
     * Returns the source column used as the node id.
     *
     * @return id column name
     */
    @NotNull
    public String getIdColumnName() {
        return idColumnName;
    }

    /**
     * Returns the source columns used as node attributes.
     *
     * @return attribute column names in projection order
     */
    @NotNull
    public List<String> getNodeAttributeNames() {
        return attributes;
    }

    /**
     * Returns the number of columns participating in the Node Spec
     *
     * @return number of columns participating in the node spec
     */
    public int getNumberOfTotalColumns() {
        return 1 + 1 + getNodeAttributeNames().size();
    }
}
