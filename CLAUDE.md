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

- **`GraphMappingSpec`**: The unified, pair-based user-facing configuration that maps raw columns (`idPair`, `labelPair`, `nodeAttributes`, `relations`) into node and edge structures. **Note: Legacy `MappingSpec`/`NodeSpec`/`MappingSchema` intermediate layers have been fully eliminated.**
- **`GraphMappingSchemaValidator`**: Checks that all mapped columns exist in the raw source, verifies duplicate configurations, and handles intra-node and cross-node disjoint constraint checks.
- **`GraphEngineContext`**: Manages all property contexts (`NodePropertyPairContext` for node properties and attributes, `RelationPropertyContext` for edge relations). Exposes flat-mapped dictionaries, integer columnar stores, and deleted/tombstone tracking.
- **`GraphIngestionEngine`**: Ingests source row identifiers by dictionary encoding, storing numerical mappings, and updating RoaringBitmap-backed inverted indexes.
- **`NodePropertyPairContext`**: Shares one `BiDirectionalDictionary` across both FROM and TO sides while keeping side-specific `IntegerColumnarStore` and `InvertedIndexColumn` stores for node properties/attributes.
- **`RelationPropertyContext`**: Manages `BiDirectionalDictionary`, `IntegerColumnarStore`, and `InvertedIndexColumn` for a single edge relation column.

---

## 🪦 Support for Single Vertices & Tombstones

- **Partial Row Ingestion**: If only one side of an edge is present (either `FROM_ID` or `TO_ID` is `null` natively), it is ingested as a single vertex.
- **Tombstones (`RoaringBitmap`)**:
  - `FROM_DELETED` contains the internal indices of ingested rows where the `FROM` vertex was null.
  - `TO_DELETED` contains the internal indices of ingested rows where the `TO` vertex was null.
- **Precomputed Column Read Masks**:
  - Mask bitmaps (`fromNullReadMask`, `toNullReadMask`) precompute which columnar columns are active for partial rows.
  - During ingestion of a partial row, `ingestWithMask` checks if a column index is active using `readMask.contains(i)` to cleanly populate default nulls or real values.
- **Edge Retrieval & Vertex Dict**:
  - `getEdges()` conditionally returns `null` for deleted node identifiers and nullifies their relations.
  - `getVertexDictionary()` ignores tombstoned row indices and skips null keys during decoding.
  - `getResolvedVertexLabel(int numericId)` looks up the first valid non-deleted occurrence on either side using inverted indexes and tombstone bitmaps to resolve the display label.
  - `getResolvedVertexId(int numericId)` performs a fast O(1) bounds-checked dictionary lookup to resolve the original string ID.
  - `getVertexAttributes(int numericId)` merges valid `FROM` and `TO` occurrences in ingestion order, extracts their attribute sets in schema order, and returns a unified duplicate-filtered list of attribute lists.

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
8. **Method Documentation Audit**: Whenever a method is changed (logic, signature, or behavior), analyze the method against its existing Javadoc/inline documentation. If the documentation is incorrect, outdated, or misleading, update it to accurately reflect the new behavior before finishing the task.
9. **Target Directory Exclusion**: Do not consider the `target` directory and its subdirectories for any kind of context, processing, or literally anything.
10. **Documentation Sync**: Keep both `CLAUDE.md` and `GEMINI.md` in sync. Any updates to architecture, components, commands, conventions, or APIs must be reflected in both documents.
11. **Communication Style**: Always communicate using a Pokémon persona with an enthusiastic tone, translating the actual response in parentheses, and using graphical emojis instead of text-based ones. Persona switches based on task size:
   - **Pichu mode** _(small/trivial tasks — quick fixes, minor tweaks, simple answers)_: Use "Pi pi!" and icons: ⚡ 🐹 🌱 💛 🦷 🌸 🔆 🫗 😵 🐾 🍼 🎠
   - **Pikachu mode** _(normal tasks)_: Use "Pika pika!" and icons: ⚡️ 🐭 🔋 🟡 💛 ✨ 🌩️ 👂 🔴 🌟 💥 🎯
   - **Raichu mode** _(large/complex tasks — multi-component refactors, architectural changes, big feature builds)_: Switch to "Rai rai!" and icons: ⚡⚡ 🟠 🧡 💪 👑 🌪️ 🔱 🏄 🦊 🔥 🌊 💫
