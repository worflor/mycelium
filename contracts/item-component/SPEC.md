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
- `ComponentCodec<T>`: Pure serializer (`encode: T -> TagNode`, `decode: TagNode -> T throws CodecException`). Provides compositional value semantics: `freeze(T)`, `validate(T)`, `semanticEquals(T, T)`, `semanticHash(T)`.
- `RecordCodec<T>`: Record combinator over `ComponentCodec`: named required fields (`RecordCodec.field`), optional fields (`RecordCodec.optional`, read as `Optional`), each with its own codec, built into a codec that is also a `FieldAwareCodec`. `definition(id, default)` binds both, so fields the codec does not know survive an edit.
- `ComponentDefinition<T>(id, codec, defaultValue)`: Declares a typed component and deeply frozen default value.
- `ComponentRegistry`: Catalog of registered component definitions with duplicate conflict rejection and explicit `replace()` semantics.
- `CarrierSnapshot`: Immutable snapshot of an item's carrier state (typed components, negative patch removals, and preserved foreign/quarantined tags). Guarantees complete immutability via defensive views (`defensiveMapView`), deep array/collection cloning, and compositional deep equality.
- `ComponentPatch<T>`: Pure algebraic patch states (`Inherit`, `Set<T>`, `Remove<T>`).
- `Unit`: Pure singleton type (`Unit.INSTANCE`) representing presence of Minecraft 1.20.5+ unit components (e.g. `minecraft:glider`).
- `ItemPrototype`: Pure model of an item archetype baseline (base components table) separating prototype defaults from per-stack overrides and removals.
- `RecoveryRecord`: Structured recovery record preserving original locations, raw nodes, schema versions, and failure categories (`UNREADABLE_ENVELOPE`, `MALFORMED_CONTROL_SYNTAX`, `CORRUPT_PAYLOAD`, `UNREGISTERED_COMPONENT`, `ALIAS_COLLISION`) without competing with active component syntax.
- `FieldAwareCodec<T>`: Lens abstraction tracking consumed field footprints and retaining unconsumed remainder tags across decode-edit-encode cycles.
- `NativeProjectionRecipe`: Declarative recipes for projecting semantic components into native NBT fields below compound level (e.g. `display.Name`, bitmask fields like `HideFlags`), and tri-state reconciliation: $\operatorname{restoreEdited}(S_0, N_0, N_1)$.
- `LogicalNbtBridge`: Representation bridge between modern logical heterogeneous lists (1.21.5+) and target physical homogeneous NBT wrappers.
- `ChannelPolicy`: Specialized projection policies for `PERSISTENT`, `NETWORK`, `EFFECTIVE`, and `IDENTITY` (stacking equivalence).
- `TranslationPlan`: Compiled schema-bound translation plan coordinating component definitions, source/target prototypes, field-aware lenses, native recipes, and channel policies into an integrated compiler pipeline.
- `RosettaTranslator`: Pure bidirectional translator interface (`RosettaTranslator.standard()` provides `StandardRosettaTranslator`).

## 3. Laws

| Law | Statement |
|---|---|
| T1 | **Round-Trip Isomorphism:** For any registered component value and snapshot, $\text{fromLegacyNbt}(\text{toLegacyNbt}(S)) \equiv S$ preserving all values, removals, and keys. |
| T2 | **Default Elision:** Components whose value equals `defaultValue` are omitted from NBT. If all components are defaults and no unmapped tags or removals exist, the fenced compound is omitted entirely. |
| T3 | **Namespace Fencing:** All component data in legacy NBT resides strictly inside `"mycelium$c"`. Root NBT contains zero component keys. |
| T4 | **Foreign Tag Preservation:** Tags under `"mycelium$c"` not present in `ComponentRegistry` survive serialization round-trip unmodified. |
| T5 | **Diagnostic Rejection & Anti-Data Loss Quarantine:** Malformed tags (type mismatch, invalid syntax, non-compound envelopes, malformed control records) yield structured fallback without loss, quarantining raw evidence under distinct recovery keys so re-saving preserves data without destruction. |
| T6 | **Deterministic Key Ordering:** Serialized legacy NBT entries under `"mycelium$c"` are emitted in lexicographical key order on every platform, guaranteeing byte-reproducible serialization. |
| T7 | **Prototype Canonicalization:** $\operatorname{decode}_P(\operatorname{encode}_P(S)) = \operatorname{canonicalize}_P(S)$. Overrides matching the prototype's base components are elided back to Inherit. |
| T8 | **Ingress Immutability & Compositional Equality:** Once a value enters a snapshot, no reference available to the caller can change its meaning, equality, or hash code. Compositional equality correctly traverses nested arrays, lists, maps, and records. |
| T9 | **Field-Aware Remainder Preservation:** Updating a known subfield in a compound retains all unconsumed future/foreign subfields across edit cycles. |
| T10 | **Tri-State Native Reconciliation:** Legitimate native edits observed between emitted baseline $N_0$ and later native state $N_1$ are reconciled back into semantic edits: $\operatorname{restoreEdited}(S_0, N_0, N_1)$, preserving unrelated modern retained state. |
| T11 | **Stacking Identity Invariant:** Two items stack if and only if their `IDENTITY` channel views are equal, guaranteeing that distinct retained modern states never merge. |
| T12 | **Record Fidelity:** A `RecordCodec` round-trips its value; a missing optional field decodes as absent and an absent optional is never written; a missing required field or a field of the wrong shape is a `CodecException` (so T5 quarantines the component with its raw compound); fields, remainder included, are written in sorted key order (T6); fields the codec does not know are kept across decode, edit and encode (T9). |

