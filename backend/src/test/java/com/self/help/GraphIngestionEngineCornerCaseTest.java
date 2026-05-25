package com.self.help;

import com.self.help.input.GraphMappingSpec;
import com.self.help.input.VertexDeleteRequest;
import com.self.help.legacy.RawDataStore;
import com.self.help.output.GraphEdgeResponse;
import com.self.help.output.GraphNodeStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corner-case tests derived from observed runtime behaviour: stats accuracy after
 * neighbour deletion, cascade boundary conditions, k-hop tombstone traversal, and
 * deleteEdge tombstone semantics.
 */
class GraphIngestionEngineCornerCaseTest {

	// ---------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------

	private static GraphIngestionEngine twoNodeEngine(String fromId, String toId) {
		RawDataStore store = new RawDataStore(List.of("from", "to"));
		store.ingestRow(new String[] { fromId, toId });
		GraphIngestionEngine engine = new GraphIngestionEngine(store,
				GraphMappingSpec.builder().idPair("from", "to").build());
		engine.ingest(0);
		return engine;
	}

	private static int id(GraphIngestionEngine engine, String sourceId) {
		Integer id = engine.getNumericIdBySourceId(sourceId);
		assertNotNull(id, "sourceId not found: " + sourceId);
		return id;
	}

	// ---------------------------------------------------------------------------
	// 1. Outgoing count decreases when the TO-side neighbour is deleted
	// ---------------------------------------------------------------------------

	@Test
	void outgoingStatDecreaseAfterNeighbourDeletion() {
		// A→B. Delete B. A's outgoing must drop to 0 — the row is dead because its
		// TO side is tombstoned, so activeCount must subtract toDeleted as well.
		GraphIngestionEngine engine = twoNodeEngine("A", "B");
		int aId = id(engine, "A");
		int bId = id(engine, "B");

		assertEquals(1, engine.getVertexStats(aId).outgoingEdgeCount());

		engine.deleteVertex(new VertexDeleteRequest(bId, false, false));

		GraphNodeStats stats = engine.getVertexStats(aId);
		assertNotNull(stats);
		assertEquals(0, stats.outgoingEdgeCount(), "dead TO-side row must not count as outgoing");
	}

	// ---------------------------------------------------------------------------
	// 2. Incoming count decreases when the FROM-side neighbour is deleted
	// ---------------------------------------------------------------------------

	@Test
	void incomingStatDecreaseAfterNeighbourDeletion() {
		// A→B. Delete A. B's incoming must drop to 0 — the row is dead because its
		// FROM side is tombstoned, so activeCount must subtract fromDeleted as well.
		GraphIngestionEngine engine = twoNodeEngine("A", "B");
		int aId = id(engine, "A");
		int bId = id(engine, "B");

		assertEquals(1, engine.getVertexStats(bId).incomingEdgeCount());

		engine.deleteVertex(new VertexDeleteRequest(aId, false, false));

		GraphNodeStats stats = engine.getVertexStats(bId);
		assertNotNull(stats);
		assertEquals(0, stats.incomingEdgeCount(), "dead FROM-side row must not count as incoming");
	}

	// ---------------------------------------------------------------------------
	// 3. Cascade downstream blocked when candidate has a second live incoming edge
	// ---------------------------------------------------------------------------

	@Test
	void cascadeDownstreamBlockedBySecondIncomingEdge() {
		// A→Y, B→Y. Delete A with cascade downstream.
		// allIncomingFromDeleted(Y, {A}) is false because B→Y is still active.
		// Only A must be deleted; Y survives.
		RawDataStore store = new RawDataStore(List.of("from", "to"));
		store.ingestRow(new String[] { "A", "Y" }); // row 0
		store.ingestRow(new String[] { "B", "Y" }); // row 1
		GraphIngestionEngine engine = new GraphIngestionEngine(store,
				GraphMappingSpec.builder().idPair("from", "to").build());
		engine.ingest(0);
		engine.ingest(1);

		int aId = id(engine, "A");
		int yId = id(engine, "Y");

		boolean deleted = engine.deleteVertex(new VertexDeleteRequest(aId, true, false));

		assertTrue(deleted);
		assertNull(engine.getVertexDetails(aId), "A must be deleted");
		assertNotNull(engine.getVertexDetails(yId), "Y must survive — B→Y is still active");
	}

