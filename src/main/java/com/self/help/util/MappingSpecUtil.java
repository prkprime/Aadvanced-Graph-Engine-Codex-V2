package com.self.help.util;

import com.self.help.input.MappingSpec;
import com.self.help.input.NodeSpec;
import com.self.help.legacy.RawDataStore;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MappingSpecUtil {

    /**
     *
     * @param dataCube
     * @param spec
     */
    public static void validateSpec(RawDataStore dataCube, MappingSpec spec) {
        NodeSpec fromSpec = spec.getFromNodeSpec();
        NodeSpec toSpec = spec.getToNodeSpec();

        List<String> fromAttrs = fromSpec.getNodeAttributeNames();
        List<String> toAttrs = toSpec.getNodeAttributeNames();

        if (fromAttrs.size() != toAttrs.size()) {
            throw new IllegalArgumentException("Attribute size mismatch: fromNodeSpec has " + fromAttrs.size() + " attributes, but toNodeSpec has " + toAttrs.size());
        }

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
        for (String attr : fromAttrs) {
            verifyColumnExists(dataCube, attr);
        }
        for (String attr : toAttrs) {
            verifyColumnExists(dataCube, attr);
        }
        for (String rel : spec.getRelationColumnNames()) {
            verifyColumnExists(dataCube, rel);
        }
    }

    private static void verifyColumnExists(RawDataStore dataCube, String columnName) {
        if (columnName == null || columnName.trim().isEmpty() || dataCube.getColumnIndex(columnName) == -1) {
            throw new IllegalArgumentException("Mapping violation: Column '" + columnName + "' does not exist in the DataCube.");
        }
    }
}
