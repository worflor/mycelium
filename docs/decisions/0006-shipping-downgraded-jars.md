# 0006 — Shipping downgraded jars, and the portability surface

| | |
|---|---|
| Date | 2026-09-26 |
| Status | Accepted (`./gradlew check` green; self-tests; end-to-end proof below) |
| Resolves | `docs/eras.md` "Not yet built" 1 and 2; requested by Recon's `docs/decisions/0002-every-version.md` |

## Context

`myceliumEraJava<N>` already downgrades a bundle (a contract's harness, or the artifact's pure layer) with
JvmDowngrader and proves it holds on that era's JVM: stubs, linkage (P2) and behaviour. Nothing put that
downgraded, shaded bytecode into the jar a target actually ships. A consumer porting to an era older than its
pure layer's release (Recon's first rung, 1.12.2, Java 8, pure layer written for 21) had proof the code *would*
run there and no way to ship it.

Separately, nothing measured how much of a consumer's own code touches the platform outside the layer meant to
touch it, so the cost of the next rung was invisible until the port started.

## Options considered

**Shipping.**

1. **Recompile the pure layer a second time for the jar, at the target's release.** Wrong direction: the whole
   point is that the source is written once, at a modern release, and downgraded — recompiling at the target's
   own (older) release would reject the modern syntax and APIs the pure layer is written to use.
2. **Re-run JvmDowngrader at jar time, separately from the era gate.** Doubles the downgrade-and-shade work (with
   its own retry discipline) and, worse, ships bytes that were never the ones the era gate checked — exactly the
   claims-discipline mistake DESIGN §11 warns against ("a green process exit is one input, not the acceptance
   decision").
3. **Extract the shipped classes from the era gate's own output.** The gate already produced, downgraded, shaded
   and checked (stubs, linkage, behaviour) a jar for this exact bundle and this exact era. Shipping a subset of
   those unmodified bytes means the claim "these bytes were checked" is true by construction, not by assumption.

Option 3 is the decision. `myceliumPureShip` depends on the specific `myceliumEraJava<N>` for the target's own
era (added to whatever `eras(...)` a consumer declares, if it is not already there) and copies two kinds of class
out of that gate's jar: the artifact's own declared pure packages (`pure.sources`, plus every imported contract
model — "join the pure layer" is a fact about packages, not a separate compile), and anything shaded under the
artifact's `shadePrefix` (JvmDowngrader's own backport support classes, which the downgraded bytecode calls
directly — the fixture below shipped without them the first time, and failed on Java 8 with
`NoClassDefFoundError`, not the `MissingStubError` the stub check would have named). The shared Mycelium harness
and the artifact's own test suites are also part of the same bundle (the era gate needs them to run behaviour
checks) and are never shipped (Hard rule 2): the packages are named from a source scan, and the harness's
package is never among them.

