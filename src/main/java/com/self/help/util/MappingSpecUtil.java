package com.self.help.util;

import com.self.help.input.MappingSpec;
import com.self.help.input.NodeSpec;
import com.self.help.legacy.RawDataStore;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MappingSpecUtil {

    /**
     * Validates that a graph mapping can be resolved against the supplied raw
     * data store. From-node and to-node attributes must be paired by position,
     * each mapped column must exist, and from/to node mappings must not reuse
     * the same source columns.
     *
     * @param dataCube raw source store that owns the mapped columns
     * @param spec graph mapping to validate
     * @throws IllegalArgumentException when the mapping is inconsistent or references missing columns
     */
    public static void validateSpec(RawDataStore dataCube, MappingSpec spec) {
        Objects.requireNonNull(dataCube, "dataCube");
        Objects.requireNonNull(spec, "spec");

        NodeSpec fromSpec = spec.getFromNodeSpec();
        NodeSpec toSpec = spec.getToNodeSpec();

        List<String> fromAttrs = fromSpec.getNodeAttributeNames();
        List<String> toAttrs = toSpec.getNodeAttributeNames();

        if (fromAttrs.size() != toAttrs.size()) {
            throw new IllegalArgumentException("Attribute size mismatch: fromNodeSpec has " + fromAttrs.size() +
                    " attributes, but toNodeSpec has " + toAttrs.size());
        }

        verifyNoDuplicates("fromNodeSpec attributes", fromAttrs);
        verifyNoDuplicates("toNodeSpec attributes", toAttrs);
        verifyNoDuplicates("relation columns", spec.getRelationColumnNames());

        Set<String> fromCore = new HashSet<>();
        fromCore.add(fromSpec.getIdColumnName());
        fromCore.add(fromSpec.getLabelColumnName());

        Set<String> fromAttrSet = new HashSet<>(fromAttrs);
        Set<String> fromIntraOverlap = new HashSet<>(fromCore);
        fromIntraOverlap.retainAll(fromAttrSet);
        if (!fromIntraOverlap.isEmpty()) {
            throw new IllegalArgumentException("Intra-node violation in fromNodeSpec. Overlap: " + fromIntraOverlap);
        }

        Set<String> toCore = new HashSet<>();
        toCore.add(toSpec.getIdColumnName());
        toCore.add(toSpec.getLabelColumnName());

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

        verifyColumnExists(dataCube, fromSpec.getIdColumnName());
        verifyColumnExists(dataCube, toSpec.getIdColumnName());
        verifyColumnExists(dataCube, fromSpec.getLabelColumnName());
        verifyColumnExists(dataCube, toSpec.getLabelColumnName());
        verifyColumnsExist(dataCube, fromAttrs);
        verifyColumnsExist(dataCube, toAttrs);
        verifyColumnsExist(dataCube, spec.getRelationColumnNames());
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
