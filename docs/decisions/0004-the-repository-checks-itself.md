# 0004 — The repository checks itself

| | |
|---|---|
| Date | 2026-09-26 |
| Status | Accepted (`./gradlew check` runs it; every check shown to fire by a self-test) |
| Answers | worflor/mycelium#1, #2, #3, #4, #5, #8, #11, #12 (red team, 2026-09-26) |

## Context

A red team filed twelve issues against Mycelium in one pass. Most of them were one defect seen from different sides.
The repository *stated* facts in many places and *derived* almost none of them:

- the era table named a beta version that never existed, and nothing compared it with the real version list (#1);
- the era table was not a build input, and the purity check kept its own hand-written loader list (#2);
- `registry.json` carried four fields no code read, and its note said one of them was checked (#3);
- nothing compared `contracts/` with the registry, and the two runner scripts disagreed about what "everything" was
  (#4);
- the README's rejection count was wrong (80, when the matrices held 79), and so was a count in `docs/eras.md` (#5);
- the transaction pattern had been copied ten times, and nothing would notice the eleventh copy drifting (#8);
- `AGENTS.md` mapped a fraction of the plugin's classes, in breach of its own rule to keep that map current (#11);
- three gates passed when given nothing to check, and said nothing (#12).

While fixing these, one more turned up. `Fingerprint` hashed a single file as "missing", so editing
`contracts/registry.json` never invalidated a task that depended on it.

Each fix alone was a patch: correct the number, delete the field, add the row. The shape of the problem is that a
statement with no derivation drifts silently. Correcting each statement would leave that shape in place for the next
one.

## Options considered

1. **Fix each statement.** Cheap, and it restarts the drift the day it lands.
2. **A natural-language claims scanner** (#5 asked for one: fail the build on a sentence that asserts in-game
   behaviour or an unbuildable count). No scanner can tell "the harness proves the model" from "the feature works in
   game" without drowning in false positives or being gamed by rephrasing. A gate that cries wolf gets disabled, and a
   gate that can be phrased around gives false assurance.
3. **Derive every fact the repository can derive, and make statements point at derivations.** Where a statement
   cannot be derived (the truth of prose), leave it to the author and the reviewer, and say so.

## Decision

Option 3. Each piece of the repository states only what a build derives, or what a build checks.

- **`dev.woflo.mycelium.repository`**, a second plugin, applied only by Mycelium's root build. It applies the main
  plugin (the repository dogfoods it) and makes `./gradlew check` run every harness, the pure layer's laws, every era
  gate, the plugin's self-tests, and **`myceliumRepository`**. `myceliumRepository` checks:
  - **registry**: rows against package directories, both ways;
  - **packages**: every package's shape, exactly one `Run`, no private copy of the shared harness, a harness report
    for every package;
  - **models**: no model mentions another contract's package or the harness (decision 0005);
  - **patterns**: `contracts/patterns.json` declares code copied on purpose (the transaction), and every copy must
    equal its canonical file once comments, the package line and the class name are set aside;
  - **eras**: every bound is a real version (`eras/versions.json`, from Mojang's manifest) or a line (`<line>.x`),
    every era runs forwards, every real version falls in exactly one era, and every loader an era names has its
    packages in `eras/loaders.json`;
  - **documents**: a count appears only on `README.md`'s status line, which is derived; every path a living document
    or a contract document names exists in this repository; `AGENTS.md` names every plugin class; `README.md` names
    every task;
  - **the harness's own checks**: every class in `harness/test` is a main that exits 0 when it holds.
- **What only running can tell, the harness reports.** The first version of the matrix count parsed `Run.java` for
  `new ContractRun.Broken("..."`. It broke on the first package that built its matrix through a helper method, and a
  check that depends on code style is a claim about style, not about matrices. So `ContractRun` now keeps the matrix
  rules where the matrix runs: it fails a matrix without a `NoOp*` entry (decision 0005) and writes its run as JSON
  when `mycelium.harness.report` is set. `myceliumVerify` collects the reports into `results.json`, and a harness that
  exits 0 without writing one fails the build. The repository check counts from those reports.
- **The era table is data every task reads.** `eras/loaders.json` holds each loader's packages with their source.
  `myceliumPurity` forbids exactly those, which now includes `org.quiltmc`, a loader the table had named with nothing
  guarding it. `eras/` is part of every task's input fingerprint, and a single file now hashes by its bytes.
- **The registry is strict.** Schema 2 refuses unknown fields. `supersedes` is enforced when a build selects
  contracts (never both). `provenance` records who a contract was written for, as history the code does not check.
  `liveConsumers`, `firstConsumer` and `promotedFrom` are gone: who carries a contract today is in each artifact's
  evidence manifest, which is derived.
- **No gate passes vacuously.** Every evidence entry says `"checked": true`, or `"checked": false` with a `why`.
  `era.json` records the bundles each gate expected, checked and skipped, and warns when it checked none.
  `myceliumUsage` records that it had no sources rather than omitting the field. An integration declaration is
  validated where it is written and where it is read.
- **One way to run.** Both runner scripts run the harness's own checks, then every package's single `Run`. A package
  with no `Run`, or more than one, is an error rather than a guess.

Not derived, on purpose: whether a sentence is true. Documents follow `docs/admission.md` ("A document"): living
documents are timeless, history is dated, and consumers are named in words.

## Evidence

- The self-tests (`gradle-plugin/src/test/java/dev/woflo/mycelium/gradle/SelfTest.java`, 156 assertions, all
  passing) copy the repository, break the copy in one way, and require the check to name that break against an
  unbroken copy. The breaks cover: an unregistered package, a registry row with no package, a drifted transaction copy,
  a renamed-only copy (not a finding), a bound that is no version, an unknown loader, a gap in the era table, an era
  that runs backwards, a wrong status count, a count outside the status line, a missing path, a path leaving the
  repository, a contract document naming a file its package lacks, a private harness copy, a package with no harness
  report, a model reaching another contract, and an unmapped plugin class.
- `harness/test/mycelium/harness/ContractRunRules.java` proves the matrix rules on made-up matrices: a matrix without
  `NoOp*` fails however it is written, a missed `NoOp` fails, and the report records what ran.
- Run against the repository before its documents were rewritten, the check reported 40 findings: two packages whose
  matrices, built through helper methods, a source parser could not see (the reason the harness now reports its own
  matrix); stale counts; twelve paths into other repositories; fourteen plugin classes missing from `AGENTS.md`. All
  were fixed at the root, and the check is green.
- The era gates' new skip record caught a vacuous gate the issues had not: `myceliumPureCompile` required suites, so
  Mycelium's own contract models were never compiled alone, and every era gate skipped their bundle. With that fixed,
  every gate reports all its bundles checked and none skipped.

## Consequences

- A fact the repository states fails the build when it stops being true, or is a derivation, or is prose.
- The README's status line is the one place counts live. The build derives and checks them.
- Adding a plugin class, a task, a loader, a version or a package without its documentation fails `check`.
- A consumer never applies the repository plugin; its builds are unchanged, apart from the stricter registry and the
  explicit `checked` fields in its evidence.
