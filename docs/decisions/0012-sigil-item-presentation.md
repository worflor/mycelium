# 0012 — The Sigil: compile item presentation from component state

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Accepted; implemented as `item.presentation@1` and the `item-presentation` bridge |
| Provenance | First adopted by Somnium |
| Builds on | `item.component@1`; [declared requirements](0014-contracts-compose-by-declared-requirement.md) |
| Specification | [item.presentation@1](../../contracts/item-presentation/SPEC.md) |

## Context

An item whose appearance depends on state needs different artifacts across the 1.21.4 model boundary:

- An item definition selects models using custom model data strings.
- An older item model uses ascending float overrides, where the last matching threshold wins.

Handwritten tables and selector arithmetic repeat the same fact. A wrong selector can draw the wrong model without a
build failure.

## Options considered

- Maintain each era's assets and selector logic separately.
- Compile assets and a runtime selection plan from one finite state program.

## Decision

Declare a program over component fields with finite domains, ordered cases, a base model, and optional display context
rules. Compile it into each target's native assets and a plan evaluated by the same pure model at runtime.

Legacy float codes come from a committed, append-only lock. New selections append; retired selections keep their codes
and draw the base model. The lock is required on modern targets too, so building a different target cannot allocate a
different code. A selection's key is its model reference.

Compilation checks every input against a component catalog, including network visibility. A server-private field is
refused. The consumer checks `catalog.disagreements(registry)` in its own tests because the build cannot load its registry.

`myceliumPresentation` generates resources before `processResources`. `myceliumPresentationLock` updates the lock only
when explicitly run. Target realizations come from `eras/minecraft.json`: the compiler contains no version switches.

The SPEC owns the laws: totality, asset/selector agreement, stable codes, override ordering, client projection,
determinism, bounded enumeration, and display contexts.

## What adoption established

- Scalar components need inputs with no field path; presence components must be recognized as units.
- An absent field uses an input default. Out-of-domain state draws the base and reports that it was out of domain.
- Modern display contexts can choose a different model in a hand or inventory. Legacy overrides cannot express that
  distinction and ignore context rules; the difference is explicit.
- A legacy model cannot use itself as its parent. Compilation refuses that model clash.
- Visual state must be declared as state when it is not derivable from the existing snapshot.

These additions preserved the major's laws and labels. The runtime bridge writes the generated plan's selector onto a
stack and has conformance cases over real stacks and raw display-entity item data.

## Evidence

The reference compiler passed and each initial rejection fixture was caught for its intended label.
The plugin self-tests, the standalone `presentation-consumer` fixture, and `./gradlew check` passed.

Consumer adoption recorded game evidence on 26.3 and 1.21.1. Other bridge versions have linkage evidence where name data
is pinned; game behavior on them remains unproven. Conformance resources are excluded from the shipping jar by default;
conformance classes require `conformanceClassesOutOfJar()` when they share `main`.

## Consequences

The program, selector, plan, and assets share one source of truth. The lock becomes durable save-compatibility data.
Layered presentation and mechanisms such as music-disc models remain separate work when a consumer needs them.
