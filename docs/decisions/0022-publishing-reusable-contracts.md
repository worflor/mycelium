# 0022 — Publish contracts deliberately

| | |
|---|---|
| Date | 2026-10-18 |
| Status | Accepted |
| Builds on | Decision 0015: a compiler developed alongside its consumer |

## Context

A contract can be pure, well tested, and useful to its author while defining a feature the author has not chosen to
publish. General names do not turn that feature into shared infrastructure. The compiler must remain useful to a mod
whose contracts stay in its own checkout.

## Options

- Publish every contract developed with the compiler: exposes consumer designs as a condition of using the tooling.
- Generalize every feature before using it: adds abstraction work without an established reuse need.
- Publish reusable contracts deliberately and keep other definitions with the consumer's checkout.

## Decision

Use the third approach. This repository carries compiler machinery and contracts intentionally available for reuse.
A consumer may keep additional contracts in its own checkout or private fork under the same package rules and gates.
Compiler contributions do not require publishing the features that motivated them.

## Evidence

The registry and standalone runners discover the packages present in the checkout. Contract requirements are explicit;
the repository check rejects missing dependencies. Package selection therefore does not require a fixed upstream corpus.

## Consequences

Review a contribution's published meaning as well as its purity and matrix. Use synthetic examples and concise
provenance. Extract a smaller shared mechanism when it has a clear purpose, without weakening the original laws.
