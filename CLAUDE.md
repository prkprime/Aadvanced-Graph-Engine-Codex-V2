# CLAUDE.md — Developer Guidelines & Commands

Welcome to the **Advanced Columnar Graph Ingestion Engine**! This document outlines build, test, and style conventions to assist Claude (or any AI assistant/developer) working in this codebase.

---

## 🛠️ Common Commands

These are the primary Maven commands used to build, test, and run the engine:

- **Build Project**:
  ```bash
  mvn clean compile
  ```
- **Run All Tests**:
  ```bash
  mvn clean test
  ```
- **Run Specific Test Class**:
  ```bash
  mvn test -Dtest=GraphIngestionEngineTest
  ```
- **Run Specific Test Case**:
  ```bash
  mvn test -Dtest=GraphIngestionEngineTest#skipsCompletelyNullRowsDuringIngestion
  ```
- **Run Application**:
  ```bash
  mvn spring-boot:run
  ```

---

## 🏗️ Architecture & Key Components

This codebase implements a high-performance, columnar-based graph ingestion engine. It translates tabular source data (e.g., from a columnar raw data store) into dense, dictionary-encoded numerical columns, complete with sidecars and inverted indices for fast lookup.

- **`GraphMappingSchema`**: The unified, pair-based user-facing configuration that maps raw columns (`idPair`, `labelPair`, `attributePairs`, `relationColumns`) into node and edge structures. **Note: Legacy `MappingSpec`/`NodeSpec` intermediate layers have been fully eliminated.**
- **`GraphMappingSchemaValidator`**: Checks that all mapped columns exist in the raw source, verify duplicate configurations, and handles intra-node and cross-node disjoint constraint checks.
- **`GraphEngineContext`**: Manages all per-column contexts (`NodeSideContext` for FROM/TO nodes, `RelationColumnContext` for edge relations). Exposes shared dictionaries, integer stores, and deleted/tombstone tracking.
- **`GraphIngestionEngine`**: Ingests source row identifiers by dictionary encoding, storing numerical mappings, and updating RoaringBitmap-backed inverted indexes.
- **`ColumnContext`**: Holds the dense columnar structures (`BiDirectionalDictionary`, `IntegerColumnarStore`, `InvertedIndexColumn`, and raw data-cube column index) for one edge side variable.

---

## 🪦 Support for Single Vertices & Tombstones

- **Partial Row Ingestion**: If only one side of an edge is present (either `FROM_ID` or `TO_ID` is `null` natively), it is ingested as a single vertex.
- **Tombstones (`RoaringBitmap`)**:
  - `FROM_DELETED` contains the internal indices of ingested rows where the `FROM` vertex was null.
  - `TO_DELETED` contains the internal indices of ingested rows where the `TO` vertex was null.
- **Precomputed Column Read Masks**:
  - Mask bitmaps (`fromNullReadMask`, `toNullReadMask`) precompute which columnar columns are active for partial rows.
  - The hot ingestion path uses `getIntIterator()` directly from these bitmaps to bypass branching or range checks during columnar population.
- **Edge Retrieval & Vertex Dict**:
  - `getEdges()` conditionally returns `null` for deleted node identifiers and nullifies their relations.
  - `getVertexDictionary()` ignores tombstoned row indices and skips null keys during decoding.

---

## 🎨 Code Style & Rules

1. **Java Standards**: Target Java 21+ features.
2. **Boilerplate Reduction**: Extensively leverage **Lombok** (`@Getter`, `@RequiredArgsConstructor`, `@AccessLevel`) and **Record** types to keep classes clean and lightweight.
3. **Refactoring & DRY**: When creating or modifying source code, proactively identify opportunities to extract common logic into reusable methods or components. Prioritize clean, deduplicated code.
4. **Nullability Annotations**: Explicitly annotate signatures with JetBrains annotations:
   - `@NotNull` for non-nullable params, methods, fields.
   - `@Nullable` for optional values (e.g. `labelPair` mappings).
5. **Performance**: Avoid branching or dynamic object instantiation in the hot ingestion path. Prefer RoaringBitmap operations for fast, index-level matching.
6. **No Dead Fields / Code**: Ensure all unused fields, legacy intermediate specs, and bridging logic (e.g., legacy `toMappingSpec()`) remain removed.
7. **Documentation Integrity**: Maintain excellent Javadocs. Update comments and docstrings when changing code structure, but preserve unrelated existing comments intact.
8. **Target Directory Exclusion**: Do not consider the `target` directory and its subdirectories for any kind of context, processing, or literally anything.
