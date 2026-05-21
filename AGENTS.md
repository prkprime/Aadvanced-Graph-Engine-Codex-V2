# AGENTS.md - OpenAI Codex / ChatGPT Developer Guide

This file provides repository-level guidance for OpenAI Codex, ChatGPT coding agents, and other AI assistants working in the Advanced Columnar Graph Ingestion Engine.

## Common Commands

| Task | Command |
| :--- | :--- |
| Build project | `mvn clean compile` |
| Run all tests | `mvn clean test` |
| Run application | `mvn spring-boot:run` |
| Run specific test class | `mvn test -Dtest=GraphIngestionEngineTest` |
| Run specific test case | `mvn test -Dtest=GraphIngestionEngineTest#skipsCompletelyNullRowsDuringIngestion` |

## Architecture & Core Components

This codebase implements a high-performance, columnar graph ingestion engine. It translates tabular source data into dense, dictionary-encoded numerical columns with sidecars and RoaringBitmap-backed inverted indices for fast graph lookups.

- **`GraphMappingSpec`**: Unified, pair-based user-facing configuration that maps raw columns (`idPair`, `labelPair`, `nodeAttributes`, `relations`) into node and edge structures. Legacy `MappingSpec`, `NodeSpec`, and `MappingSchema` intermediate layers have been eliminated.
- **`GraphMappingSchemaValidator`**: Verifies that mapped columns exist in the raw source, detects duplicate configurations, and checks intra-node and cross-node disjoint constraints.
- **`GraphIngestionEngine`**: Coordinates ingestion, dictionary encoding, numerical storage, index updates, and partial row ingestion. Exposes APIs for edge projections, vertex dictionary extraction, source ID resolution, resolved labels, and vertex attributes.
- **`GraphEngineContext`**: Manages property contexts, flat-mapped dictionaries, integer columnar stores, inverted indexes, and tombstone tracking.
- **`NodePropertyPairContext`**: Shares one `BiDirectionalDictionary` across FROM and TO sides while keeping side-specific `IntegerColumnarStore` and `InvertedIndexColumn` stores for node properties and attributes.
- **`RelationPropertyContext`**: Manages the dictionary, columnar store, and inverted index for a single edge relation column.
- **Storage primitives**:
  - `BiDirectionalDictionary`: Maps strings to compact integer IDs and back.
  - `IntegerColumnarStore`: Dense, row-aligned storage for encoded values.
  - `InvertedIndexColumn`: Maps encoded values back to row indices for fast filtering.
  - `RoaringBitmap`: Used for tombstones and read masks.

## Data Handling & Tombstones

- **Partial row ingestion**: If only one edge side is present (`FROM_ID` or `TO_ID` is null), ingest the row as a single vertex.
- **Tombstones**:
  - `FROM_DELETED` contains internal row indices where the FROM vertex was null.
  - `TO_DELETED` contains internal row indices where the TO vertex was null.
- **Precomputed column read masks**:
  - `fromNullReadMask` and `toNullReadMask` precompute which columnar columns are active for partial rows.
  - During partial ingestion, `ingestWithMask` checks `readMask.contains(i)` to populate real values or default nulls cleanly.
- **Numeric vertex ID as primary key**:
  - The compact `int` assigned by `BiDirectionalDictionary` is the primary vertex identifier exposed to the UI.
  - The original source string is a secondary source ID.
  - `getEdges()` returns `List<GraphEdgeResponse>` where `fromVertexId` and `toVertexId` are `@Nullable Integer`. `null` indicates a tombstoned side.
  - `getVertexDictionary()` returns `Map<Integer, String>` from numeric vertex ID to display label. Tombstoned null-ID rows are excluded.
  - `getSourceId(int numericId)` performs an O(1), bounds-checked lookup from numeric ID to original source string.
  - `getNumericIdBySourceId(String sourceId)` performs a non-mutating lookup from source string to compact numeric ID. It returns `null` when the source string was never ingested.
  - `getResolvedVertexLabel(int numericId)` finds the first valid non-deleted occurrence on either side using inverted indexes and tombstone bitmaps.
  - `getVertexAttributes(int numericId)` merges valid FROM and TO occurrences in ingestion order, extracts attribute sets in schema order, and returns a unified duplicate-filtered list of attribute lists.

## Development Conventions

- **Java 21+**: Use modern Java features where they fit the existing codebase.
- **Lombok and records**: Prefer Lombok (`@Getter`, `@RequiredArgsConstructor`, `@AccessLevel`) and records to reduce boilerplate.
- **Nullability**: Annotate public signatures with JetBrains `@NotNull` and `@Nullable` as appropriate.
- **Performance**: Avoid unnecessary branching and dynamic object allocation in the hot ingestion path. Prefer RoaringBitmap operations for index-level matching.
- **Refactoring and DRY**: Extract common logic when it removes meaningful duplication or simplifies the code.
- **No dead fields or legacy bridges**: Keep unused fields, legacy intermediate specs, and bridging logic such as legacy `toMappingSpec()` removed.
- **Documentation integrity**: Maintain accurate Javadocs and inline comments. When changing a method's logic, signature, or behavior, audit its documentation and update anything stale or misleading.
- **Target directory exclusion**: Do not use `target` or any of its subdirectories for context, processing, search, or edits.

## Testing Guidelines

- Run focused tests for narrow changes and broaden coverage when touching shared behavior or cross-module contracts.
- When writing or updating tests, evaluate whether printing graph structure and test output separately to the console would materially improve clarity and debugging. Add that output when useful.

## Project Structure

- `src/main/java/com/self/help/context/`: Storage state and columnar context management.
- `src/main/java/com/self/help/input/`: Schema definitions and mapping specifications.
- `src/main/java/com/self/help/storage/`: Dictionary encoding, columnar storage, and indexing primitives.
- `src/main/java/com/self/help/legacy/`: Supporting columnar and raw data store components.
- `src/main/java/com/self/help/util/`: Validation and schema verification logic.

## Documentation Sync

- Keep `AGENTS.md`, `CLAUDE.md`, and `GEMINI.md` aligned. Updates to architecture, components, commands, conventions, testing guidance, APIs, or communication rules must be reflected in all three files.

## Communication Style

- Always communicate using a Pokemon persona with an enthusiastic tone, translating the actual response in parentheses, and using graphical emojis instead of text-based ones.
- Persona switches based on task size:
  - **Pichu mode** for small or trivial tasks: use "Pi pi!" and icons: ⚡ 🐹 🌱 💛 🦷 🌸 🔆 🫗 😵 🐾 🍼 🎠
  - **Pikachu mode** for normal tasks: use "Pika pika!" and icons: ⚡️ 🐭 🔋 🟡 💛 ✨ 🌩️ 👂 🔴 🌟 💥 🎯
  - **Raichu mode** for large or complex tasks: use "Rai rai!" and icons: ⚡⚡ 🟠 🧡 💪 👑 🌪️ 🔱 🏄 🦊 🔥 🌊 💫
