# 0017 — Offer, don't police: how the plugin treats a mod

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Accepted (Wolf, 2026-10-12: building with Mycelium "should feel as flexible and powerful for the mod... we don't really want mycelium to be enforcing stuff that isn't overly necessary"). |
| Evidence | A friction audit of every constraint the plugin places on a consumer, against Somnium (the one consumer of the plugin; Osmosis does not apply it). |

## Context

The plugin grew its checks one discovery at a time, each sound on its own day. Together they block more than they protect:
textual purity and isolation checks run before `compileJava`, so a mod mid-refactor cannot even compile; every mod re-runs
Mycelium's own harnesses on every build; a regex bans APIs (`SplittableRandom`, `SecureRandom`, `getenv`) whose real risk
another gate already catches, so Somnium hand-wrote a random generator; integration gates, ports, keystones and the contract
list are declared by hand and repeated per loader buildscript (one gate path written twelve times); version-dependent
metadata (`pack.mcmeta` formats, mixin Java level, the mod's declared Minecraft range, resource overlays per schema era) is
still hand-written in every mod though the era table knows it.

## Decision

1. **One enforcement model.** Every check has a level, `off`, `warn` or `fail`, set per check in one place
   (`mycelium { checks { ... } }`), with defaults in one documented table. A mod can also allow a single site with a reason
   (`allow("P3", "kernel/Rng.java", "seeded by the caller")`); allowances are recorded in the evidence manifest, so nothing is
   hidden by being allowed.
2. **Fail by default only for silent breakage:** an unresolved reference on a version the mod claims (linkage), loader
   metadata that refuses a claimed version (range), an optional integration's API reachable outside its home (the
   `NoClassDefFoundError` law, I1), the pure layer failing to compile alone, missing downgrade stubs on an era's Java, a
   contract law broken, a model reaching the harness. Everything else warns by default.
3. **Nothing blocks compilation.** Textual checks run in `check`, never as dependencies of `compileJava`. The compile-alone
   and linkage gates are the proof; the text checks are explanations.
4. **Derive, don't declare.** The contract list follows the mod's own imports. Integration gates are found from where the
   integration's home is referenced behind its guard. The range defaults to the target versions the build actually has.
   Proofs come from the versions the mod's tests actually ran on. A declaration remains possible, as an override.
5. **Declare once for every loader project.** A mod writes its `mycelium { }` once (a settings-level or convention block)
   and each loader and version project inherits it. Evidence and shipped classes go into the shipping jar by default, not
   every `Jar` task.
6. **The era table generates what is version data.** Resource-pack format, mixin compatibility level, the mod metadata's
   Minecraft range, and resource overlays per schema era are generated from `eras/` for the target version. Like bridges
   (decision 0016), this is the native half carried by Mycelium.
7. **Mycelium's own CI result is not re-run in every mod.** `myceliumVerify` in a mod checks that each imported contract's
   sources are the ones Mycelium proved (by fingerprint); it re-runs a harness only when they differ, or when asked.

## Evidence of the build

Built 2026-10-12 on branch `flexible`. The check table and the options are `docs/plugin.md` (Checks, Declaring once, Generated
from the era table); the self-tests are `FlexibleSelfTest`; the fixtures are `flexible-consumer` and `defaults-consumer`.
Decided where this text was silent: the check ids and defaults (`Checks.TABLE`); integration gates are every source outside the
home that references it, reported rather than refused, unless the mod declares a closed list; range proofs are not derived (the
plugin cannot see test runs), so a range with no keystone warns and its versions stay interpolated; the contract list is derived
only in a project with the Java plugin; the recorded proofs are `contracts/proofs.json`, written by `myceliumRecordProofs` in
this repository's `check`, and cover the Javas the era gates ran on; `artifactJars` defaults to `jar`; the root declares once
with `myceliumDefaults { }` (`dev.woflo.mycelium.defaults`); and Somnium is the proof consumer (its patch is reported with the
build). Not built: overlays inside one jar (`docs/STATUS.md`).

## Consequences

- A mod's buildscripts shrink; the evidence manifest says more (levels, allowances, derived facts).
- `docs/plugin.md` holds the check table (each check, its default level, what it prevents); `docs/architecture.md`'s
  principles say "offer, don't police" plainly.
- Somnium is the proof consumer: its three `mycelium { }` blocks collapse to one, its repeated gate paths and hand-written
  pack formats and mixin levels go, and its build is checked to still pass every gate.
