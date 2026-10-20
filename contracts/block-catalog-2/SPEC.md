# block.catalog@2

A finite, immutable snapshot of the blocks available in one game version, including each block's state-property
domains and default state. Written for WorldPrompt so an operation can validate a block identifier and fully normalize
its supplied properties before reaching the game registry.

| | |
|---|---|
| Contract id | `block.catalog@2` (implementation namespace `mycelium.block_catalog.v2`) |
| Supersedes | `block.catalog@1` (same types and methods; `@1` sorted every property domain, `@2` keeps the capture's order) |
| Promoted from | A consumer that had to show a block's real property values: WorldPrompt, October 2026 |

## The problem

`@1` sorted each property domain alphabetically. That is stable, but it destroys the only ordering a native registry
has and the only one a reader can interpret:

- A direction enum is declared `north, south, east, west`. Sorted, it reads `east, north, south, west`, so the
  domain no longer resembles the game's own vocabulary.
- A numeric property is declared `0 … 15`. Sorted as text it reads `0, 1, 10, 11, 12, 13, 14, 15, 2, 3, …`, which
  states a false adjacency: `1` next to `10`, and `15` before `2`.

A consumer cannot recover the capture's order from a sorted domain, because the sort is lossy — no rule maps
`0, 1, 10, 11, …` back to `0 … 15` without knowing the values are integers, and that heuristic then destroys the
nonnumeric enum orders. The order has to survive capture, so `@2` makes it a law.

## Model

- `BlockCatalog(version, blocks)`: a validated catalogue snapshot. `blocks()` is keyed by canonical block id in sorted
  order; each `Entry` contains the property domains and exact default values.
- `BlockCatalog.Entry(properties, defaults)`: property names to their finite values and one default value per property.
- `BlockCatalog.normalize(id, explicitProperties)`: the defaults for a known block with explicit values applied; an
  unknown block, property, or value is rejected.
- `CatalogSource.capture()`: the native seam that captures a game registry as one catalogue snapshot.

## Laws

| Law | Statement |
|---|---|
| C1 | Membership is exact: only ids in this snapshot are known, including their namespace; unknown ids reject. |
| C2 | Every known block's normalized empty state contains all and only its declared defaults. |
| C3 | Explicit properties override defaults; unknown properties and values outside the declared domain reject. |
| C4 | Construction rejects malformed definitions up front: blank version, noncanonical id or property, empty or duplicate domain, invalid value, or defaults that do not exactly match the properties and domains. |
| C5 | Construction deep-copies inputs and every exposed map and list is immutable. |
| C6 | Every property domain is exposed in the order the capture offered it, for every value shape, with no reordering. |

Definitions use canonical identifiers (`[a-z0-9_.-]+:[a-z0-9/._-]+`), property names (`[a-z0-9_]+`), and nonempty
property values without whitespace, control characters, `[]`, comma, or equals. These generic tokens can represent finite
domains outside Minecraft's current lowercase state values. A catalogue may be empty, but its version must be nonblank.

Block ids and property names are exposed in sorted order, so iterating a snapshot is deterministic whatever order the
capture walked its registry in. Only a domain's values carry the capture's order, because only they carry meaning that
sorting destroys (C6). A duplicate value is still rejected (C4), so preserving order never admits a repeat.

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `NoOpCatalogSource` | Captures an empty catalogue when definitions are available | C1 (`C1:known-blocks`) |
| `AcceptsBadDefinitionFactory` | Silently accepts a malformed definition | C4 (`C4:rejects-malformed`) |
| `MissingDefaultsFactory` | Omits a declared property's default during normalization | C2 (`C2:all-defaults`) |
| `IgnoresExplicitFactory` | Ignores an explicitly supplied property value | C3 (`C3:explicit-overrides`) |
| `AliasesInputsFactory` | Retains caller-owned maps and lists | C5 (`C5:deep-immutable`) |
| `MutableOutputsFactory` | Exposes a mutable catalogue map | C5 (`C5:deep-immutable`) |
| `SortsDomainsFactory` | Sorts every domain alphabetically, as `@1` did | C6 (`C6:capture-order`) |

Not covered (a runtime concern): when the game registry is captured, how its native names map into this snapshot, or
the consumer's serialized payload and size limits.

## Using it

Capture a native registry into a `BlockCatalog` and pass the snapshot to code that needs known block ids, allowed state
values, or a fully defaulted state. Call `normalize(id, explicitProperties)` before creating a native block state; it
rejects unknown ids, properties, and values instead of silently substituting another block or state.

A capture must offer each domain in the game's own order; iterating the native property's possible values gives that
order directly. Do not sort on the way in, and do not sort a domain on the way out — a consumer that renders the domain
for a reader, or indexes into it, is relying on C6. Anything downstream that reorders values (a serializer, a wire
format, a cache) has to preserve list order too, or the guarantee stops at that boundary.

## Versioning

`@2` is a new major in its own directory (`contracts/block-catalog-2/`, package `mycelium.block_catalog.v2`), so an
`@1` consumer's own code and its sorted domains are untouched. The types, methods and labels `C1`–`C5` carry over
unchanged; `C1` asserts membership only, since `C6` now owns domain order. A build imports one of the two, never both.

The `block-catalog` bridge moved with the port, so `@1` no longer has a native half: it keeps its model, laws and
harness, but a mod that wants the native registry capture references that bridge and gets `@2`. An `@1` consumer that
needs a native capture writes its own `CatalogSource`, or moves to `@2`.

## Running the harness

From the Mycelium root, `bash run-contracts.sh block-catalog-2` (or `.\run-contracts.ps1 block-catalog-2`), or
`./gradlew myceliumVerify -Pmycelium.contracts=block.catalog@2`. Expected: the reference clean, all seven matrix entries
`CAUGHT`, exit code 0.
