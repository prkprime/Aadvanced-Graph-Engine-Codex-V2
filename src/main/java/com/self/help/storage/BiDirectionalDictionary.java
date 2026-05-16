package com.self.help.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains a stable two-way mapping between raw string values and dense
 * integer ids. The graph engine uses these ids as compact values for bitmap
 * indexes while still being able to hydrate ids back to strings when needed.
 */
public class BiDirectionalDictionary {
    private final Map<String, Integer> valueToId = new HashMap<>();
    private final List<String> idToValue = new ArrayList<>();

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
    public synchronized String getValue(int id) {
        return idToValue.get(id);
    }

    /**
     * Returns the number of unique values encoded by this dictionary.
     *
     * @return unique value count
     */
    public synchronized int size() {
        return idToValue.size();
    }

    /**
     * Looks up an existing dictionary id without mutating the dictionary.
     *
     * @param value value to look up
     * @return dictionary id, or {@code -1} when the value has not been encoded
     */
    public synchronized int getIdIfExists(String value) {
        return valueToId.getOrDefault(value, -1);
    }
}
