package com.self.help.input;

import com.self.help.enums.MappingTargetType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unified schema for defining graph mappings across nodes and relations.
 */
public record GraphMappingSpec(
        @NotNull NodePropertyMappingSpec idPair,
        @Nullable NodePropertyMappingSpec labelPair,
        @NotNull List<NodePropertyMappingSpec> nodeAttributes,
        @NotNull List<RelationPropertyMappingSpec> relations
) implements java.io.Serializable {
    public GraphMappingSpec {
        Objects.requireNonNull(idPair, "idPair");
        nodeAttributes = nodeAttributes.stream()
                .map(GraphMappingSpec::requireNamedNodeAttribute)
                .toList();
        relations = List.copyOf(relations);
    }

    /**
     * Resolves the node-pair mapping for the specified target type and optional name.
     * Applicable for ID, LABEL, and ATTRIBUTE targets. If the LABEL target is
     * requested but no explicit labelPair is configured, it falls back to the idPair.
     *
     * @param targetType the target type (ID, LABEL, or ATTRIBUTE)
     * @param name       the name of the attribute (ignored for ID and LABEL)
     * @return the associated node attribute mapping
     * @throws IllegalArgumentException if targetType is RELATION or if name is not found
     */
    @NotNull
    public NodePropertyMappingSpec getPairFor(@NotNull MappingTargetType targetType, @Nullable String name) {
        return switch (targetType) {
            case ID -> idPair;
            case LABEL -> labelPair != null ? labelPair : idPair;
            case ATTRIBUTE -> {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Attribute name is required to resolve ATTRIBUTE mapping");
                }
                yield nodeAttributes.stream()
                        .filter(ap -> ap.attributeName().equalsIgnoreCase(name))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Attribute '" + name + "' not found in mapping schema"));
            }
            case RELATION ->
                    throw new IllegalArgumentException("RELATION target uses RelationPropertyMappingSpec, not NodePropertyMappingSpec");
        };
    }

    /**
     * Resolves the relation mapping for the specified relation column name.
     *
     * @param name the relation column name
     * @return the matched relation spec from the schema
     * @throws IllegalArgumentException if the relation column name is not found
     */
    @NotNull
    public RelationPropertyMappingSpec getRelationFor(@NotNull String name) {
        Objects.requireNonNull(name, "name");
        return relations.stream()
                .filter(relation -> relation.columnName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Relation column '" + name + "' not found in mapping schema"));
    }

    /**
     * Resolves the single column mapping for the specified RELATION target.
     *
     * @param name the relation column name
     * @return the matched relation column name from the schema
     * @throws IllegalArgumentException if the relation column name is not found
     */
    @NotNull
    public String getRelationColumnFor(@NotNull String name) {
        return getRelationFor(name).columnName();
    }

    /**
     * Backward-compatible view of node attribute mappings.
     */
    @NotNull
    public List<NodePropertyMappingSpec> attributePairs() {
        return nodeAttributes;
    }

    /**
     * Backward-compatible view of relation columns.
     */
    @NotNull
    public List<String> relationColumns() {
        return relations.stream()
                .map(RelationPropertyMappingSpec::columnName)
                .toList();
    }

    /**
     * Creates a new builder to fluently construct a GraphMappingSpec.
     *
     * @return fluent builder
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for GraphMappingSpec.
     */
    public static class Builder {
        private NodePropertyMappingSpec idPair;
        private NodePropertyMappingSpec labelPair;
        private final List<NodePropertyMappingSpec> nodeAttributes = new ArrayList<>();
        private final List<RelationPropertyMappingSpec> relations = new ArrayList<>();

        public Builder idPair(@NotNull String idPairAttributeName, @NotNull String fromColumnName, @NotNull String toColumnName) {
            this.idPair = new NodePropertyMappingSpec(idPairAttributeName, fromColumnName, toColumnName);
            return this;
        }

        public Builder idPair(@NotNull String fromColumnName, @NotNull String toColumnName) {
            this.idPair = new NodePropertyMappingSpec("idPair", fromColumnName, toColumnName);
            return this;
        }

        public Builder idPair(@NotNull NodePropertyMappingSpec idPair) {
            this.idPair = Objects.requireNonNull(idPair, "idPair");
            return this;
        }

        public Builder labelPair(@NotNull String labelPairAttributeName, @NotNull String fromColumnName, @NotNull String toColumnName) {
            this.labelPair = new NodePropertyMappingSpec(labelPairAttributeName, fromColumnName, toColumnName);
            return this;
        }

        public Builder labelPair(@NotNull String fromColumnName, @NotNull String toColumnName) {
            this.labelPair = new NodePropertyMappingSpec("labelPair", fromColumnName, toColumnName);
            return this;
        }

        public Builder labelPair(@Nullable NodePropertyMappingSpec labelPair) {
            this.labelPair = labelPair;
            return this;
        }

        public Builder addAttribute(@NotNull String attributeName, @NotNull String fromColumnName, @NotNull String toColumnName) {
            this.nodeAttributes.add(requireNamedNodeAttribute(new NodePropertyMappingSpec(attributeName, fromColumnName, toColumnName)));
            return this;
        }

        public Builder addAttribute(@NotNull NodePropertyMappingSpec nodeAttribute) {
            this.nodeAttributes.add(requireNamedNodeAttribute(nodeAttribute));
            return this;
        }

        public Builder addRelation(@NotNull String columnName) {
            this.relations.add(new RelationPropertyMappingSpec(columnName));
            return this;
        }

        public Builder addRelation(@NotNull RelationPropertyMappingSpec relation) {
            this.relations.add(Objects.requireNonNull(relation, "relation"));
            return this;
        }

        public GraphMappingSpec build() {
            if (idPair == null) {
                throw new IllegalStateException("idPair must be specified");
            }
            return new GraphMappingSpec(idPair, labelPair, nodeAttributes, relations);
        }
    }

    private static NodePropertyMappingSpec requireNamedNodeAttribute(NodePropertyMappingSpec nodeAttribute) {
        Objects.requireNonNull(nodeAttribute, "nodeAttribute");
        String attributeName = nodeAttribute.attributeName();
        if (attributeName.isBlank()) {
            throw new IllegalArgumentException("nodeAttribute.attributeName must be specified");
        }
        return nodeAttribute;
    }
}
