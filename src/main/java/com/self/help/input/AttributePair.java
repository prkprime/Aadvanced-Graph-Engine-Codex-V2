package com.self.help.input;

import org.jetbrains.annotations.NotNull;
import java.util.Objects;

/**
 * Binds a semantic attribute name to a source and target column pair.
 */
public record AttributePair(@NotNull String attributeName, @NotNull PairSpec columnPair) {
    public AttributePair {
        Objects.requireNonNull(attributeName, "attributeName");
        Objects.requireNonNull(columnPair, "columnPair");
    }

    /**
     * Convenience constructor to build an AttributePair directly from column names.
     */
    public AttributePair(@NotNull String attributeName, @NotNull String fromColumnName, @NotNull String toColumnName) {
        this(attributeName, new PairSpec(fromColumnName, toColumnName));
    }
}
