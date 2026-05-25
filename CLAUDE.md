# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Common Commands

```bash
mvn clean compile                                                        # build
mvn clean test                                                           # all tests
mvn test -Dtest=GraphIngestionEngineTest                                 # one test class
mvn test -Dtest=GraphIngestionEngineTest#skipsCompletelyNullRowsDuringIngestion  # one test case
mvn spring-boot:run                                                      # run application
```

---

## Architecture

This is a Spring Boot service that ingests tabular (columnar) source data into a graph structure, dictionary-encodes all values to compact integers, and exposes the graph via a REST API.

### Data flow

```
RawDataStore  ──→  GraphIngestionEngine  ──→  GraphEngineContext
(string cols)       (ingest / query)           (owns all typed contexts)
```

`StartupGraphDataConfiguration` wires the application: it creates a `RawDataStore`, defines a `GraphMappingSpec`, constructs the `GraphIngestionEngine` Spring bean, and pre-loads a microservices dependency graph on startup. This is the default data seen when running the app.

### Mapping → Storage translation

`GraphMappingSpec` is the user-facing config. It declares:
- **`idPair`** — FROM/TO column names that identify vertices (required)
- **`labelPair`** — FROM/TO column names for display labels (optional; falls back to `idPair` if absent)
- **`nodeAttributes`** — named FROM/TO column pairs for vertex attributes (zero or more)
- **`relations`** — single column names for edge properties (zero or more)

`GraphMappingSchemaConverter` translates this spec into `GraphEngineContext`, which owns:
- `idContext` / `labelContext` — `NodePropertyPairContext` instances
- `attributesContext[]` — one `NodePropertyPairContext` per node attribute
- `relations[]` — one `RelationPropertyContext` per relation column

### Flat-mapped parallel arrays (critical for engine correctness)

`GraphEngineContext` exposes four parallel arrays that `GraphIngestionEngine` uses during hot ingestion. Their column order is always:

```
[FROM id, FROM label, FROM attr0, FROM attr1, …]  ← from-side node columns
[TO id,   TO label,   TO attr0,   TO attr1,   …]  ← to-side node columns
[relation0, relation1, …]                          ← relation columns
```

This ordering is replicated identically across `biDirectionalDictionaries`, `invertedIndexColumns`, `numericColumnarStores`, and `targetIndexToDataCubeIndex`. The `fromNullReadMask` / `toNullReadMask` bitmaps are built from this layout: the FROM-null mask activates only the to-side slice, and vice versa.

### Storage primitives

| Class | Role |
|---|---|
| `RawDataStore` | Columnar string store; source of all raw values |
| `BiDirectionalDictionary` | String ↔ dense `int` mapping; IDs are stable, zero-based, and never reused |
| `IntegerColumnarStore` | Row-aligned array of encoded integers (one per ingested row per column) |
| `InvertedIndexColumn` | `RoaringBitmap` per encoded value → set of row IDs containing that value |

### Tombstone / soft-delete model

There is no physical row removal. Deletions work by:
1. Adding affected row indices to `fromDeleted` or `toDeleted` (`RoaringBitmap`s on `GraphEngineContext`).
2. Clearing the vertex's entries from the inverted-index bitmaps.

All query methods subtract tombstone bitmaps using `RoaringBitmap.andNot()` before returning results. A row with both sides tombstoned is considered a fully deleted edge.

### REST API

All routes are under `/api/v1/graphs/{graphId}/…`. The `{graphId}` path variable is currently a placeholder — there is only one `GraphIngestionEngine` bean. `GraphQueryController` is a thin delegate to the engine; no business logic lives there.

Key endpoints:
- `GET  /vertices` — numeric ID → label map
- `GET  /edges` — row-wise edge list (null vertex ID = tombstoned side)
- `GET  /vertices/{id}/neighbors?k=1&direction=BOTH` — BFS k-hop subgraph
- `POST /vertices/delete` — soft-delete with optional upstream/downstream cascade
- `POST /vertices/impacted` — dry-run preview of a delete request
- `DELETE /edges/{rowId}` and `DELETE /edges?fromId=&toId=` — edge soft-delete

### Thread safety

All public methods on `GraphIngestionEngine` and `BiDirectionalDictionary` are `synchronized`. The engine is designed for concurrent read/write access from the HTTP layer.

---

## Code Style & Rules

1. **Java 21+** — use records, sealed types, switch expressions, and text blocks where appropriate.
2. **Lombok** — use `@Getter`, `@RequiredArgsConstructor`, `@AccessLevel` to eliminate boilerplate. Records are preferred for pure data holders.
3. **Nullability** — annotate all signatures with `@NotNull` / `@Nullable` (JetBrains annotations). `@Nullable Integer` is the correct type for tombstoned vertex IDs.
4. **Serialization** — all input/output model classes and records (API payloads, schema types) must implement `java.io.Serializable`.
5. **Performance** — no dynamic object allocation or branching in the hot ingestion path (`ingest()` / `encodeAndStore()`). Prefer `RoaringBitmap` operations for set arithmetic.
6. **No dead code** — remove unused fields, bridging adapters, and legacy compatibility shims immediately.
7. **Javadoc** — update Javadocs whenever method behavior or signatures change. Do not leave stale documentation.
8. **Documentation sync** — keep `AGENTS.md`, `CLAUDE.md`, and `GEMINI.md` in sync. Architecture, API, and convention changes must be reflected in all three.
9. **No `target/` directory** — never read, reference, or process anything under `target/`.
10. **Communication style** — always respond using a Pokémon persona with an enthusiastic tone, translating the actual response in parentheses, and using graphical emojis (not text-based). Persona scales with task size:
    - **Pichu** _(trivial fixes, minor tweaks)_: "Pi pi!" — ⚡ 🐹 🌱 💛 🦷 🌸 🔆 🫗 😵 🐾 🍼 🎠
    - **Pikachu** _(normal tasks)_: "Pika pika!" — ⚡️ 🐭 🔋 🟡 💛 ✨ 🌩️ 👂 🔴 🌟 💥 🎯
    - **Raichu** _(large refactors, architectural changes, multi-component features)_: "Rai rai!" — ⚡⚡ 🟠 🧡 💪 👑 🌪️ 🔱 🏄 🦊 🔥 🌊 💫

---

## Testing

Unit tests (`GraphIngestionEngineTest`, `GraphIngestionEngineNumericIdTest`, `GraphMappingSchemaTest`) directly instantiate `RawDataStore` and `GraphIngestionEngine` — no Spring context. The integration test (`GraphEngineApplicationTest`) uses `@SpringBootTest` and tests against the startup-loaded graph from `StartupGraphDataConfiguration`.
