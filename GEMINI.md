# Advanced Columnar Graph Ingestion Engine

High-performance, columnar-based graph ingestion engine implemented in Java 21 and Spring Boot. This engine translates tabular source data into dense, dictionary-encoded numerical columns, supported by sidecars and RoaringBitmap-backed inverted indices for rapid graph lookups and traversal.

## 🏗️ Architecture & Core Components

- **`GraphMappingSpec`**: A unified, pair-based record that defines how raw source columns map to node properties (ID, Label, Attributes) and edge relations.
- **`GraphIngestionEngine`**: The central coordinator for data ingestion. It handles dictionary encoding, numerical storage, and index updates while managing partial row ingestion (single vertices).
- **`GraphEngineContext`**: Maintains the lifecycle and flattened state of all storage components, including dictionaries, columnar stores, and tombstone bitmaps.
- **Storage Primitives**:
  - `BiDirectionalDictionary`: Maps strings to integers and vice versa.
  - `IntegerColumnarStore`: Dense, row-aligned storage for encoded values.
  - `InvertedIndexColumn`: Maps encoded values back to row indices for fast filtering.
  - `RoaringBitmap`: Used for efficient tombstone tracking (`fromDeleted`, `toDeleted`) and read masks.

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
- **Target Directory Exclusion**: Do not consider the `target` directory and its subdirectories for any kind of context, processing, or literally anything.

### Data Handling
- **Partial Ingestion**: Rows with only one vertex (`FROM_ID` or `TO_ID` is null) are ingested as single vertices.
- **Tombstones**: RoaringBitmaps (`FROM_DELETED`, `TO_DELETED`) track indices of partial rows to ensure correct decoding during graph reconstruction.
- **Mapping Logic**: Intermediate legacy mapping specs have been eliminated; all configuration must use the unified `GraphMappingSpec`.

## 📂 Project Structure

- `src/main/java/com/self/help/`:
    - `context/`: Management of storage state and columnar contexts.
    - `input/`: Schema definitions and mapping specifications.
    - `storage/`: Core data structures for dictionary encoding and indexing.
    - `legacy/`: Supporting columnar and raw data store components.
    - `util/`: Validation and schema verification logic.
