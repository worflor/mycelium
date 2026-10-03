# 0007 — Downgrading the jar's own classes

| | |
|---|---|
| Date | 2026-09-29 |
| Status | Accepted (`./gradlew check` and `selfTest` green; a consumer's Java 8 servers, below) |
| Extends | `docs/decisions/0006-shipping-downgraded-jars.md` |

## Context

`myceliumPureShip` puts the pure layer into a jar downgraded, from the era gate's own bytes. That is enough while only
the pure layer is written in a newer Java than the era runs. A consumer porting to Java 8 (Minecraft 1.16.5, the first
such era after the pure layer went modern) has the same problem for everything else in the jar: its port, its loader
entrypoints and its process code use records, `List.of`, pattern matching and `Files.readString`, and there is no gate
bundle to take their bytes from. The choices were to write that code in Java 8 (a second copy of every class that touches
the platform, for one era), or to downgrade it.

Two things turned up while doing it, both in code that already existed:

- A class of the jar that extends a class outside it (a class loader, say) and is called through its own name reaches an
  inherited method, and `Linkage.outsideReferences` skipped every owner inside the jar. `ClassLoader.getDefinedPackage`
  (Java 9) called on the consumer's own loader was never checked against Java 8 and failed at boot on a real server.
- `Ship`'s exclusion of the artifact's own compiled copy of a pure class was per jar entry. A synthetic class
  (`Outer$1`, for an enum switch) exists in the artifact's compilation and not in the pure bundle's, so a stale class of
  another compilation, at the artifact's class version, stayed in the jar beside the downgraded `Outer`.

## Options considered

1. **Write the port for the oldest Java.** Rejected as above: it forks the source for one era, and the next old era
   forks it again.
2. **Downgrade in the consumer's build.** Every consumer would repeat the JvmDowngrader invocation, the classpath
   handling, the retry for its shading race and the checks, and each would prove less than the era gate does.
3. **A task in the plugin that downgrades any jar and holds the result to the era.** Decided.

Within 3:

- **Where.** On the `jar` task's output by default, and the consumer puts the result where its loader's tooling takes
  the jar (Loom's `remapJar` input, Unimined's), so the downgrader reads the classes against the classpath they were
  compiled against, before any remap. The task is in no `check` and in no jar: a build that does not use its output is
  unchanged (`docs/admission.md`).
- **Backports, and the ship's.** The classes `myceliumPureShip` put in are downgraded already and call backports of
  their own under `shadePrefix`. A shade keeps only the members its own callers use, so two shades under one name each
  lack what the other's callers need (found the first time, as a `NoSuchMethodError` for `List.of(a, b)` in a shipped
  class). This task shades under `<shadePrefix>_ports`, and the stub check treats both prefixes as backports.
- **What it holds the result to.** The class version of every class is at most the era's. No call of the artifact's own
  may be one the downgrader had no backport for; a backport that throws and that nothing in the artifact calls is not a
  failure here (a jar of a game's size reaches corners of a backport it never calls; the era gate over a small bundle
  still names them). Every `java.*` and `javax.*` class, method and field the result reaches exists on a real JVM of the
  era, by the same probe the era gate uses, over the whole jar and with the inherited-member fix above.
- **Backport metadata.** JvmDowngrader can leave runtime-visible and runtime-invisible annotations in downgraded class
  files, and generated backport classes can retain references to JvmDowngrader's metadata API. These classfile, nested
  annotation, enum, class-valued, default-value and erased bytecode references preserve an existing private definition
  first, then use the target-major API archive, then the pinned JvmDowngrader tool archive for types neither provides. The selected
  closure is copied under the artifact's private prefix and all references are remapped. Each selected class must fit
  the target class-file floor; a missing or too-new closure class fails. Both exact input hashes and per-class source
  lists are recorded in `downgrade.json`.
- **What it does not hold it to.** That the game runs it. The report says what ran (`downgrade.json`), and the evidence
  claim stays where `DESIGN.md` §11 puts it.

## Decision

`DowngradeJarTask` (`myceliumDowngradeJar`), registered for every Java project and run by no other task;
`PureShipTask.makesWay` for the nested-class rule; `Linkage.outsideReferences` follows a member named on a class of the
jar to the first class outside it that its superclass chain reaches.

## Evidence

- **Self-tests** (`SelfTest.downgrading`): the class version a jar needs is read from its classes (a multi-release copy
  is not the jar's own); a nested or synthetic class follows its outermost class, and a name that merely starts alike
  does not; a backport that throws is named when the artifact calls it and not when nothing does; a method inherited from
  a class outside the jar is traced to it, and one the jar's class declares is not.
- **A consumer.** Recon's Minecraft 1.16.5 targets (Fabric and Forge): a jar compiled for Java 17 downgraded to class
  version 52, 500 to 600 `java.*` references resolved on Temurin 8 by the probe, and both servers booted, linked and passed
  the ladder with two players on Java 8. The first boot failed on `ClassLoader.getDefinedPackage` (the inherited-member
  gap above, found by the game and then by the probe), and `Ingredient.getItems()` is a case the game found and no probe
  can: a method that exists in the development jar and not in the dedicated server's.

## Consequences

- A consumer can write its port once, in the Java its pure layer uses, and ship it to an older era with evidence of what
  was done to the bytes.
- The classpath a consumer passes is a command line: past about 28,000 characters the task refuses rather than cut it.
- JvmDowngrader's `ProcessHandle` stand-in shells out to `wmic`; the stub check does not mind (it is a working stand-in on
  some machines). A consumer that needs a process table on the oldest Java decides that itself, as Recon does.