	// ---------------------------------------------------------------------------
	// 4. Cascade upstream blocked when candidate has a second live outgoing edge
	// ---------------------------------------------------------------------------

	@Test
	void cascadeUpstreamBlockedBySecondOutgoingEdge() {
		// W→Y, W→Z. Delete Y with cascade upstream.
		// allOutgoingToDeleted(W, {Y}) is false because W→Z is still active.
		// Only Y must be deleted; W survives.
		RawDataStore store = new RawDataStore(List.of("from", "to"));
		store.ingestRow(new String[] { "W", "Y" }); // row 0
		store.ingestRow(new String[] { "W", "Z" }); // row 1
		GraphIngestionEngine engine = new GraphIngestionEngine(store,
				GraphMappingSpec.builder().idPair("from", "to").build());
		engine.ingest(0);
		engine.ingest(1);

		int yId = id(engine, "Y");
		int wId = id(engine, "W");

		engine.deleteVertex(new VertexDeleteRequest(yId, false, true));

		assertNull(engine.getVertexDetails(yId), "Y must be deleted");
		assertNotNull(engine.getVertexDetails(wId), "W must survive — W→Z is still active");
	}

	// ---------------------------------------------------------------------------
	// 5. k-hop traversal does not cross tombstoned mid-path vertices
	// ---------------------------------------------------------------------------

	@Test
	void kHopSkipsTombstonedMidPathVertex() {
		// A→B→C. Delete B. k=2 outgoing from A must not reach C.
		RawDataStore store = new RawDataStore(List.of("from", "to"));
		store.ingestRow(new String[] { "A", "B" }); // row 0
		store.ingestRow(new String[] { "B", "C" }); // row 1
		GraphIngestionEngine engine = new GraphIngestionEngine(store,
				GraphMappingSpec.builder().idPair("from", "to").build());
		engine.ingest(0);
		engine.ingest(1);

		int aId = id(engine, "A");
		int bId = id(engine, "B");
		int cId = id(engine, "C");

		engine.deleteVertex(new VertexDeleteRequest(bId, false, false));

		Map<Integer, String> neighbors = engine.getKNeighbors(aId, 2, com.self.help.enums.TraversalDirection.OUTGOING)
			.vertices();

		assertFalse(neighbors.containsKey(bId), "deleted B must not appear in k-hop");
		assertFalse(neighbors.containsKey(cId), "C must not be reached through deleted B");
	}

	// ---------------------------------------------------------------------------
	// 6. deleteEdge tombstones both sides of the row
	// ---------------------------------------------------------------------------

	@Test
	void deleteEdgeTombstonesBothSides() {
		// A→B. deleteEdge(row 0). getEdges() row 0 must have fromVertexId=null AND
		// toVertexId=null — both bitmaps updated, both inverted indexes cleared.
		GraphIngestionEngine engine = twoNodeEngine("A", "B");

		boolean deleted = engine.deleteEdge(0);
		assertTrue(deleted);

		List<GraphEdgeResponse> edges = engine.getEdges();
		assertEquals(1, edges.size());
		assertNull(edges.get(0).fromVertexId(), "FROM side must be tombstoned after deleteEdge");
		assertNull(edges.get(0).toVertexId(), "TO side must be tombstoned after deleteEdge");
	}

	// ---------------------------------------------------------------------------
	// 7. Deleted vertex returns null from getVertexStats; double-delete returns false
	// ---------------------------------------------------------------------------

	@Test
	void deletedVertexHasNullStatsAndDoubleDeleteReturnsFalse() {
		GraphIngestionEngine engine = twoNodeEngine("A", "B");
		int aId = id(engine, "A");

		assertNotNull(engine.getVertexStats(aId));

		boolean first = engine.deleteVertex(new VertexDeleteRequest(aId, false, false));
		assertTrue(first, "first delete must succeed");

		assertNull(engine.getVertexStats(aId), "stats for a deleted vertex must be null");

		boolean second = engine.deleteVertex(new VertexDeleteRequest(aId, false, false));
		assertFalse(second, "second delete must return false");
	}

}
