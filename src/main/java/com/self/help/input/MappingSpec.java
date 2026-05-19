package com.self.help.input;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Describes how raw source columns should be interpreted as graph edges.
 * The mapping contains the from-node specification, the to-node specification,
 * and relation columns that belong to the edge between those node sides.
 */
public record MappingSpec(@NotNull NodeSpec fromNodeSpec, @NotNull NodeSpec toNodeSpec,
                          @NotNull List<String> relations) implements java.io.Serializable {
    /**
     * Creates a graph mapping with an immutable relation-column list.
     *
     * @param fromNodeSpec source-column mapping for the edge's from-node side
     * @param toNodeSpec source-column mapping for the edge's to-node side
     * @param relations source columns that describe edge/relation properties
     */
    public MappingSpec {
        Objects.requireNonNull(fromNodeSpec, "fromNodeSpec");
        Objects.requireNonNull(toNodeSpec, "toNodeSpec");
        relations = List.copyOf(relations);
    }

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
     * Returns the total number of source columns participating in this mapping.
     *
     * @return total number of participating source columns
     */
    public int getNumberOfTotalColumns() {
        return getFromNodeSpec().getNumberOfTotalColumns() +
                getToNodeSpec().getNumberOfTotalColumns() +
                getRelationColumnNames().size();
    }
}
