# 0011 — Rosetta: typed components across the item NBT divide

| | |
|---|---|
| Date | 2026-10-11 |
| Status | Accepted; implemented as `item.component@1` |
| Provenance | Written for Cartomancy |
| Specification | [item.component@1](../../contracts/item-component/SPEC.md) |

## Context

Minecraft 1.20.5 replaced root item NBT with data components. A mod targeting both representations needs a stable meaning
for typed values, defaults, removals, unknown fields, and corrupt data. Repeating that translation in platform adapters
makes preservation rules depend on the target.

## Options considered

- Keep the conversion in each consumer's adapters.
- Model the conversion independently of Minecraft, with adapters responsible for reading and writing the native carrier.

## Decision

Use a pure carrier model and translator. `TagNode` represents NBT without Minecraft types; component definitions bind ids,
codecs, and defaults; `CarrierSnapshot` holds typed state and retained data. The translator fences its payload under
`mycelium$c`, elides registered defaults, preserves unknown entries, and quarantines unreadable data.

The SPEC defines the laws and rejection matrix. The initial matrix covered round trips, default elision, namespace
fencing, unknown-tag preservation, corruption handling, and deterministic key ordering. Later additions extend that
model within the same major.

Native adapters own the platform operation: where the fenced payload lives, how it is copied, and whether clearing it
leaves a null or empty tag. Those properties need tests on real stacks. [Decision 0016](0016-bridges.md) subsequently
moved the reusable adapter work into bridges.

## Evidence

The recorded implementation run passed the reference harness and caught its six rejection fixtures.
`./gradlew check` passed, including the era gates from Java 8 through Java 25. Compatibility work included explicit
tag formatting and replacement of APIs the Java 8 lowering could not support.

These runs prove the pure model and its Java compatibility. The modern and legacy game-test lanes described during
design were verification plans, not completed game evidence. The SPEC separates consumer evidence from model-only work.

## Consequences

Consumers declare component state once and adapt its carrier per target. This does not convert every legacy native
field automatically or establish stackability by itself; those claims require a native implementation and game tests.
The package uses Java 17 source and has no Minecraft or loader dependency.
