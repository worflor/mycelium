# 0002 — What the first full consumer taught the contracts

| | |
|---|---|
| Date | 2026-09-23 |
| Status | Accepted |
| Evidence | Consumer review and corresponding Mycelium rejection fixtures |

## Context

Consumer testing exposed omissions in contract laws. An implementation could satisfy the stated interface while
losing a return trip across a restart, copying an unsafe exit, or reporting imports that an artifact never used.
These failures belong in reusable laws and checks rather than repeated consumer workarounds.

## Decision

Add a law and a rejection fixture when the existing major can express the requirement without changing its meaning.
Introduce a new major when the required model changes. Journaled trips, sealed copies, and source-derived usage are
the general requirements; consumer-owned features and implementation notes remain outside this record.

## Consequences

The contract suite must reject these failures before a native implementation is written. A passing model still needs
consumer game evidence. Each artifact records its own contents rather than relying on a central adoption ledger.
