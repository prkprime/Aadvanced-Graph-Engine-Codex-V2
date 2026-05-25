package com.self.help.enums;

/**
 * Directional filter for graph traversals and neighborhood expansions.
 */
public enum TraversalDirection {
    /**
     * Follow edges in the directed forward direction (from -> to).
     */
    OUTGOING,

    /**
     * Follow edges in the directed backward direction (to -> from).
     */
    INCOMING,

    /**
     * Follow edges in both directions, treating the graph as undirected.
     */
    BOTH
}
