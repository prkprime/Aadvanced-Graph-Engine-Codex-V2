package com.self.help;

import com.self.help.enums.MappingTargetType;
import com.self.help.input.GraphMappingSpec;
import com.self.help.input.NodePropertyMappingSpec;
import com.self.help.input.RelationPropertyMappingSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GraphMappingSchemaTest {

    @Test
    public void testBuilderAndProperties() {
        GraphMappingSpec schema = GraphMappingSpec.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .addAttribute("type", "fromType", "toType")
                .addAttribute("dept", "fromDept", "toDept")
                .addRelation("relation")
                .addRelation("priority")
                .build();

        assertNotNull(schema);
        assertEquals(new NodePropertyMappingSpec("idPair", "fromId", "toId"), schema.idPair());
        assertEquals(new NodePropertyMappingSpec("labelPair", "fromLabel", "toLabel"), schema.labelPair());
        assertEquals(2, schema.nodeAttributes().size());
        assertEquals("type", schema.nodeAttributes().get(0).attributeName());
        assertEquals(new NodePropertyMappingSpec("type", "fromType", "toType"), schema.nodeAttributes().get(0));
        assertEquals("dept", schema.nodeAttributes().get(1).attributeName());
        assertEquals(new NodePropertyMappingSpec("dept", "fromDept", "toDept"), schema.nodeAttributes().get(1));
        assertEquals(List.of(new RelationPropertyMappingSpec("relation"), new RelationPropertyMappingSpec("priority")), schema.relations());
    }

    @Test
    public void testBuilderThrowsIfIdPairMissing() {
        assertThrows(IllegalStateException.class, () -> {
            GraphMappingSpec.builder()
                    .labelPair("fromLabel", "toLabel")
                    .build();
        });
    }

    @Test
    public void testEnumBasedHelpers() {
        MappingTargetType targetId = MappingTargetType.ID;
        MappingTargetType targetLabel = MappingTargetType.LABEL;
        MappingTargetType targetAttr = MappingTargetType.ATTRIBUTE;
        MappingTargetType targetRel = MappingTargetType.RELATION;

        GraphMappingSpec schema = GraphMappingSpec.builder()
                .idPair("fromId", "toId")
                .labelPair("fromLabel", "toLabel")
                .addAttribute("type", "fromType", "toType")
                .addRelation("relation")
                .build();

        // 1. Check ID retrieval
        assertEquals(new NodePropertyMappingSpec("idPair","fromId", "toId"), schema.getPairFor(targetId, null));

        // 2. Check explicit LABEL retrieval
        assertEquals(new NodePropertyMappingSpec("labelPair","fromLabel", "toLabel"), schema.getPairFor(targetLabel, null));

        // 3. Check ATTRIBUTE retrieval
        assertEquals(new NodePropertyMappingSpec("type", "fromType", "toType"), schema.getPairFor(targetAttr, "type"));

        // 4. Check RELATION retrieval
        assertEquals("relation", schema.getRelationColumnFor("relation"));
        assertEquals(new RelationPropertyMappingSpec("relation"), schema.getRelationFor("relation"));

        // 5. Check illegal parameters / missing attributes
        assertThrows(IllegalArgumentException.class, () -> schema.getPairFor(targetAttr, null));
        assertThrows(IllegalArgumentException.class, () -> schema.getPairFor(targetAttr, "missing"));
        assertThrows(IllegalArgumentException.class, () -> schema.getPairFor(targetRel, "relation"));
        assertThrows(IllegalArgumentException.class, () -> schema.getRelationColumnFor("missing"));

        // 6. Check LABEL fallback when labelPair is null
        GraphMappingSpec noLabelSchema = GraphMappingSpec.builder()
                .idPair("fromId", "toId")
                .build();
        assertEquals(new NodePropertyMappingSpec("idPair", "fromId", "toId"), noLabelSchema.getPairFor(targetLabel, null));
    }
}
