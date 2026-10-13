# item.component@1

A deterministic, bidirectional translation and carrier model bridging modern Minecraft Data Components
(1.20.5+) and legacy item NBT compounds (pre-1.20.5, specifically 1.20.1). Written for Cartomancy and any
Mycelium mod bridging the Data Component era divide without compromising pure Java 21 flyweight designs.

## 0. What is proven, and what is only designed

This contract was written for Cartomancy and first used by Somnium. The marks below say what a consumer has actually used, so a new consumer knows what
stands on evidence and what stands on design alone. The laws of both kinds are checked by the harness; the difference is
whether a mod has put the part to work.

| Part | Status |
|---|---|
| `TagNode`, `ComponentCodec`, `RecordCodec`, `ComponentDefinition`, `ComponentRegistry`, `CarrierSnapshot`, `Unit` | **Proven by a consumer.** Somnium (2026-10) declares its whole item state with them: record components, marks, values with no default. |
| `RosettaTranslator` / `StandardRosettaTranslator` (fenced read and write: T1 to T6, T9, T12) | **Proven by a consumer.** Somnium reads and writes every stack through it, on every version and loader it builds. |
| Quarantine and recovery records (T5) | **Proven by a consumer.** Somnium relies on an unreadable entry reading as absent and surviving later writes. |
| Presence components, `FencedCarrier`, `WriteOutcome`, `ComponentRead`, `LegacyKeyMigration` (T13 to T20) | **Designed from Somnium's adapter, not yet used by a consumer.** Written to replace what its `Carrier` had to build around the translator (hand-written marks, a separate single-component decode, a legacy-key reader and stripper). Their first real use is the evidence they still need. |
| `StackCarrier` (S1 to S6, section 5.7) and its bridge `item-carrier` | **Designed from Somnium's adapter; first used by Somnium (2026-10).** The port a platform stack satisfies; Mycelium's bridge implements it for `ItemStack` on every version it covers (decision 0016), and its conformance game tests are what keep S1 to S6. |
| `TranslationPlan`, `ItemPrototype` (T7), `ChannelPolicy` (T11), `NativeProjectionRecipe` (T10), `LogicalNbtBridge` | **Design only.** Checked by the harness and its adversarial probes; no consumer has used them yet (Somnium keeps `minecraft:custom_data` as its only carrier and uses the bridge only for list encoding). Treat their shapes as provisional until one does. |

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
- `ComponentDefinition.presence(id)` / `ComponentDefinition.withoutDefault(id, codec)`: a **presence component** is a `Unit` mark whose meaning is that it is there; a component **without a default** has no value T2 may elide (an `int` 0 that means "zero", not "unset"). `hasDefault()` and `isPresence()` say which a definition is. `RecordCodec.definition(id)` and `ComponentRegistry.Builder.registerPresence / registerWithoutDefault` are the same for records and registries; `CarrierSnapshot.has(def)` and `withMark(def)` read and set a mark.
- `FencedCarrier` (`StandardFencedCarrier`): the carrier on a stack's root compound (on a modern stack, `minecraft:custom_data`), as pure operations over a `TagCompound`: `read(root)` is the whole snapshot, `read(root, def)` one component by the same path (`ComponentRead`: `Present`, `Absent`, `Removed`, `Quarantined`), and `write(root, snapshot, mode)`, `set(root, def, value, mode)`, `clear(root, def, mode)` return a `WriteOutcome` (`Written(root, changed)` or `Refused(reason, detail, root)`). The `OnQuarantine` mode (`REFUSE` by default, `KEEP`, `REPLACE`) says what a write does with unreadable data it meets.
- `LegacyKeyMigration`: a declared table of a mod's old ad-hoc keys and the components they become (root keys or keys inside an old compound; one key to a whole component with the old shape as a codec, several keys to the fields of one component with a `Reader`, a mark from a key, or a retired key to drop). `FencedCarrier` applies it on read and strips the keys it accounted for on write.

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
| T13 | **Presence Components:** A component declared with `presence` or `withoutDefault` is written whenever a stack carries it, even when its value equals what a default would have been (a mark is `{}`, an `int` 0 is 0), and reads back present; one a stack does not carry is absent, never made present by a read, and a cleared stack is again exactly the plain one; an explicit removal round-trips. T2 still elides the real defaults of other components in the same registry. |
| T14 | **Typed Write Outcomes:** `write`, `set` and `clear` return `Written(root, changed)`, where `changed` is true exactly when `root` differs from what was given, or `Refused(reason, detail, root)` with the given root untouched. A write that would move or discard unreadable data is refused (`ENVELOPE_QUARANTINED` for a fenced entry that is not a compound, `COMPONENT_QUARANTINED` for an entry or old key that does not decode), never a silent no-op, under the default `REFUSE`. `KEEP` applies the write and keeps the unreadable data under the fence; no mode but `REPLACE` loses it, and `REPLACE` does (the adapter decided). Evidence an earlier `KEEP` kept stays kept through later ordinary writes. Writing back what was read destroys nothing and is written. |
| T15 | **One Decode Path:** Reading one component is the whole-snapshot read looked up: for every root and component, `read(root, def)` is `Present` exactly when `read(root)` has it (with an equal value), `Removed` exactly when it is removed, and `Quarantined` (carrying the raw evidence) when its entry, its old key or the whole envelope is stored but unreadable, otherwise `Absent`. Quarantine is visible to the single read and never throws. |
| T16 | **Migrated Values:** For a root carrying old keys the table names, `read` yields the components the table says (the value its codec or reader gives), by key path, whatever integer width the old tag had, filling missing keys of a several-key rule as its reader says; a root with none of them migrates nothing. |
| T17 | **Migration Strips:** A write after a migrated read stores the component in the fence and removes the old keys that gave it (and compounds that removal leaves empty); the old key does not bring back a component that was cleared. |
| T18 | **Migration Is Idempotent:** `read(write(read(x)))` equals `read(x)` and a second write changes nothing. The fenced state is authoritative: an old key beside a fenced value (or an explicit removal) does not override it, and is stripped on write. |
| T19 | **Unreadable Old Keys Are Quarantined:** An old value that does not decode is kept under the fence (`"!legacy:" + key`, with a recovery record), not dropped, and the component reads `Quarantined`; if any key of a several-key rule is unreadable all of its keys are kept. Old keys a write does not account for (a snapshot not read from this root) and old keys beside an unreadable fenced entry for the same component are left where they are. |
| T20 | **Untouched Keys:** A read or write touches no root key the table does not name: other mods' keys, look-alike keys, siblings inside an old compound. A root with nothing the table names changes only by the fence. |

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
| `ElidingMarksTranslator` | Treats a component with no default as elidable, so a mark is never written | T13 (`T13:no-default-written`) |
| `MaterializingMarksTranslator` | A presence component that was not stored reads back present | T13 (`T13:absence-is-absence`) |
| `SilentNoOpCarrier` | A write the quarantine stops comes back as "nothing changed" with no reason | T14 (`T14:refused-with-reason`) |
| `QuarantineDestroyingCarrier` | Every write behaves as `REPLACE` and discards unreadable data | T14 (`T14:no-data-loss`) |
| `LyingChangedCarrier` | Every `Written` says `changed`, even when the root is as it was | T14 (`T14:written-reports`) |
| `StubbornCarrier` | `REPLACE` is read as `REFUSE`, so unreadable data can never be written over | T14 (`T14:replace-discards`) |
| `DirectEntryDecodeCarrier` | Reads one component by decoding its fenced entry directly, missing migrated keys, the envelope and removals | T15 (`T15:same-decode-path`) |
| `QuietReadCarrier` | A stored but unreadable component reads as absent | T15 (`T15:quarantine-visible`) |
| `IgnoringMigrationCarrier` | Accepts the migration table and never applies it | T16 (`T16:migration-values`) |
| `KeyRetainingCarrier` | Writes the migrated component and leaves the old keys | T17 (`T17:migration-strips`) |
| `StaleOverwritingCarrier` | An old key beside a fenced value wins on read | T18 (`T18:migration-idempotent`) |
| `DroppingMigrationCarrier` | Strips every old key on write, readable or not | T19 (`T19:migration-quarantine`) |
| `OverreachingMigrationCarrier` | Strips every root key that starts like an old key | T20 (`T20:migration-untouched`) |

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

