# 0005 — What a contract is made of

| | |
|---|---|
| Date | 2026-09-26 |
| Status | Accepted (kept by `ContractRun` and `myceliumRepository`; `CONTRACT-PACKAGE.md` states the rules) |
| Answers | worflor/mycelium#6, #7, #9, #10 (red team, 2026-09-26) |

## Context

Four issues asked what a contract package must contain, and what it may share.

- **#6.** `contracts/flow`, the package `AGENTS.md` told everyone to copy, kept its own copy of the harness and ran on
  it. It was the only one.
- **#7.** Two of flow's defensive paths were dead under test. The executor's restore path, which puts back what a
  destination did not take, could be deleted and every fixture still passed. The `INSERTION_FAILED` reason was
  declared but constructed nowhere.
- **#9.** The packages share no vocabulary. There are ten `Reason` enums, nine `Trait` enums and ten transaction
  classes. Should they?
- **#10.** Three packages (`chunk.hold@1`, `data.catalog@1`, `random.seeded@1`) had no `NoOp*` entry, so nothing
  showed their laws fail a do-nothing implementation. They also have no outcomes or receipts. Is that a defect, or a
  second kind of contract?

## Decision

**One harness (#6).** Every package runs on `mycelium.harness.ContractRun`, and none keeps a private `Check`,
`ContractRun`, `Suite` or `Seeded`. The rules every matrix keeps live in `ContractRun`, where the matrix runs, so they
hold whatever shape a package's `Run` takes. flow's copy is gone.

**Every defensive path is reached (#7).** A path no fixture reaches is a claim nobody checked. flow gains three
fixtures that drive providers the way real ones behave. They are not broken implementations of the contract; they are
behaviours every executor must handle (`DESIGN.md` §8.1: a promise of room is not room).
- **T11**: a destination that promises more room than it has. The remainder is restored, and a destination that
  takes nothing gives `Rejected(TRANSFER_FAILED)`.
- **T12**: a source that will not take the remainder back. The executor throws: an unconfirmed outcome is never an
  ordinary rejection (`DESIGN.md` §7).
- **T13**: a source that reports stock and gives none. That is `Rejected(EXTRACTION_FAILED)`: the source refused,
  not the destination.

The matrix gains `NoRestoreExecutor` and `UncheckedExtractionExecutor`, the two survivors, now caught.
`INSERTION_FAILED` is marked **reserved**: no v1 operation produces it, because changing which reason the
admits-nothing case returns is an outcome change, and that waits for a major that attempts insertion as its own step.

**Two kinds of contract (#10).** *Operation contracts* govern an operation's outcomes: a sealed outcome, receipts,
a transaction (`contracts/flow`). *Model contracts* govern a type's behaviour over any sequence of calls: a ledger, a
catalogue, a random source (`contracts/chunk-hold`). They need no outcome or receipt. Both are legitimate, and both keep
the same obligation: **a do-nothing implementation must fail**. Every matrix holds a `NoOp*` entry, and `ContractRun`
fails a matrix without one. The three model contracts gained `NoOpKeeper` (caught by H1), `NoOpCatalog` (T1) and
`NoOpSource` (T1). chunk-hold's H4 had no matrix entry at all; it gained `ReleaseAllOverreachKeeper`.

**Vocabulary stays per contract (#9).** Measured across the model packages:

| Name | Files | Distinct contents |
|---|---|---|
| `Reason` | 10 | 10 |
| `Trait` | 9 | 9 |
| `Feedback` | 6 | 5 |
| `PlayerId` | 4 | 2 |
| `Position` | 3 | 2 |
| transaction (`Tx`, `*Tx`, `*Transaction`) | 10 | 1, by rule |

The files share names, not meanings. Every `Reason` and every `Trait` lists its own contract's cases. The
byte-identical copies are almost all within one version family (`dimension.travel@1` and `@2`), which by design shares
nothing with its successor at run time. Across families, one identical `PlayerId` pair is about eight lines.

A shared vocabulary package would:
- **couple version lines.** Contract A at `@1` and contract B at `@2` would have to agree on one `Reason`, so changing
  one contract's cases would become a change to every contract;
- **be a package with no laws.** A no-op satisfies a vocabulary, so it cannot be a contract (the rule above), and
  Mycelium has no other kind of shared code (no runtime, `DESIGN.md` §1, §12);
- **save little.** The measured duplication outside the transaction is a few dozen lines.

So:
- A model never mentions another contract's package (checked by `myceliumRepository`).
- Contracts compose in the consumer's seams (L3), where a mod joins, say, a transfer's reason with a travel's reason
  in its own terms.
- The one substantial copy, the transaction, is a **copied pattern**: `contracts/patterns.json` names
  `FlowTransaction` as canonical, and every copy must equal it once comments, the package line and the class name are
  set aside. A change to the pattern is then a change to all copies at once, which is the only benefit sharing would
  have bought.

Revisit when a consumer measures its seam code and finds the joins costly, or when a pattern other than the
transaction is copied into a third package.

## Evidence

- Every package: reference clean, every entry caught, a `NoOp*` among them, on the shared harness. The totals are on
  `README.md`'s status line, derived by the build.
- flow's restore path: with it deleted, the reference fails T11 and `NoRestoreExecutor` is the implementation that
  does exactly that. Before T11, the deletion passed every fixture.
- `harness/test/mycelium/harness/ContractRunRules.java`: a matrix without `NoOp*` fails, however its `Run` is written.

## Consequences

- `CONTRACT-PACKAGE.md` states both kinds, and says which check keeps each rule.
- A new package that forgets its no-op fails `myceliumVerify`, the runner scripts and every era gate, not only a
  review.
- Composition remains the consumer's work, and `docs/admission.md` refuses a shared vocabulary package.
