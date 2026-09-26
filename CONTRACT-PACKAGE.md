# Contract packages

A contract is a typed, versioned promise about one domain capability: what goes in, what comes out, what may never
happen and what must. A package holds the promise (`model/`), a reference implementation that keeps it and a
rejection matrix of implementations that break it (`fixtures/`), and the harness that proves both (`harness/`). It
is plain Java 17 and never imports Minecraft or a loader. What belongs in one, and what does not, is in
`docs/admission.md`.

```
contracts/<slug>/
├── SPEC.md                            the contract, normative (sections below)
└── src/<package path>/                the registry's package, dots as slashes: mycelium/<slug_with_underscores>/v<N>
    ├── model/                         the promise: the only code a consumer imports
    ├── fixtures/                      the reference implementation and every broken one the matrix names
    └── harness/
        ├── <Name>ContractTests.java   the fixtures, as labelled checks
        └── Run.java                   main(): mycelium.harness.ContractRun.run(id, reference, matrix)
```

## Two kinds of contract

- **Operation contracts** govern an operation's outcomes: a transfer, a trip, a copy. They have a sealed outcome,
  receipts and a transaction. `contracts/flow` is the model to copy (`DESIGN.md` §7, §8).
- **Model contracts** govern a type: a ledger of holds, a reloadable catalog, a seeded random source. Their laws are
  about the type's behaviour over any sequence of calls, and they need no outcome or receipt. `contracts/chunk-hold`
  is the model to copy.

Both keep every rule below; rules 6 and 7 apply to operation contracts only.

## Rules

Each rule says what keeps it. A rule nothing keeps is a wish; add the check or say so.

1. **Registered.** A row in `contracts/registry.json` declares the id (`domain.capability@N`), the path (the slug,
   kebab-case) and the package, in the same change as the package. `supersedes` names the contract a new major
   replaces; a build may import one of the two, never both. `provenance` says in words who it was written for.
   *Kept by `myceliumRepository` (rows against directories, both ways) and `ContractRegistry` (unknown fields refused).*
2. **Shaped.** `SPEC.md`, then `model/`, `fixtures/` and `harness/` under the package path, and exactly one
   `Run.java`, in that `harness/`, where every runner starts. *Kept by `myceliumRepository` and both runner scripts.*
3. **On the shared harness.** `Run` calls `mycelium.harness.ContractRun.run`. No package keeps its own `Check`,
   `ContractRun`, `Suite` or `Seeded`, so every report reads alike and every rule below is kept in one place.
   *Kept by `myceliumRepository` (private copies) and `myceliumVerify` (a harness that writes no report fails).*
4. **A no-op fails.** The matrix holds a do-nothing implementation named `NoOp*`, and a law catches it. Safety alone
   is satisfiable by doing nothing, so an operation contract catches its no-op with a positive obligation
   (`DESIGN.md` §8.3); a model contract catches it with the law that says what the type must do. A contract whose
   laws a no-op keeps is not a contract. *Kept by `ContractRun`, for every package however its `Run` is written.*
5. **Every break is caught for its reason.** Each matrix entry names the label that must fail, and only that label
   counts: failing some other law is a miss. *Kept by `ContractRun`.*
6. **Outcomes are sealed** (operation contracts): `Committed(receipt)`, `Rejected(reason)`, `Unsupported(detail)`, and
   `Stale` or `Indeterminate` where the staging really has that state. Zero effect is a rejection, never a receipt. An
   outcome that cannot be confirmed is never an ordinary rejection (`DESIGN.md` §7).
7. **Receipts say what committed** (operation contracts). Feedback consumes receipts only after the outermost commit.
8. **Labels are surface.** `"T3:committed-7"`, `"H1:..."`, `"P1:..."`: a matrix, a consumer's tests and a SPEC refer
   to them. A label is never renamed within a major.
9. **Pure.** The model mentions no platform package (every root in `eras/loaders.json`), reads no clock or
   environment, draws no unseeded chance, and holds on every Java an era runs on. Variety in fixtures comes from
   `mycelium.harness.Seeded`. *Kept by `myceliumPurity`, `myceliumPureCompile` and `myceliumEras` (`docs/eras.md`).*
10. **Its own.** A model never mentions another contract's package or `mycelium.harness`. Contracts share no
    vocabulary; a consumer composes them in its own seams (`docs/decisions/0005-what-a-contract-is-made-of.md`).
    *Kept by `myceliumRepository`; `myceliumImport` refuses a model that reaches the harness.*
11. **Copied patterns stay copied.** A transaction type (`Tx`, `*Tx`, `*Transaction`) is `FlowTransaction` with its
    name changed: copy it, never invent a transaction shape. `contracts/patterns.json` declares each pattern, its
    canonical file and any exemption with its reason. *Kept by `myceliumRepository`.*
12. **Versioned.** Additions that keep every law, label and required method stay in `@N` (a new law a consumer found,
    a `default` method), and are noted in `CHANGELOG.md`. Anything else is `@N+1`: a new package directory
    (`<slug>-2`), a new package (`v2`), and `supersedes` in its row.
13. **The SPEC stands alone.** Everything the laws mean is in the SPEC. Every file it names is in this repository.
    Where a law was learned is said in words: a consumer and a date, never a path into another repository, which
    moves on its own schedule. *Paths kept by `myceliumRepository`.*

## SPEC sections

An operation contract mirrors `contracts/flow/SPEC.md`: scope, types, outcomes, laws (safety), positive obligations,
edge semantics, traits and admission, fixtures (normative), rejection matrix, versioning and packaging, running the
harness, explicit non-claims.

A model contract mirrors `contracts/chunk-hold/SPEC.md`: the problem, the model, the laws, the rejection matrix
(its `NoOp*` entry among them), using it (what an adapter must do on the platform), and what is not covered.

Both end their harness section with the standard line: from the Mycelium root, `bash run-contracts.sh <slug>` (or
`.\run-contracts.ps1 <slug>`), or `./gradlew myceliumVerify -Pmycelium.contracts=<id>`. Expected: the reference
clean, every matrix entry `CAUGHT`, exit code 0.

## Running

```bash
bash run-contracts.sh chunk-hold                               # one package (the harness's own checks run first)
bash run-contracts.sh                                          # every package on disk
./gradlew myceliumVerify -Pmycelium.contracts=chunk.hold@1     # the build gate, on one contract
./gradlew check                                                # everything the repository promises
```

`.\run-contracts.ps1` takes the same arguments. Set `MYCELIUM_JDK` (or `JAVA_HOME`) to a JDK 17 or newer.
