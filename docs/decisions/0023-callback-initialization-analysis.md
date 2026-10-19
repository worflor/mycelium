# 0023 - Callback initialization diagnostics

| | |
|---|---|
| Date | 2026-10-18 |
| Status | Accepted as an opt-in experimental linter |
| Extends | [0017](0017-offer-dont-police.md), [0010](0010-linkage-at-each-version.md) |

## Context

While porting WorldPrompt, a callback could reach a shared permission query before a later callback bound it. The API
names were valid, so linkage could not explain the initialization risk. A loader event recipe would describe that one
integration but add a second source of compatibility knowledge to maintain.

## Options

- Maintain event names and ordering rules for each loader. This duplicates native behavior and becomes stale when the
  loader changes.
- Infer a complete loader lifecycle from names or package roots. Those inputs do not establish event ordering.
- Analyze callback dependencies in compiled consumer code, report the read and exposure sites, and keep scheduling
  uncertainty visible. This gives useful feedback without inventing a native lifecycle proof.

## Decision

Use abstract interpretation of compiled bytecode to analyze static reference dependencies exposed through callbacks.
The opt-in experimental linter, `myceliumBehavior`, is the diagnostic interface to that analysis layer.
ASM's analysis module supplies operand provenance; the compiler propagates bindings through branches and direct-call summaries and
follows callback factories, forwarded parameters, static callback fields and locally constructed interface implementations,
including inherited handlers. Guard facts belong to the checked value; unrelated class initializers cannot bind a root
class's fields. Known direct and returned nulls invalidate bindings. The selected target's compiled classes are
the input, so loader-specific wiring is analyzed as code rather than encoded again as rules. The additional ASM module
uses the same version as the existing ASM dependencies.

C1 warns by default when explicitly run. It identifies the callback registration and state read, with line metadata
when available. It is outside `check` unless the consumer opts in. The report identifies its input, inferred public
roots, callback-exposing class initializers, supported scope and analysis errors. It cannot be read as proof that a
callback is reachable on a particular loader before another event. A confirmed external ordering guarantee can justify an allowance.

## Dependency worklist refinement (2026-10-18)

A fixed number of whole-program passes can stop before a long call chain reaches its fixed point. The solver now
records which summaries read each method summary, returned callback set, callback field and class initializer.
Changed facts enqueue their dependents. Operand provenance still comes from ASM; the interprocedural relationships
come from those actual reads. The report exposes sorted typed dependency edges and the evaluation count. The solver runs until its worklist is
empty or the caller cancels. Progress telemetry exposes repeated work; arbitrary instruction and iteration caps do not
define the supported program size.

A parameter invoked as a receiver carries the invoked method signature through forwarding summaries. At a concrete
call site, only matching callback methods contribute requirements. This distinguishes invocation from merely calling
`Object.toString` on the callback. Scheduler-local binding order is retained. Known callback arguments now specialize
consumer-owned scheduler summaries, including synchronous writes and clears. Specializations are dependency-worklist
nodes, including recursive calls; normal-return reachability separates a non-returning path from one that establishes
a binding. Initialization sets use immutable snapshots of bitsets, preserving set operations while reducing large-input
copy costs. Initialization reachability is closed separately over direct activations and synchronous call edges. Its
union growth does not invalidate unchanged bytecode effects. Public methods, including package-private interface implementations, remain roots even when they also have internal callers. Mixed known
null and non-null factory returns invalidate the receiving binding.

Independent regressions compile anonymous callbacks and lambdas across compiler releases. Unsafe and safe witnesses
execute on the JVM. A reverse-ordered dependency chain and a permutation of its class-file method order check
convergence and invariance. The focused `behaviorSelfTest` task supports a Java 17 or newer compiler toolchain, with
Java 7 bytecode fixtures compiled on Java 17. These tests establish bytecode-analysis behavior, not native Minecraft
support on those eras.

## Outcome summaries and rethrows (2026-10-19)

Field effects now distinguish normal completion from typed exceptional exits. A callee's exceptional effects apply at
the caller's invocation site before exception-table routing. Explicit throws retain exact allocation types when known;
other references carry type bounds. Conservative implicit faults and unresolved calls contribute exceptional paths.
Missing ancestry is unknown; platform hierarchy headers come from the analysis host and do not establish every target
JVM's hierarchy or fault conditions.

An initial typed-outcome implementation still merged different exception kinds in a shared catch. The executable
`correlatedSafe` control then inherited a clear from the other kind's path. Flow nodes now retain separate environments
for caught values used by a later throw. Multi-catch source origins absent from that environment contribute no thrown
value. The distinction comes from the counterexample; other catch state can still join normally. There is no fixed
partition or iteration cap.

A further witness stored a nullable argument before throwing. The previous straight-line parameter-write scan applied
only after normal calls and could not handle exceptional exits or in-callee exposure. Bindings now carry formal
dependencies through normal returns, exceptional effects, reads within helpers and callback exposure. Reference-origin snapshots preserve
earlier reads across later overwrites, and return facts allow argument forwarding. Call-site substitution uses the
same mechanism for each outcome. Join-law and immutability controls check this domain independently of fixture output.

`ExceptionBehaviorOracle` exercises normal-only clears, exceptional repair, typed handler order, shared and multi-catch
rethrows, callbacks, recursion, implicit arithmetic faults, finally overrides and resource closure. Each entry executes
with a fresh class loader and checks whether the callback ran. These experiments validate the supported fragment;
external effects, fault feasibility and initialization completion remain separate open problems. The
[research notes](../reference/behavioral-analysis-research.md) explain the connection to explicit exceptional control flow
in ByteBack and to selective context refinement.

## Evidence

The independent fixtures compile as Java 8 bytecode. They exercise early exposure, prior binding, delayed writers,
partial and complete branches, null guards, callback factories, helper methods, anonymous implementations, resets and
exception paths. Adversarial fixtures cover unrelated class initializers, inherited handlers, forwarded parameters,
base-typed method references, merged and stale guard values, resets within callbacks, known null returns and safe lazy
class initialization. The eager scheduler fixture also executes the unsafe and safe programs, rather than relying solely
on the analyzer's own output. Missing debug metadata preserves method-level findings. Multi-release fixtures select
different Java 8 and 17 implementations instead of merging them.

The full repository check passed with 979 plugin assertions and contract era gates on Java 8, 16, 17, 21 and 25. The
Gradle task analyzed both WorldPrompt target modules without candidates or errors; removing NeoForge's early permission
binding from a separate bytecode mutation produced the expected registration/read candidate. Static scans of five
official Minecraft 1.21.1 mods completed without analysis errors after the initialization and multi-release fixes.
These scans are analysis evidence, not runtime compatibility claims.

## Consequences

The compiler gains one scoped behavioral diagnostic, not a general proof of cross-loader behavior. Reflection,
captured instance aliases, a closed set of runtime dispatch targets and native scheduler semantics remain outside its
model. It tracks forwarded callbacks and matching synchronous receiver invocations with known callback effects.
Unknown targets and effects remain uncertain. Assignment is
tracked separately from a full nullness proof. Existing range, linkage, bridge conformance and production runtime
validation remain necessary. Future diagnostics should derive from program facts or declared contracts with rejection
fixtures, rather than accumulate loader-name exceptions.
