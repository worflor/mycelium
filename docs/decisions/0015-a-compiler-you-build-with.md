# 0015 — Mycelium is a compiler you build with

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Accepted |
| Supersedes | The second-consumer admission threshold and the catalogue reading of `contracts/` |

## Context

The earlier documentation described contracts as capabilities to select and native patterns as candidates for promotion
after independent reuse. That encouraged consumers to write version splits locally whenever no existing package fit.

The implementation history showed a different model: a mod encountered a divide, the work added a contract or gate to
Mycelium, and the mod used it. Item components, counted chunk holds, and artifact linkage all grew this way.

## Options considered

- Treat Mycelium as a catalogue; require another consumer before adding a general contract.
- Develop the compiler alongside the first consumer, using laws and independent game evidence to keep the result honest.

## Decision

Mycelium is the machinery a mod builds with to turn a version or loader divide into declarations, laws, generated native
artifacts, and evidence.

- **One consumer is enough to start.** Model a meaningful divide when the mod encounters it and use the result in the
  same work. Laws, a rejection matrix, and consumer tests provide evidence; another consumer is useful validation,
  not an admission requirement.
- **Existing contracts are optional reuse and worked examples.** Reuse one when its meaning fits and reuse helps. Extend it additively or create
  a new major when needed. A mod's meaning does not change to fit an unrelated contract.
- **The machine grows with its consumers.** Changes to the harness, plugin, eras, and documentation remain subject to
  their gates and repository authority rules. A mod can develop those changes in its own Mycelium checkout or private
  fork. The compiler supplies the common compatibility machinery while the mod supplies its structure and contracts;
  upstream issues and pull requests are contribution routes, not dependencies of that loop.
- **Declarations stay specific.** Component codecs, presentation programs, and lifecycle policies are separate models.
  There is no universal effect language or runtime DSL.
- **Composition is explicit.** A contract can require another model through an acyclic registry dependency
  ([decision 0014](0014-contracts-compose-by-declared-requirement.md)).

Private imported code, native platform access, no-op rejection, loader-independent plugin logic, and the distinction
between model evidence and game evidence remain foundational. `DESIGN.md` stays frozen; later decisions record changes.

## Evidence

The contract and plugin history supplies the motivating cases: `item.component@1`, `chunk.hold@1`, and the linkage gate
were developed for concrete consumer needs. This decision changes the contribution posture; it adds no runtime claim.

## Consequences

The README introduces the compiler and adoption loop. The building guide explains how to cross a divide. Admission
criteria evaluate the quality of an addition rather than its number of consumers.

A future `localContracts` input could let a mod incubate a package under the same gates before moving it here.
It is not implemented; the [status board](../STATUS.md) tracks it. Work in another repository still requires that
repository owner's authorization.
