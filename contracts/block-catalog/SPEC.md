# block.catalog@1

A finite, immutable snapshot of the blocks available in one game version, including each block's state-property
domains and default state. Written for WorldPrompt so an operation can validate a block identifier and fully normalize
its supplied properties before reaching the game registry.

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

Definitions use canonical identifiers (`[a-z0-9_.-]+:[a-z0-9/._-]+`), property names (`[a-z0-9_]+`), and nonempty
property values without whitespace, control characters, `[]`, comma, or equals. These generic tokens can represent finite
domains outside Minecraft's current lowercase state values. Property maps and block ids are exposed in sorted order; each
domain is sorted as well. A catalogue may be empty, but its version must be nonblank.

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `NoOpCatalogSource` | Captures an empty catalogue when definitions are available | C1 (`C1:known-blocks`) |
| `AcceptsBadDefinitionFactory` | Silently accepts a malformed definition | C4 (`C4:rejects-malformed`) |
| `MissingDefaultsFactory` | Omits a declared property's default during normalization | C2 (`C2:all-defaults`) |
| `IgnoresExplicitFactory` | Ignores an explicitly supplied property value | C3 (`C3:explicit-overrides`) |
| `AliasesInputsFactory` | Retains caller-owned maps and lists | C5 (`C5:deep-immutable`) |
| `MutableOutputsFactory` | Exposes a mutable catalogue map | C5 (`C5:deep-immutable`) |

Not covered (a runtime concern): when the game registry is captured, how its native names map into this snapshot, or
the consumer's serialized payload and size limits.

## Using it

Capture a native registry into a `BlockCatalog` and pass the snapshot to code that needs known block ids, allowed state
values, or a fully defaulted state. Call `normalize(id, explicitProperties)` before creating a native block state; it
rejects unknown ids, properties, and values instead of silently substituting another block or state.

## Running the harness

From the Mycelium root, `bash run-contracts.sh block-catalog` (or `.\run-contracts.ps1 block-catalog`), or
`./gradlew myceliumVerify -Pmycelium.contracts=block.catalog@1`. Expected: the reference clean, all six matrix entries
`CAUGHT`, exit code 0.