**One path, not two.** A consumer may already add its pure sources to `main` directly (`docs/decisions/0001`,
Somnium's kernels) so the jar carries them the ordinary way on eras that need no downgrade. `myceliumPureShip` is
a genuine no-op there (target Java ≥ the pure layer's release): it ships nothing, so there is nothing to
duplicate. Only when a downgrade is actually needed does every `Jar` task exclude, from whatever `main` already
compiled, any `.class` file in a package the ship task also carries — checked by the file's own source path, so
it can never exclude the copies the ship task just added. A consumer targeting an old era could not have
compiled modern-syntax pure sources into `main` at that era's release anyway, so this exclusion is a safety net,
not the load-bearing path.

**No new extension surface for shipping.** Everything it needs (`minecraft`, `pure.release`, `shadePrefix`,
`downgraderVersion`) is already declared. Like `myceliumEvidence`, it runs unconditionally wherever it applies
(`admission.md`'s "cannot break a build that did not ask for it" is about *new checks*; shipping fills a gap the
build already implied it would fill, and the standard failure modes — JvmDowngrader failing, no era gate output —
already exist in `myceliumEraJava<N>` today).

**The portability surface.**

1. **Count references per file.** Files are not the unit anyone reasons about when planning a rung; packages are
   (`docs/eras.md`'s platform map is per-version, not per-file).
2. **A whole new "ports" abstraction (validated packages, required annotations, …).** Rejected by
   `docs/admission.md`'s "keep it minimal": a port that does not exist yet, or is misspelled, is not a build
   error — it just is not a port, and its references count as surface. Nothing here can "break a build that did
   not ask for it."
3. **A flat list, `mycelium.ports`, and a report per package.** Decided. `myceliumSurface` scans the artifact's
   own main sources (never the imported models — those are proven pure already) for references to any platform
   root (`eras/loaders.json`, the game included), the same textual match `PurityTask` and `IsolationTask` already
   use (a dotted mention before a capitalized name, `_`, `$` or `*`, comments and strings ignored), grouped by the
   file's own package. A package in `mycelium.ports` is expected to touch the platform; every other package's
   references are the surface — the number the next rung has to eat, and the one that should only fall.
   `failOnGrowth` against a checked-in baseline is opt-in and off by default; misconfiguring it (asking to fail
   growth with no baseline declared) fails loudly, at declaration.

## Decision

Both land as described above: `PureShipTask` (`myceliumPureShip`) and `SurfaceTask` (`myceliumSurface`), wired
into `check`. `MyceliumExtension` gains `ports(...)` / `getPorts()`, `getSurfaceFailOnGrowth()` and
`getSurfaceBaseline()` — no new nested block; a flat list is minimal and justified per `docs/admission.md`.

## Evidence

- **End to end, Java 8.** `gradle-plugin/src/test/fixtures/ship-consumer` (a separate build, not part of this
  repository's own `check`): a pure layer written for release 21 (`kernel/Greeting.java`, one `List.of` call, a
  Java 9+ API), `minecraft = "1.12.2"` (era Java 8). `./gradlew jar` ran `myceliumEraJava8` (downgraded to class
  version 52, 106 outside references resolved) then `myceliumPureShip` (5 classes shipped: 1 of the artifact's
  own, 4 of JvmDowngrader's shaded support classes). The jar held exactly `fixture/ship/kernel/Greeting.class`
  plus `META-INF/mycelium/evidence.json` — no harness, no test suite, no duplicate. Extracted and run directly
  with a real Java 8 JDK (Temurin 8u504, provisioned the same way the era gate provisions it):
  ```
  Mycelium ship-consumer fixture: OK (hello,java8)
  ```
  Retargeting the same fixture at `minecraft = "1.21.1"` (era Java 21 ≥ release 21) made `myceliumPureShip` a
  no-op ("pure layer needs no downgrade for Java 21 (release 21)") and the jar carried no classes at all beyond
  the evidence manifest — the no-downgrade path adds nothing, so there is nothing to duplicate.
- **Self-tests** (`gradle-plugin/src/test/.../SelfTest.java`, `shipping()` and `surface()`): a class in a
  declared pure package ships; a package that merely starts with a pure package's name (or the shade prefix's
  name) does not, proving the directory-boundary check; the shared harness never ships even though it shares the
  bundle; JvmDowngrader's own shaded support classes ship; the shipped-bytes claim points at the era gate's own
  result for that exact bundle name, says so honestly when no era ran, and names a bundle the era gate never
  reported on; the surface's reference count is textual (the same mention twice counts twice), a same-prefix
  package is not a reference, and comments/strings are not references.
- **`./gradlew check`** (this repository, dogfooding both plugins) and `./gradlew -p gradle-plugin selfTest`:
  green.

## Consequences

- A rung whose era is older than the pure layer's release now has a real jar to test with a real server, not
  only a gate that says it would work — Recon's ladder (`0002-every-version.md` §2) can start.
- The claim shipped bytes carry is exactly "these are the unmodified bytes `myceliumEraJava<N>` downgraded,
  shaded and checked for this bundle" (`ship.json`'s `eraGate` block) — never a fresh, independent check of the
  shipped subset alone. That is honest, and narrower than "this jar works on a real server": that evidence is
  still the consumer's own gametest, per target (DESIGN §11).
- `myceliumPureShip` reads its era gate's output by hand (`@Internal`, like `EvidenceTask`'s own cross-task
  reads) rather than as a declared Gradle input; ordering comes from `dependsOn`, not from Gradle noticing the
  era report changed on its own. In practice the two are never out of step: the era task and the ship task share
  the same upstream inputs (the pure bundle, the shade prefix, the downgrader version).
- The portability surface is a diagnostic, not a gate, unless a consumer opts into `failOnGrowth`. It is a
  textual count (three uses on one line are three), not a distinct-type count — cheap and consistent with every
  other source-scanning check in the plugin, and stated as such rather than oversold.
- Loader-blind throughout: `myceliumPureShip` and `myceliumSurface` work on the standard `jar` task and Java
  sources; a consumer wires a loader's remap step (Loom's `remapJar`, ForgeGradle/jarJar) to consume the `jar`
  task's output exactly as it already does today — nothing here changes what a loader plugin sees or needs to
  know.
