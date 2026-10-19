# Behavioral analysis research

Research reviewed on 2026-10-19. These are priors for the interpreter, with experiments that can test their usefulness.
They do not establish Mycelium's soundness or native loader behavior. The current supported scope is in
[the plugin reference](../plugin.md#inspecting-an-existing-mod).

## Exceptional control flow

The [JVMS exception procedure](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-2.html#jvms-2.10) supplies the
rule: search the exception table in order, check the protected instruction and type, and use the first matching handler.
An unhandled callee exception continues at its caller's invocation site. Individual opcodes specify their implicit
exceptions; for example, [`aastore`](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-6.html#jvms-6.aastore)
has null, bounds and array-store failure cases.

[ByteBack's exceptional-behavior paper](https://bugcounting.net/pubs/ifm23-bb-exceptions.pdf), section 3.3, makes those
edges explicit in an intermediate representation. A thrown-value state connects explicit throws, supported implicit
faults, calls and ordered handlers. Section 3.5 explains the role of specifications for modular and system-library
calls. Its deductive-verification backend is a different undertaking from Mycelium's diagnostics; its explicit outcome
model is directly useful here.

The resulting Mycelium experiment is `ExceptionBehaviorOracle`: a helper clears a service and throws; a caller catches
the exception or executes a finally block and exposes a callback. Paired controls repair the service, clear only on a
normal return, or catch a different type. Repeated variants exercise forwarding, recursion, callback specialization,
multi-catch, rethrows, finally overrides and resource closure. Each entry executes with fresh static state.

Two distinctions proved necessary during implementation:

- Exceptional effects must describe the state on that exit. A union of normal and exceptional writes contaminates
  handlers with effects from paths that cannot reach them.
- A shared catch can receive different exception types with different field effects. Retaining only a union of types
  and an intersection of bindings loses their relationship on rethrow. The flow retains separate environments for
  caught values that can be rethrown. Multi-catch origins absent from that environment do not contribute a value.

Bindings can also depend on a formal argument. A straight-line write scan lost that condition on throws and during
callback exposure. The interpreter now carries known-null and parameter-dependency facts with reference origins,
normal return values, outcome effects, reads within helpers and callback exposures. Substitution at a call can produce a clear or a residual
dependency. A later field overwrite does not change an earlier captured reference. Missing dependency facts are not
a non-null proof.

Dereferences need the binding facts captured with that origin as well. A local copy of a static read keeps its binding
and invalidation facts after another write to the field. Paired runtime controls save a nullable value before repair,
or a ready value before clearing the field. Entry-binding and saved-value guard controls separate those cases from an
unknown incoming value. Reference-snapshot joins intersect binding guarantees and union invalidations and dependencies.

Reference-dependency joins use Boolean disjunction and parameter-set union. State joins intersect must-bindings and
union may-effects within their exception environments. Tests check associativity, commutativity, idempotence and
snapshot immutability, including an exhaustive two-parameter reference domain. These laws do not establish that every
JVM instruction or external effect has been modeled soundly.

Diagnostic provenance needs a join too. A field requirement retains a stable minimum read-site witness, rather than
whichever dispatch target is visited first. A permutation control checks that combining the same targets gives the
same summary in either order. Witness choices participate in summary equality, so incidental traversal order must not
manufacture a new analysis fact.

Implicit fault conditions remain conservative. Missing exception ancestry is unknown. Platform exception headers come
from the analysis host, without initializing consumer code; this does not establish another target JVM's complete
platform hierarchy. External bodies and their arbitrary field effects remain unresolved.

## Initialization is state, not reachability

The [JVM initialization procedure](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-5.html#jvms-5.5) distinguishes
uninitialized, initializing, initialized and erroneous classes. Recursive initialization by the same thread returns
without completing a second initialization. An initializer that throws can leave partial field effects and an erroneous
class. A set of classes reached transitively does not establish that those classes completed initialization.

[GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/ClassInitialization/)
is an industrial comparison: its build-time initialization decision depends on relevant supertypes, initializers and
calls being safe. Native or unresolved virtual calls constrain that decision. Its closed-world build/runtime split
differs from Mycelium's inputs and external schedulers; its safety conclusions cannot simply be reused.

The [Checker Framework Initialization Checker](https://checkerframework.org/manual/#initialization-checker) also makes
initialization phases explicit, for object construction and field guarantees at source level. Its type discipline is a
useful comparison, while static initialization and arbitrary bytecode need their own semantics.

Next experiment: model may/must initialization states and partial stores on a two-class cycle, conditional prior
initialization, an initializer clearing another class's field, and a failed initialization followed by another active use.
Preserve the distinction between activation and completion. Independent JVM witnesses already expose failures in the
current reachability-based approximation.

## Fixed points and selective context

| Prior | Useful technique | Boundary and experiment |
|---|---|---|
| [Abstracting Abstract Machines](https://arxiv.org/abs/1007.4446) | Derive an abstract transition system from an explicit concrete machine, including state and control. | It supplies a method, not JVM semantics. Write a small reference machine for the field/call/throw/initialization fragment used by executable witnesses, then compare transfers. |
| [IFDS](https://web.stanford.edu/class/archive/cs/cs295/cs295.1086/papers/p49-reps.pdf) and [practical IFDS/IDE](https://www.bodden.de/pubs/bodden12inter-procedural.pdf) | Realizable interprocedural paths over finite distributive fact domains. | Check distributivity of a small field/outcome subdomain before considering a solver replacement. Correlated stores and context-specialized effects need not fit that subdomain. |
| [OPAL FPCF](https://arxiv.org/abs/2010.04476), [API](https://www.opal-project.de/library/api/SNAPSHOT/org/opalj/fpcf/par/index.html), [project](https://github.com/opalj/opal) | Typed lattice properties, dependencies, continuations and collaborative fixed points. | Compare exceptional summaries and initialization facts as mutually dependent property kinds. Mycelium already has a dependency worklist; replace it only for a demonstrated missing capability. |
| [WALA context policies](https://github.com/wala/WALA/wiki/Pointer-Analysis) | Selective call-string, receiver and factory contexts. | These refine points-to and call-graph analysis. Test a particular lost callback or exception distinction instead of importing a whole heap model. |
| [Tai-e design](https://arxiv.org/abs/2208.00337), [project](https://github.com/pascal-lab/Tai-e) | A maintained Java analysis framework whose analyses share and contribute facts. | Compare its client-analysis interfaces with Mycelium's dependency report. Broader pointer-analysis assumptions do not make a callback path conclusive. |

Context refinement should follow an executable counterexample. The caught-value partition above is one such refinement;
it does not add path distinctions for unrelated state. Its state space is finite for a finite input, but can still grow
large. Measure work and cancellation honestly; a resource limit is not a semantic result.

## A potential path and a feasible witness answer different questions

[Infer's industrial summary approach](https://engineering.fb.com/2015/06/11/developer-tools/open-sourcing-facebook-infer-identify-bugs-before-you-ship/)
is a useful precedent for compositional analysis. Its newer [Pulse documentation](https://github.com/facebook/infer/blob/main/infer/documentation/checkers/Pulse.md)
distinguishes manifest errors from latent, caller-dependent issues. Pulse also documents its treatment of unknown calls
and the resulting precision limits.

[Incorrectness separation logic](https://plv.mpi-sws.org/ISL/paper.pdf) studies under-approximate bug finding.
[TACAS 2025 work on object-oriented incorrectness specifications](https://songyahui.github.io/TACAS2025.pdf)
adds type constraints for casts and dispatch. These are useful references for feasible witnesses, with different goals
from a conservative analysis that must retain every relevant possibility.

[CEGAR](https://web.stanford.edu/class/cs357/cegar.pdf) provides the refinement pattern: inspect a candidate path, determine
whether abstraction introduced it, and refine the missing distinction. A general refinement loop need not terminate.
The practical Mycelium version is to preserve the candidate and an independent JVM observation as separate evidence,
then add the smallest justified semantic distinction. Failing to reproduce a witness leaves safety undetermined.

[CodeQL's Java data-flow guide](https://codeql.github.com/docs/codeql-language-guides/analyzing-data-flow-in-java/)
and [library design notes](https://github.com/github/codeql/blob/main/docs/ql-libraries/dataflow/dataflow.md) are references
for inspectable paths and matched call/return flow. A useful Mycelium trace would connect the clear, summary edge,
exceptional transfer, callback exposure and read, while identifying external or unresolved edges.

The eventual result taxonomy needs separate obligations: a feasible modeled violation, a property established within
an explicit modeled scope, or undetermined. C1 currently remains an undetermined potential-risk diagnostic. A converged
worklist, an available report and a zero-candidate run do not discharge those proof obligations.