### 5.4 A mark, and a value with no default

A mark's meaning is that it is there. Declare it as one, so it is never elided and never made up:

```java
ComponentDefinition<Unit> GLOWING = ComponentDefinition.presence(ComponentId.of("mymod:glowing"));
ComponentDefinition<Integer> RIG = ComponentDefinition.withoutDefault(ComponentId.of("mymod:rig"), ComponentCodec.INT);
// a record with no default: RECORD_CODEC.definition(ComponentId.of("mymod:tool_state"))

ComponentRegistry registry = ComponentRegistry.builder().register(GLOWING).register(RIG).build();
```

A stack carries `GLOWING` as `{}` under the fence, or does not carry it; `RIG` 0 is written as 0, because 0 is a rig id and not
"unset". A component that has a real default (`new ComponentDefinition<>(id, codec, 0)`) is still elided at it (T2).

### 5.5 The carrier: reading, writing, and what a write can say

The adapter turns the platform's compound (`custom_data`) into a `TagNode.TagCompound` and back, and does everything else
through one `FencedCarrier`:

```java
FencedCarrier carrier = FencedCarrier.standard(registry, MIGRATION);   // MIGRATION: 5.6, or omit it

ComponentRead<Integer> rig = carrier.read(root, RIG);   // Present / Absent / Removed / Quarantined
boolean glowing = carrier.read(root, GLOWING).isPresent();

switch (carrier.set(root, RIG, 4)) {
    case WriteOutcome.Written w -> { if (w.changed()) platformSet(w.root()); }
    case WriteOutcome.Refused r -> {
        // r.reason(): ENVELOPE_QUARANTINED or COMPONENT_QUARANTINED; r.root() is the root as given.
        // Decide: report it, leave the stack alone, or ask again with KEEP (apply and keep the unreadable data)
        // or REPLACE (apply and discard it): carrier.set(root, RIG, 4, OnQuarantine.KEEP)
    }
}
```

