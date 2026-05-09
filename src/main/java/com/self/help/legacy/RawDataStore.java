package com.self.help.legacy;

import lombok.Data;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Simple legacy-friendly columnar store for raw string data.
 * Values are appended row-by-row but stored as one list per source column, which
 * lets the graph engine read individual mapped columns without reconstructing
 * full rows during ingestion and projection.
 */
@Data
public class RawDataStore {
    private final @NotNull List<String> columnNames;
    int size;
    final Map<String, Integer> columnNameToIndexMap = new LinkedHashMap<>();
    final List<String>[] columns;

    /**
     * Creates an empty columnar raw store with the supplied column order.
     *
     * @param columnNames source column names in storage order
     */
    public RawDataStore(@NotNull List<String> columnNames) {
        columns = new List[columnNames.size()];
        for (int index = 0; index < columnNames.size(); index++) {
            columnNameToIndexMap.putIfAbsent(columnNames.get(index), columnNameToIndexMap.size());
            columns[index] = new ArrayList<String>();
        }
        this.columnNames = columnNames;
    }

    /**
     * Appends one row of raw string values across all columns.
     *
     * @param values row values in the same order as the configured columns
     * @return assigned row index
     * @throws IllegalArgumentException when the number of values does not match the number of mapped columns
     */
    public synchronized int ingestRow(@NotNull String[] values) {
        if (values.length != columnNameToIndexMap.size()) {
            throw new IllegalArgumentException();
        }
        for (int index = 0; index < values.length; index++) {
            columns[index].add(values[index]);
        }
        return size++;
    }

    /**
     * Returns a row reconstructed from the columnar storage.
     *
     * @param rowIndex row index to read
     * @return row values, or {@code null} when the row index is outside the stored range
     */
    public String[] getRow(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= size) {
            return null;
        }
        return Arrays.stream(columns).map(column -> column.get(rowIndex)).
                toList().
                toArray(new String[0]);
    }

    /**
     * Returns the zero-based position of a source column.
     *
     * @param columnName source column name
     * @return column index, or {@code -1} when the column is unknown
     */
    public int getColumnIndex(String columnName) {
        return columnNameToIndexMap.getOrDefault(columnName, -1);
    }

    /**
     * Reads one raw string value directly from the columnar store.
     *
     * @param rowId row index
     * @param columnIndex column index
     * @return stored string value
     */
    public String getString(int rowId, int columnIndex) {
        return columns[columnIndex].get(rowId);
    }
}
