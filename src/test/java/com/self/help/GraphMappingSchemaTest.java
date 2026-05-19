package com.self.help;

import com.self.help.input.GraphMappingSchema;
import com.self.help.input.MappingSpec;
import com.self.help.input.PairSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GraphMappingSchemaTest {

    @Test
    public void testBuilderAndProperties() {
        GraphMappingSchema schema = GraphMappingSchema.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .addAttribute("type", "fromType", "toType")
                .addAttribute("dept", "fromDept", "toDept")
                .addRelation("relation")
                .addRelation("priority")
                .build();

        assertNotNull(schema);
        assertEquals(new PairSpec("fromId", "toId"), schema.idPair());
        assertEquals(new PairSpec("fromLabel", "toLabel"), schema.labelPair());
        assertEquals(2, schema.attributePairs().size());
        assertEquals("type", schema.attributePairs().get(0).attributeName());
        assertEquals(new PairSpec("fromType", "toType"), schema.attributePairs().get(0).columnPair());
        assertEquals("dept", schema.attributePairs().get(1).attributeName());
        assertEquals(new PairSpec("fromDept", "toDept"), schema.attributePairs().get(1).columnPair());
        assertEquals(List.of("relation", "priority"), schema.relationColumns());
    }

    @Test
    public void testBuilderThrowsIfIdPairMissing() {
        assertThrows(IllegalStateException.class, () -> {
            GraphMappingSchema.builder()
                    .labelPair("fromLabel", "toLabel")
                    .build();
        });
    }

    @Test
    public void testCompilationToMappingSpec() {
        GraphMappingSchema schema = GraphMappingSchema.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .addAttribute("type", "fromType", "toType")
                .addAttribute("dept", "fromDept", "toDept")
                .addRelation("relation")
                .addRelation("priority")
                .build();

        MappingSpec mappingSpec = schema.toMappingSpec();

        assertNotNull(mappingSpec);
        // Verify from node spec
        assertEquals("fromId", mappingSpec.getFromNodeSpec().getIdColumnName());
        assertEquals("fromLabel", mappingSpec.getFromNodeSpec().getLabelColumnName());
        assertEquals(List.of("fromType", "fromDept"), mappingSpec.getFromNodeSpec().getNodeAttributeNames());

        // Verify to node spec
        assertEquals("toId", mappingSpec.getToNodeSpec().getIdColumnName());
        assertEquals("toLabel", mappingSpec.getToNodeSpec().getLabelColumnName());
        assertEquals(List.of("toType", "toDept"), mappingSpec.getToNodeSpec().getNodeAttributeNames());

        // Verify relations
        assertEquals(List.of("relation", "priority"), mappingSpec.getRelationColumnNames());
    }

    @Test
    public void testEnumBasedHelpers() {
        com.self.help.input.MappingTargetType targetId = com.self.help.input.MappingTargetType.ID;
        com.self.help.input.MappingTargetType targetLabel = com.self.help.input.MappingTargetType.LABEL;
        com.self.help.input.MappingTargetType targetAttr = com.self.help.input.MappingTargetType.ATTRIBUTE;
        com.self.help.input.MappingTargetType targetRel = com.self.help.input.MappingTargetType.RELATION;

        GraphMappingSchema schema = GraphMappingSchema.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .addAttribute("type", "fromType", "toType")
                .addRelation("relation")
                .build();

        // 1. Check ID retrieval
        assertEquals(new PairSpec("fromId", "toId"), schema.getPairFor(targetId, null));

        // 2. Check explicit LABEL retrieval
        assertEquals(new PairSpec("fromLabel", "toLabel"), schema.getPairFor(targetLabel, null));

        // 3. Check ATTRIBUTE retrieval
        assertEquals(new PairSpec("fromType", "toType"), schema.getPairFor(targetAttr, "type"));

        // 4. Check RELATION retrieval
        assertEquals("relation", schema.getRelationColumnFor("relation"));

        // 5. Check illegal parameters / missing attributes
        assertThrows(IllegalArgumentException.class, () -> schema.getPairFor(targetAttr, null));
        assertThrows(IllegalArgumentException.class, () -> schema.getPairFor(targetAttr, "missing"));
        assertThrows(IllegalArgumentException.class, () -> schema.getPairFor(targetRel, "relation"));
        assertThrows(IllegalArgumentException.class, () -> schema.getRelationColumnFor("missing"));

        // 6. Check LABEL fallback when labelPair is null
        GraphMappingSchema noLabelSchema = GraphMappingSchema.builder()
                .idPair("fromId", "toId")
                .build();
        assertEquals(new PairSpec("fromId", "toId"), noLabelSchema.getPairFor(targetLabel, null));
    }
}