`read(root, def)` is the snapshot read looked up (T15), so there is one way a component is decoded and it already includes
migration and quarantine; the adapter has no decode of its own to keep in step. A cleared stack is again the plain one, so
it still stacks.

### 5.6 Old keys

Every mod adopting Rosetta has item state under keys of its own. Declare where each goes, once:

```java
LegacyKeyMigration MIGRATION = LegacyKeyMigration.builder()
        .key("mymod_rig", RIG, ComponentCodec.INT)                      // one key, the whole value, old shape = a codec
        .mark("mymod_glowing", GLOWING)                                 // a key that was a flag
        .keys(List.of(Key.of("mymod_owner"), Key.of("mymod_issue")), OWNERSHIP,   // several keys, the fields of one value
                v -> new Ownership(v.string("mymod_owner", ""), v.integer("mymod_issue", 0)))
        .key(Key.of("mymod", "name"), NAME, ComponentCodec.STRING)      // a key inside an old compound
        .drop("mymod_dye")                                              // retired: read nowhere, stripped on write
        .build();
```

On read the old values appear as components; the next write stores them fenced and removes the old keys. An old value that
does not decode is kept under the fence as `"!legacy:<key>"` with a recovery record and the component reads `Quarantined`
(T19). The fenced state wins over an old key, so a stack that already moved on is not pulled back (T18). When the last world
that has the old keys is gone, delete the table: nothing else changes.

### 5.7 The stack port, and the bridge that implements it

`FencedCarrier` works on a root compound. Something has to take the root out of a stack and put it back, and convert the platform's tag to a `TagNode`: on 1.20.5 and later the `minecraft:custom_data` component, before it the stack's root tag, with a tag API that shifts inside each. That is a fact about the game, not about the mod, so Mycelium ships it as a bridge (`bridges/item-carrier`, `docs/decisions/0016-bridges.md`) and this contract declares the port it implements:

```java
StackCarrier<ItemStack> items = ItemCarrier.of(registry, MIGRATION);     // the bridge's class, imported under the mod's namespace
items.set(stack, RIG, 4);
Optional<Integer> rig = items.get(stack, RIG);
boolean glowing = items.has(stack, GLOWING);
```

`StackCarrier<S>` repeats `FencedCarrier`'s operations on a stack. A stack has laws a root compound cannot state, because they are about what the game does with the result. They are kept by the bridge's conformance game tests, which run in the real game on every target the mod builds (and apply to a mod's own override of the bridge):

| Law | Statement |
|---|---|
| **S1 round trip** | what `set` puts on a stack, `read` gives back, for a value, a record, a mark and a value with no default |
| **S2 plain** | a stack with nothing left to carry holds no tag or component of its own: it equals a plain stack and stacks with it (a null tag and an empty tag do not stack before 1.20.5, so clearing leaves null) |
| **S3 other keys kept** | keys of the stack's root that are not the fence's (another mod's) survive every write, and a stack whose only other keys remain is not made plain |
| **S4 refuse, not lose** | a write that meets unreadable data returns `Refused` and leaves the stack exactly as it was; `KEEP` applies it and the data survives |
| **S5 the stack travels** | a copy, a save and reload through the game's own stack codec, and the stack as a client receives it from the network, read the same state and are the same stack |
| **S6 empty and unchanged** | an empty stack carries nothing and a write to it changes nothing; a write that changes nothing (`changed` false) leaves the stack as it was |

A mod that does not want the bridge writes its own class against the same interface (`bridges { override("item-carrier", "com.example.MyCarrier") }`), or skips the port and writes native code; the laws are the same.

## 6. Running the harness

From the Mycelium root: `bash run-contracts.sh item-component` (or `.\run-contracts.ps1 item-component`), or `./gradlew myceliumVerify -Pmycelium.contracts=item.component@1`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 7. Explicit non-claims

- No Minecraft or mod loader code was executed to produce this package.
- Native byte-level SNBT parsing and Mojang `DataComponentMap` binding belong to P2 platform adapters and consumer in-game gametest lanes.
- The carrier laws (T13 to T20) have not yet been exercised by a consumer's adapter; the translator, records and fenced read/write have (section 0).
- S1 to S6 are laws of the platform's stack, not of the pure model: the harness does not run them. The bridge's conformance game tests do, in a real game, and a bridge or an override has them only where a mod has run those tests.
- Additions of this kind (presence definitions, `FencedCarrier`, `LegacyKeyMigration`, `StackCarrier`) are additive within `@1`: no existing law, label or required method changed. `@2` is reserved for a change of meaning.
