# item.component@1

A deterministic, bidirectional translation and carrier model bridging modern Minecraft Data Components
(1.20.5+) and legacy item NBT compounds (pre-1.20.5, specifically 1.20.1). Written for Cartomancy and any
Mycelium mod bridging the Data Component era divide without compromising pure Java 21 flyweight designs.

## 1. Problem & Architecture

In Minecraft 1.20.5+, item state is an immutable map of strongly typed record components (`DataComponentMap`).
In Minecraft 1.20.1, item state is a mutable string-keyed tree of untyped NBT (`CompoundTag`).

`item.component@1` establishes a pure, mathematical carrier model and translation grammar:
- **Zero Minecraft imports:** Operates on an immutable, pure sealed algebraic data type (`TagNode`) representing NBT AST.
- **Fenced Namespace:** Fences all translated component payloads into a dedicated namespace (`"mycelium$c"`), preventing pollution or collision with root vanilla tags (`Damage`, `Enchantments`, `display`).
- **Default Elision:** Omits values identical to registered defaults, guaranteeing clean item stacks with zero tag overhead on 1.20.1 when un-mutated.
- **Foreign Tag Preservation:** Preserves unmapped or future-version tags under the fenced namespace across round-trip serialization.
- **Negative Patches:** Explicitly tracks and preserves component removals (`!removed`) across serialization boundaries.
- **Anti-Data Loss Quarantine:** Safely falls back to default component values when reading malformed nodes while quarantining the raw tag, ensuring re-saving never permanently destroys user data.

## 2. Model

- `ComponentId(namespace, path)`: A validated identifier token (e.g. `example:card_instance`).
- `TagNode`: Pure algebraic data type for hierarchical NBT data:
  - Primitives: `TagByte`, `TagShort`, `TagInt`, `TagLong`, `TagFloat`, `TagDouble`, `TagString`
  - Arrays: `TagByteArray`, `TagIntArray`, `TagLongArray`
  - Collections: `TagList`, `TagCompound`
- `ComponentCodec<T>`: Pure serializer (`encode: T -> TagNode`, `decode: TagNode -> T throws CodecException`).
- `ComponentDefinition<T>(id, codec, defaultValue)`: Declares a typed component and its default value.
- `ComponentRegistry`: Catalog of registered component definitions.
- `CarrierSnapshot`: Immutable snapshot of an item's carrier state (typed components, negative patch removals, and preserved foreign/quarantined tags). Supports $O(N)$ batch construction via `CarrierSnapshot.builder()`.
- `RosettaTranslator`: Pure bidirectional translator interface (`RosettaTranslator.standard()` provides the reference implementation `StandardRosettaTranslator` in `model/`):
  - `toLegacyNbt(CarrierSnapshot, ComponentRegistry) -> TagCompound`
  - `fromLegacyNbt(TagCompound, ComponentRegistry) -> CarrierSnapshot`

## 3. Laws

| Law | Statement |
|---|---|
| T1 | **Round-Trip Isomorphism:** For any registered component value and snapshot, $\text{fromLegacyNbt}(\text{toLegacyNbt}(S)) \equiv S$ preserving all values, removals, and keys. |
| T2 | **Default Elision:** Components whose value equals `defaultValue` are omitted from NBT. If all components are defaults and no unmapped tags or removals exist, the fenced compound is omitted entirely. |
| T3 | **Namespace Fencing:** All component data in legacy NBT resides strictly inside `"mycelium$c"`. Root NBT contains zero component keys. |
| T4 | **Foreign Tag Preservation:** Tags under `"mycelium$c"` not present in `ComponentRegistry` survive serialization round-trip unmodified. |
| T5 | **Diagnostic Rejection & Anti-Data Loss Quarantine:** Malformed tags (type mismatch, invalid syntax) yield a structured fallback to defaults at domain level, while quarantining the raw tag under unmapped tags so that re-saving preserves raw data without world loss. |
| T6 | **Deterministic Key Ordering:** Serialized legacy NBT entries under `"mycelium$c"` are emitted in lexicographical key order on every platform, guaranteeing byte-reproducible serialization. |

## 4. Rejection Matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `NoOpTranslator` | Returns an empty snapshot on decode and empty tag on encode | T1 (`T1:round-trip-isomorphism`) |
| `DefaultWritingTranslator` | Serializes default component values into NBT instead of eliding them | T2 (`T2:default-elision`) |
| `RootPollutingTranslator` | Writes component keys directly to root NBT rather than under `"mycelium$c"` | T3 (`T3:namespace-fencing`) |
| `LossyUnknownTagTranslator` | Discards unrecognized tags under `"mycelium$c"` during round-trip | T4 (`T4:foreign-tag-preservation`) |
| `CrashingParseTranslator` | Throws an unchecked `RuntimeException` on malformed nodes | T5 (`T5:corruption-rejected-safely`) |
| `UnsortedKeysTranslator` | Serializes keys in reverse order instead of canonical sorted order | T6 (`T6:deterministic-ordering`) |

`NoOpTranslator` never crashes, writes no corrupt data, and is trivially fast. It is in the matrix because doing nothing must fail T1.

## 5. Using it: Platform Adapter Architecture

Consumers vendor `item.component@1` under their private namespace via `myceliumImport`. Platform adapters implement thin P2 bridges:

### 5.1 Modern Loaders (Fabric 1.21.1 / NeoForge 1.21.1)
- Modern `ItemStack` uses immutable `DataComponentMap`.
- The P2 adapter reads `DataComponentType<T>` registrations and populates a `CarrierSnapshot` using `CarrierSnapshot.builder()`.
- Negative patches (`DataComponentPatch` removals) map directly to `builder.withRemoval(id)`.
- Round-trips to network or disk invoke Mojang's native component codecs directly.

### 5.2 Legacy Loaders (Forge 1.20.1 / Fabric 1.20.1)
- Legacy `ItemStack` stores untyped `CompoundTag`.
- The P2 adapter inspects `tag.getCompound("mycelium$c")` and delegates to `StandardRosettaTranslator.INSTANCE.fromLegacyNbt(tagNode, registry)`.
- When mutating items, `StandardRosettaTranslator.INSTANCE.toLegacyNbt(snapshot, registry)` re-serializes components under `"mycelium$c"`, leaving vanilla root tags (`Damage`, `display`, `Enchantments`) untouched.
- If all components match registered defaults, `"mycelium$c"` is completely removed from root NBT (zero tag overhead).

## 6. Running the harness

From the Mycelium root: `bash run-contracts.sh item-component` (or `.\run-contracts.ps1 item-component`), or `./gradlew myceliumVerify -Pmycelium.contracts=item.component@1`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 7. Explicit non-claims

- No Minecraft or mod loader code was executed to produce this package.
- Native byte-level SNBT parsing and Mojang `DataComponentMap` binding belong to P2 platform adapters and consumer in-game gametest lanes.
