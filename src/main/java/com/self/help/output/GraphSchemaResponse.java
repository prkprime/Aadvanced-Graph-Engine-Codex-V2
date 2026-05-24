package com.self.help.output;

import java.util.List;

/**
 * Metadata schema response describing the structural layout, active attributes,
 * relation categories, and dictionary cardinality metrics of the graph.
 */
public record GraphSchemaResponse(
        IdPairSchema idPair,
        LabelPairSchema labelPair,
        List<AttributeSchema> attributes,
        List<RelationSchema> relations,
        StorageMetricsSchema storageMetrics
) implements java.io.Serializable {

    /**
     * Schema info for the vertex ID pair column mapping.
     */
    public record IdPairSchema(String fromColumn, String toColumn, int uniqueVertexCount) implements java.io.Serializable {}

    /**
     * Schema info for the vertex Label pair column mapping.
     */
    public record LabelPairSchema(String fromColumn, String toColumn, int uniqueLabelCount) implements java.io.Serializable {}

    /**
     * Schema info for a vertex-level attribute.
     */
    public record AttributeSchema(String name, String fromColumn, String toColumn, int uniqueValueCount) implements java.io.Serializable {}

    /**
     * Schema info for an edge-level relation column.
     */
    public record RelationSchema(String name, int uniqueValueCount) implements java.io.Serializable {}

    /**
     * Current storage metrics and sizing statistics.
     */
    public record StorageMetricsSchema(int ingestedRowCount, int activeVertexCount) implements java.io.Serializable {}
}
