package com.self.help.util;

import com.self.help.input.GraphMappingSpec;
import com.self.help.input.NodePropertyMappingSpec;
import com.self.help.input.RelationPropertyMappingSpec;
import com.self.help.legacy.RawDataStore;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GraphMappingSchemaValidator {

    /**
     * Validates that a graph mapping schema can be resolved against the supplied raw
     * data store. Each mapped column must exist, and from/to node mappings must not reuse
     * the same source columns.
     *
     * @param dataCube raw source store that owns the mapped columns
     * @param schema graph mapping schema to validate
     * @throws IllegalArgumentException when the schema is inconsistent or references missing columns
     */
    public static void validate(RawDataStore dataCube, GraphMappingSpec schema) {
        Objects.requireNonNull(dataCube, "dataCube");
        Objects.requireNonNull(schema, "schema");

        List<String> fromAttrs = schema.nodeAttributes().stream()
                .map(NodePropertyMappingSpec::fromColumnName)
                .toList();
        List<String> toAttrs = schema.nodeAttributes().stream()
                .map(NodePropertyMappingSpec::toColumnName)
                .toList();
        List<String> relationColumns = schema.relations().stream()
                .map(RelationPropertyMappingSpec::columnName)
                .toList();

        verifyNoDuplicates("fromNodeSpec attributes", fromAttrs);
        verifyNoDuplicates("toNodeSpec attributes", toAttrs);
        verifyNoDuplicates("relation columns", relationColumns);

        Set<String> fromCore = new HashSet<>();
        fromCore.add(schema.idPair().fromColumnName());
        if (schema.labelPair() != null) {
            fromCore.add(schema.labelPair().fromColumnName());
        }

        Set<String> fromAttrSet = new HashSet<>(fromAttrs);
        Set<String> fromIntraOverlap = new HashSet<>(fromCore);
        fromIntraOverlap.retainAll(fromAttrSet);
        if (!fromIntraOverlap.isEmpty()) {
            throw new IllegalArgumentException("Intra-node violation in fromNodeSpec. Overlap: " + fromIntraOverlap);
        }

        Set<String> toCore = new HashSet<>();
        toCore.add(schema.idPair().toColumnName());
        if (schema.labelPair() != null) {
            toCore.add(schema.labelPair().toColumnName());
        }

        Set<String> toAttrSet = new HashSet<>(toAttrs);
        Set<String> toIntraOverlap = new HashSet<>(toCore);
        toIntraOverlap.retainAll(toAttrSet);
        if (!toIntraOverlap.isEmpty()) {
            throw new IllegalArgumentException("Intra-node violation in toNodeSpec. Overlap: " + toIntraOverlap);
        }

        Set<String> fromAllCols = new HashSet<>(fromCore);
        fromAllCols.addAll(fromAttrSet);
        Set<String> toAllCols = new HashSet<>(toCore);
        toAllCols.addAll(toAttrSet);

        Set<String> crossOverlap = new HashSet<>(fromAllCols);
        crossOverlap.retainAll(toAllCols);
        if (!crossOverlap.isEmpty()) {
            throw new IllegalArgumentException("Disjoint violation: fromNodeSpec and toNodeSpec share columns: " + crossOverlap);
        }

        verifyColumnExists(dataCube, schema.idPair().fromColumnName());
        verifyColumnExists(dataCube, schema.idPair().toColumnName());
        if (schema.labelPair() != null) {
            verifyColumnExists(dataCube, schema.labelPair().fromColumnName());
            verifyColumnExists(dataCube, schema.labelPair().toColumnName());
        }
        verifyColumnsExist(dataCube, fromAttrs);
        verifyColumnsExist(dataCube, toAttrs);
        verifyColumnsExist(dataCube, relationColumns);
    }

    private static void verifyNoDuplicates(String groupName, List<String> columnNames) {
        Set<String> uniqueNames = new HashSet<>();
        Set<String> duplicateNames = new HashSet<>();
        for (String columnName : columnNames) {
            if (!uniqueNames.add(columnName)) {
                duplicateNames.add(columnName);
            }
        }

        if (!duplicateNames.isEmpty()) {
            throw new IllegalArgumentException("Duplicate columns in " + groupName + ": " + duplicateNames);
        }
    }

    private static void verifyColumnsExist(RawDataStore dataCube, List<String> columnNames) {
        for (String columnName : columnNames) {
            verifyColumnExists(dataCube, columnName);
        }
    }

    private static void verifyColumnExists(RawDataStore dataCube, String columnName) {
        if (columnName == null || columnName.trim().isEmpty() || dataCube.getColumnIndex(columnName) == -1) {
            throw new IllegalArgumentException("Mapping violation: column '" + columnName + "' does not exist in the raw data store.");
        }
    }
}