## 4. Rejection Matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `NoOpTranslator` | Returns an empty snapshot on decode and empty tag on encode | T1 (`T1:round-trip-isomorphism`) |
| `DefaultWritingTranslator` | Serializes default component values into NBT instead of eliding them | T2 (`T2:default-elision`) |
| `RootPollutingTranslator` | Writes component keys directly to root NBT rather than under `"mycelium$c"` | T3 (`T3:namespace-fencing`) |
| `LossyUnknownTagTranslator` | Discards unrecognized tags under `"mycelium$c"` during round-trip | T4 (`T4:foreign-tag-preservation`) |
| `CrashingParseTranslator` | Throws an unchecked `RuntimeException` on malformed nodes | T5 (`T5:corruption-rejected-safely`) |
| `UnsortedKeysTranslator` | Serializes keys in reverse order instead of canonical sorted order | T6 (`T6:deterministic-ordering`) |
| `RemainderDroppingRecords` | A record drops the fields it does not know when an edited value is written back | T12 (`T12:record-remainder`) |
| `AbsentWritingRecords` | A record writes an absent optional field (as an empty compound) | T12 (`T12:record-optional-absent`) |
| `ForgivingRecords` | A record with a malformed or missing field decodes as the default instead of throwing `CodecException` | T12 (`T12:record-malformed-rejected`) |
| `UnsortedRecords` | A record writes its fields in reverse key order | T12 (`T12:record-sorted`) |

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

### 5.3 A record component

A small record is declared once: its fields, then the codec built from them. `definition` registers it with the lens,
so a compound written by a newer version of the mod (an extra field) is not damaged by an older one.

```java
record Charm(String owner, int level, Optional<String> memory) {}

Field<Charm, String> OWNER = RecordCodec.field("owner", ComponentCodec.STRING, Charm::owner);
Field<Charm, Integer> LEVEL = RecordCodec.field("level", ComponentCodec.INT, Charm::level);
Field<Charm, Optional<String>> MEMORY = RecordCodec.optional("memory", ComponentCodec.STRING, Charm::memory);

RecordCodec<Charm> CODEC = RecordCodec.of(
        r -> new Charm(r.get(OWNER), r.get(LEVEL), r.get(MEMORY)), OWNER, LEVEL, MEMORY);

ComponentRegistry registry = ComponentRegistry.builder()
        .register(CODEC.definition(ComponentId.of("mymod:charm"), new Charm("", 0, Optional.empty())))
        .build();
```

`{"owner":"steve","level":3}` decodes with `memory` absent and encodes back without it. A maker that rejects its
fields (a constructor that throws) is also a `CodecException`. The maker is called on decode and on `freeze`, and must
build the same value from the same fields each time.

## 6. Running the harness

From the Mycelium root: `bash run-contracts.sh item-component` (or `.\run-contracts.ps1 item-component`), or `./gradlew myceliumVerify -Pmycelium.contracts=item.component@1`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 7. Explicit non-claims

- No Minecraft or mod loader code was executed to produce this package.
- Native byte-level SNBT parsing and Mojang `DataComponentMap` binding belong to P2 platform adapters and consumer in-game gametest lanes.
