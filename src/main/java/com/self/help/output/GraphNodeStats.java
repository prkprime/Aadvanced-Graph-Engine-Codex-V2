package com.self.help.output;

/**
 * Aggregate statistics about vertices in a loaded graph.
 * <p>
 * These counts describe the node side of the graph model after ingestion,
 * independent of edge or relation metrics. They are intended for lightweight
 * API responses such as {@code /api/v1/graphs/{graphId}/stats}.
 */
public record GraphNodeStats(int outgoingEdgeCount, int incomingEdgeCount) {

    public int getOutgoingEdgeCount() {
        return outgoingEdgeCount;
    }

    public int getIncomingEdgeCount() {
        return incomingEdgeCount;
    }

    public int getTotalEdgeCount() {
        return outgoingEdgeCount + incomingEdgeCount;
    }
}
