package com.self.help.input;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Describes how raw source columns should be interpreted as graph edges.
 * The mapping contains the from-node specification, the to-node specification,
 * and relation columns that belong to the edge between those node sides.
 */
public record MappingSpec(@NotNull NodeSpec fromNodeSpec, @NotNull NodeSpec toNodeSpec,
                          @NotNull List<String> relations) {
    /**
     * Returns the source-column mapping for the edge's from-node side.
     *
     * @return from-node mapping
     */
    @NotNull
    public NodeSpec getFromNodeSpec() {
        return fromNodeSpec;
    }

    /**
     * Returns the source-column mapping for the edge's to-node side.
     *
     * @return to-node mapping
     */
    @NotNull
    public NodeSpec getToNodeSpec() {
        return toNodeSpec;
    }

    /**
     * Returns the source columns that describe edge/relation properties.
     *
     * @return relation column names in projection order
     */
    public List<String> getRelationColumnNames() {
        return relations;
    }

    /**
     * Returns the total number of columns that are participating in the MappingSpec
     *
     * @return total number of participating columns in Mapping Spec
     */
    public int getNumberOfTotalColumns() {
        return getFromNodeSpec().getNumberOfTotalColumns() +
                getToNodeSpec().getNumberOfTotalColumns() +
                getRelationColumnNames().size();
    }
}
