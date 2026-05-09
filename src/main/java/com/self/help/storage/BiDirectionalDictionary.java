package com.self.help.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains a stable two-way mapping between raw string values and dense
 * integer ids. The graph engine uses these ids as compact values for bitmap
 * indexes while still being able to hydrate ids back to strings when needed.
 */
public class BiDirectionalDictionary {
    // String to ID for Ingestion
    private Map<String, Integer> valueToId = new ConcurrentHashMap<>();
    // ID to String for UI Hydration (Index is the ID)
    private List<String> idToValue = new ArrayList<>();

    /**
     * Returns the dictionary id for a value, creating a new id when the value
     * has not been seen before.
     *
     * @param value value to encode
     * @return stable integer id for the value
     */
    public synchronized int getOrEncode(String value) {
        return valueToId.computeIfAbsent(value, k -> {
            int id = idToValue.size();
            idToValue.add(k);
            return id;
        });
    }

    /**
     * Resolves a dictionary id back to its original value.
     *
     * @param id dictionary id
     * @return original value stored for the id
     * @throws IndexOutOfBoundsException when the id is outside the dictionary range
     */
    public String getValue(int id) {
        return idToValue.get(id);
    }

    /**
     * Returns the number of unique values encoded by this dictionary.
     *
     * @return unique value count
     */
    public int size() {
        return idToValue.size();
    }

    /**
     * Looks up an existing dictionary id without mutating the dictionary.
     *
     * @param name value to look up
     * @return dictionary id, or {@code -1} when the value has not been encoded
     */
    public int getIdIfExists(String name) {
        return valueToId.getOrDefault(name, -1);
    }
}
