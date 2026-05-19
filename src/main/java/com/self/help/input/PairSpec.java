package com.self.help.input;

import org.jetbrains.annotations.NotNull;

/**
 *
 * @param fromColumnName
 * @param toColumnName
 */
public record PairSpec(@NotNull String fromColumnName, @NotNull String toColumnName) {
}
