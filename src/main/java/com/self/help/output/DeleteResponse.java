package com.self.help.output;

import java.io.Serializable;

/**
 * Standard payload response representing the status of a graph deletion request.
 */
public record DeleteResponse(
    boolean success,
    String message
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
