# 0011 — Rosetta: Data Component <-> NBT Virtualization

| | |
|---|---|
| Date | 2026-10-11 |
| Status | Accepted (`item.component@1` landed and hardened; reference clean; 6 rejection fixtures caught; `./gradlew check` PASS on Java 8 through 25) |
| Evidence | `contracts/item-component/` harness (6 assertions, 50 seeded iterations); `./gradlew check` 18 tasks green |

## Context

Modern Minecraft uses typed data components; older versions use mutable NBT compounds. A mod should be able to
keep an immutable component model while targeting both representations. The conversion belongs in a checked
contract rather than repeated consumer serialization code.

## Decision

Land and harden `item.component@1` in `contracts/item-component`:

1. **Pure Algebraic Model (`TagNode`):** A sealed, zero-dependency hierarchy representing complete NBT AST in pure Java
   without importing Minecraft or loader classes. Enforces element type homogeneity on `TagList` and key ordering preservation.
2. **Deterministic Carrier State (`CarrierSnapshot`):** An immutable mapping of typed components and preserved unmapped tags.
3. **Six Mathematical Laws & Rejection Matrix:**
   - **T1 Round-Trip Isomorphism:** $\text{fromLegacyNbt}(\text{toLegacyNbt}(S)) \equiv S$ for all valid values (`NoOpTranslator`).
   - **T2 Default Elision:** Components matching their registered `defaultValue()` emit zero tags in NBT. If all components
     are defaults, the fenced payload is omitted completely, guaranteeing clean, zero-overhead item stacks and vanilla
     stackability on 1.20.1 (`DefaultWritingTranslator`).
   - **T3 Namespace Fencing:** All component data is strictly quarantined inside `"mycelium$c"`. Root NBT tags (`Damage`,
     `Enchantments`, `display`) are never touched or polluted (`RootPollutingTranslator`).
   - **T4 Foreign Tag Preservation:** Tags under `"mycelium$c"` from other versions or add-ons survive round-trip unmodified (`LossyUnknownTagTranslator`).
   - **T5 Diagnostic Rejection on Corruption:** Malformed tags reject safely and fall back to defaults rather than throwing
     unchecked runtime exceptions (`CrashingParseTranslator`).
   - **T6 Deterministic Key Ordering:** `TagCompound` entries sort lexicographically, guaranteeing identical byte output (`UnsortedKeysTranslator`).
4. **Java 8 Linkage Hardening:** Explicit `toString()` implementations on primitive tag nodes (`TagByte`, `TagShort`),
   `Arrays.toString()` on array tags, lenient numeric coercion on `BOOLEAN`, `listOf` combinator, and strict elimination of
   post-Java-8 APIs (`Optional.isEmpty()`, `String.isBlank()`) ensure clean JvmDowngrader lowering and linkage resolution across
   every historical era back to Java 8.

## P2 Platform Adapters Blueprint

Contract packages are strictly pure (P1/P3). The consumer binds Rosetta via platform adapters (P2):

### 1. Modern 1.21.1 Adapter (`ModernRosettaAdapter`)
On 1.21.1, the carrier is Mojang's native `DataComponentMap`:
```java
public final class ModernRosettaAdapter {
    public static CarrierSnapshot snapshotOf(ItemStack stack, ComponentRegistry registry) {
        CarrierSnapshot snapshot = CarrierSnapshot.empty();
        for (ComponentDefinition<?> def : registry.definitions()) {
            ComponentType<?> type = lookupComponentType(def.id());
            if (stack.has(type)) {
                snapshot = snapshot.with(def.id(), stack.get(type));
            }
        }
        return snapshot.normalized(registry);
    }
}
```

### 2. Legacy 1.20.1 Adapter (`LegacyRosettaAdapter`)
On 1.20.1, the carrier is `CompoundTag`:
```java
public final class LegacyRosettaAdapter {
    public static CarrierSnapshot snapshotOf(ItemStack stack, ComponentRegistry registry, RosettaTranslator translator) {
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(RosettaTranslator.FENCED_NAMESPACE, Tag.TAG_COMPOUND)) {
            return CarrierSnapshot.empty();
        }
        TagNode.TagCompound node = MojangNbtBridge.toTagNode(root);
        return translator.fromLegacyNbt(node, registry);
    }

    public static void apply(ItemStack stack, CarrierSnapshot snapshot, ComponentRegistry registry, RosettaTranslator translator) {
        CarrierSnapshot norm = snapshot.normalized(registry);
        if (norm.isEmpty()) {
            if (stack.hasTag()) {
                stack.getTag().remove(RosettaTranslator.FENCED_NAMESPACE);
                if (stack.getTag().isEmpty()) {
                    stack.setTag(null); // Critical: null tag preserves vanilla stackability
                }
            }
            return;
        }
        TagNode.TagCompound fenced = translator.toLegacyNbt(norm, registry);
        CompoundTag root = stack.getOrCreateTag();
        MojangNbtBridge.mergeInto(root, fenced);
    }
}
```

## The In-Game Lane Strategy (The Rosetta Game-Checker)


## Consequences

- Cartomancy and future Mycelium mods write pure modern Java 21 Data Components with zero legacy NBT debt.
- The repository count stands at 19 contract packages and 142 rejection fixtures, all proven on every supported era JVM.
- `README.md` and `CHANGELOG.md` updated to reflect the hardened capability.
