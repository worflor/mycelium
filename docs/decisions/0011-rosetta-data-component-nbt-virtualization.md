# 0011 — Rosetta: Data Component <-> NBT Virtualization

| | |
|---|---|
| Date | 2026-10-11 |
| Status | Accepted (`item.component@1` landed; reference clean; 5 rejection fixtures caught; `./gradlew check` PASS on Java 8 through 25) |
| Evidence | `contracts/item-component/` harness (6 assertions, 50 seeded iterations); `./gradlew check` 18 tasks green |

## Context

Modern Minecraft uses typed data components; older versions use mutable NBT compounds. A mod should be able to
keep an immutable component model while targeting both representations. The conversion belongs in a checked
contract rather than repeated consumer serialization code.

## Decision

Land `item.component@1` in `contracts/item-component`:

1. **Pure Algebraic Model (`TagNode`):** A sealed, zero-dependency hierarchy representing complete NBT AST in pure Java
   without importing Minecraft or loader classes.
2. **Deterministic Carrier State (`CarrierSnapshot`):** An immutable mapping of typed components and preserved unmapped tags.
3. **Six Mathematical Laws:**
   - **T1 Round-Trip Isomorphism:** $\text{fromLegacyNbt}(\text{toLegacyNbt}(S)) \equiv S$ for all valid values.
   - **T2 Default Elision:** Components matching their registered `defaultValue()` emit zero tags in NBT. If all components
     are defaults, the fenced payload is omitted completely, guaranteeing clean, zero-overhead item stacks and vanilla
     stackability on 1.20.1.
   - **T3 Namespace Fencing:** All component data is strictly quarantined inside `"mycelium$c"`. Root NBT tags (`Damage`,
     `Enchantments`, `display`) are never touched or polluted.
   - **T4 Foreign Tag Preservation:** Tags under `"mycelium$c"` from other versions or add-ons survive round-trip unmodified.
   - **T5 Diagnostic Rejection on Corruption:** Malformed tags reject safely and fall back to defaults rather than throwing
     unchecked runtime exceptions.
   - **T6 Deterministic Key Ordering:** `TagCompound` entries sort lexicographically, guaranteeing identical byte output.
4. **Java 8 Linkage Hardening:** Explicit `toString()` implementations on primitive tag nodes (`TagByte`, `TagShort`)
   ensure clean JvmDowngrader lowering and linkage resolution across every historical era back to Java 8.

## The In-Game Lane Strategy (The Rosetta Game-Checker)


## Consequences

- Cartomancy and future Mycelium mods write pure modern Java 21 Data Components with zero legacy NBT debt.
- The repository count rises to 19 contract packages and 141 rejection fixtures, all proven on every supported era JVM.
- `README.md` and `CHANGELOG.md` updated to reflect the new capability.
