package com.self.help.input;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unified, error-proof schema for defining graph mappings using pairs for nodes
 * and single elements for edges. Avoids attribute size and order mismatches.
 */
public record GraphMappingSchema(
        @NotNull PairSpec idPair,
        @Nullable PairSpec labelPair,
        @NotNull List<AttributePair> attributePairs,
        @NotNull List<String> relationColumns
) implements java.io.Serializable {
    public GraphMappingSchema {
        Objects.requireNonNull(idPair, "idPair");
        attributePairs = attributePairs == null ? List.of() : List.copyOf(attributePairs);
        relationColumns = relationColumns == null ? List.of() : List.copyOf(relationColumns);
    }

    /**
     * Resolves the pair mapping for the specified target type and optional name.
     * Applicable for ID, LABEL, and ATTRIBUTE targets. If the LABEL target is
     * requested but no explicit labelPair is configured, it falls back to the idPair.
     *
     * @param targetType the target type (ID, LABEL, or ATTRIBUTE)
     * @param name the name of the attribute (ignored for ID and LABEL)
     * @return the associated PairSpec mapping
     * @throws IllegalArgumentException if targetType is RELATION or if name is not found
     */
    @NotNull
    public PairSpec getPairFor(@NotNull MappingTargetType targetType, @Nullable String name) {
        return switch (targetType) {
            case ID -> idPair;
            case LABEL -> labelPair != null ? labelPair : idPair;
            case ATTRIBUTE -> {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Attribute name is required to resolve ATTRIBUTE mapping");
                }
                yield attributePairs.stream()
                        .filter(ap -> ap.attributeName().equalsIgnoreCase(name))
                        .map(AttributePair::columnPair)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Attribute '" + name + "' not found in mapping schema"));
            }
            case RELATION -> throw new IllegalArgumentException("RELATION target uses a single column, not a PairSpec");
        };
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
        Objects.requireNonNull(name, "name");
        return relationColumns.stream()
                .filter(col -> col.equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Relation column '" + name + "' not found in mapping schema"));
    }


    /**
     * Translates this unified pair-based schema into the engine's internal MappingSpec.
     * This provides a bridge to the existing ingestion engine with zero breaking changes.
     *
     * @return equivalent MappingSpec
     */
    @NotNull
    public MappingSpec toMappingSpec() {
        List<String> fromAttrs = attributePairs.stream()
                .map(ap -> ap.columnPair().fromColumnName())
                .toList();

        List<String> toAttrs = attributePairs.stream()
                .map(ap -> ap.columnPair().toColumnName())
                .toList();

        NodeSpec fromNode = new NodeSpec(
                idPair.fromColumnName(),
                labelPair != null ? labelPair.fromColumnName() : null,
                fromAttrs
        );

        NodeSpec toNode = new NodeSpec(
                idPair.toColumnName(),
                labelPair != null ? labelPair.toColumnName() : null,
                toAttrs
        );

        return new MappingSpec(fromNode, toNode, relationColumns);
    }

    /**
     * Creates a new builder to fluently construct a GraphMappingSchema.
     *
     * @return fluent builder
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for GraphMappingSchema.
     */
    public static class Builder {
        private PairSpec idPair;
        private PairSpec labelPair;
        private final List<AttributePair> attributePairs = new ArrayList<>();
        private final List<String> relationColumns = new ArrayList<>();

        public Builder idPair(@NotNull String fromColumnName, @NotNull String toColumnName) {
            this.idPair = new PairSpec(fromColumnName, toColumnName);
            return this;
        }

        public Builder idPair(@NotNull PairSpec idPair) {
            this.idPair = Objects.requireNonNull(idPair, "idPair");
            return this;
        }

        public Builder labelPair(@NotNull String fromColumnName, @NotNull String toColumnName) {
            this.labelPair = new PairSpec(fromColumnName, toColumnName);
            return this;
        }

        public Builder labelPair(@Nullable PairSpec labelPair) {
            this.labelPair = labelPair;
            return this;
        }

        public Builder addAttribute(@NotNull String attributeName, @NotNull String fromColumnName, @NotNull String toColumnName) {
            this.attributePairs.add(new AttributePair(attributeName, fromColumnName, toColumnName));
            return this;
        }

        public Builder addAttribute(@NotNull AttributePair attributePair) {
            this.attributePairs.add(Objects.requireNonNull(attributePair, "attributePair"));
            return this;
        }

        public Builder addRelation(@NotNull String columnName) {
            this.relationColumns.add(Objects.requireNonNull(columnName, "columnName"));
            return this;
        }

        public GraphMappingSchema build() {
            if (idPair == null) {
                throw new IllegalStateException("idPair must be specified");
            }
            return new GraphMappingSchema(idPair, labelPair, attributePairs, relationColumns);
        }
    }
}
