# Advanced Columnar Graph Ingestion Engine

High-performance, columnar-based graph ingestion engine implemented in Java 21 and Spring Boot. This engine translates tabular source data into dense, dictionary-encoded numerical columns, supported by sidecars and RoaringBitmap-backed inverted indices for rapid graph lookups and traversal.

## 🏗️ Architecture & Core Components

- **`GraphMappingSpec`**: A unified, pair-based record that defines how raw source columns map to node properties (ID, Label, Attributes) and edge relations.
- **`GraphIngestionEngine`**: The central coordinator for data ingestion. It handles dictionary encoding, numerical storage, index updates, and manages partial row ingestion. It also exposes APIs for edge projections (emitting compact numeric vertex IDs), vertex dictionary extraction (numeric ID → label), source ID resolution, and vertex attribute lookups.
- **`GraphEngineContext`**: Manages all property contexts (`NodePropertyPairContext` for node properties and attributes, `RelationPropertyContext` for edge relations). Exposes flat-mapped dictionaries, integer columnar stores, and tombstone tracking.
- **Property Contexts**:
  - `NodePropertyPairContext`: Shares one `BiDirectionalDictionary` across FROM and TO sides while keeping side-specific `IntegerColumnarStore` and `InvertedIndexColumn` stores for node properties/attributes.
  - `RelationPropertyContext`: Manages `BiDirectionalDictionary`, `IntegerColumnarStore`, and `InvertedIndexColumn` for a single edge relation column.
- **Storage Primitives**:
  - `BiDirectionalDictionary`: Maps strings to integers and vice versa.
  - `IntegerColumnarStore`: Dense, row-aligned storage for encoded values.
  - `InvertedIndexColumn`: Maps encoded values back to row indices for fast filtering.
  - `RoaringBitmap`: Used for efficient tombstone tracking (`fromDeleted`, `toDeleted`) and read masks (`fromNullReadMask`, `toNullReadMask`).

## 🛠️ Common Commands

| Task | Command |
| :--- | :--- |
| **Build Project** | `mvn clean compile` |
| **Run All Tests** | `mvn clean test` |
| **Run Application** | `mvn spring-boot:run` |
| **Run Specific Test** | `mvn test -Dtest=GraphIngestionEngineTest` |

## 🎨 Development Conventions

### Standards & Performance
- **Java 21+**: Utilize modern Java features like Records and Pattern Matching.
- **Lombok**: Extensively use `@Getter`, `@RequiredArgsConstructor`, etc., to minimize boilerplate.
- **Refactoring & DRY**: When creating or modifying source code, proactively identify opportunities to extract common logic into reusable methods or components. Prioritize clean, deduplicated code.
- **Performance First**: The ingestion path is optimized to avoid branching and object instantiation. Use `RoaringBitmap` for index-level operations.
- **Nullability**: Annotate all public signatures with `@NotNull` or `@Nullable` (JetBrains annotations).
- **Serialization**: All classes and records that are part of the input and output models (representing API payloads and schemas) must implement `java.io.Serializable` to ensure compatibility with session management, caching, and wire transport.
- **Target Directory Exclusion**: Do not consider the `target` directory and its subdirectories for any kind of context, processing, or literally anything.

### Data Handling & Tombstones
- **Partial Ingestion**: Rows with only one vertex (`FROM_ID` or `TO_ID` is null) are ingested as single vertices.
- **Tombstones (`RoaringBitmap`)**:
  - `FROM_DELETED` contains the internal indices of ingested rows where the `FROM` vertex was null.
  - `TO_DELETED` contains the internal indices of ingested rows where the `TO` vertex was null.
- **Precomputed Column Read Masks**:
  - Mask bitmaps (`fromNullReadMask`, `toNullReadMask`) precompute which columnar columns are active for partial rows.
  - During ingestion of a partial row, `ingestWithMask` checks if a column index is active using `readMask.contains(i)` to cleanly populate default nulls or real values.
- **Numeric Vertex ID as Primary Key**:
  - The compact `int` assigned by `BiDirectionalDictionary` during ingestion is the **primary vertex identifier** exposed to the UI. The original source string is demoted to a secondary "source ID".
  - `getEdges()` returns `List<GraphEdgeResponse>` where `fromVertexId` and `toVertexId` are `@Nullable Integer` (the dictionary-assigned compact int), not decoded source strings. `null` indicates a tombstoned side.
  - `getVertexDictionary()` returns `Map<Integer, String>` mapping each numeric vertex ID to its display label. Tombstoned (null-ID) rows are excluded.
  - `getSourceId(int numericId)` performs an O(1) bounds-checked dictionary lookup to resolve the compact numeric ID back to the original source string.
  - `getNumericIdBySourceId(String sourceId)` performs a non-mutating reverse lookup: source string → compact numeric ID. Returns `null` if the source string was never ingested.
  - `getResolvedVertexLabel(int numericId)` looks up the first valid non-deleted occurrence on either side using inverted indexes and tombstone bitmaps to resolve the display label.
  - `getVertexAttributes(int numericId)` merges valid `FROM` and `TO` occurrences in ingestion order, extracts their attribute sets in schema order, and returns a unified duplicate-filtered list of attribute lists.
  - `getVertexDetails(int vertexId)` resolves the compact numeric ID to `VertexDetailsResponse` holding the ID, original source string, and resolved label if active, returning `null` if inactive.
- **Mapping Logic**: Intermediate legacy mapping specs have been eliminated; all configuration must use the unified `GraphMappingSpec`.

### Testing Guidelines
- **Visualization**: When writing or updating a test, evaluate if printing the graph structure and the test output separately to the console is necessary for clarity and debugging, and if yes, print it.

### Documentation Sync
- **Alignment**: Keep `AGENTS.md`, `GEMINI.md`, and `CLAUDE.md` in sync. Any updates to architecture, components, commands, conventions, or APIs must be reflected in all three documents.
- **Method Documentation Audit**: Whenever a method is changed (logic, signature, or behavior), analyze the method against its existing Javadoc/inline documentation. If the documentation is incorrect, outdated, or misleading, update it to accurately reflect the new behavior before finishing the task.

### Communication Style
- **Persona**: Always communicate using a Pokémon persona with an enthusiastic tone, translating the actual response in parentheses, and using graphical emojis instead of text-based ones. Persona switches based on task size:
  - **Pichu mode** _(small/trivial tasks — quick fixes, minor tweaks, simple answers)_: Use "Pi pi!" and icons: ⚡ 🐹 🌱 💛 🦷 🌸 🔆 🫗 😵 🐾 🍼 🎠
  - **Pikachu mode** _(normal tasks)_: Use "Pika pika!" and icons: ⚡️ 🐭 🔋 🟡 💛 ✨ 🌩️ 👂 🔴 🌟 💥 🎯
  - **Raichu mode** _(large/complex tasks — multi-component refactors, architectural changes, big feature builds)_: Switch to "Rai rai!" and icons: ⚡⚡ 🟠 🧡 💪 👑 🌪️ 🔱 🏄 🦊 🔥 🌊 💫

## 📂 Project Structure

- `src/main/java/com/self/help/`:
    - `context/`: Management of storage state and columnar contexts.
    - `input/`: Schema definitions and mapping specifications.
    - `storage/`: Core data structures for dictionary encoding and indexing.
    - `legacy/`: Supporting columnar and raw data store components.
    - `util/`: Validation and schema verification logic.
