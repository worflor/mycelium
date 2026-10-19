# 0024 - Evidence follows producers

| | |
|---|---|
| Date | 2026-10-19 |
| Status | Accepted |
| Extends | [0004](0004-the-repository-checks-itself.md), [0017](0017-offer-dont-police.md) |

## Context

The embedded manifest read optional reports if their files existed. Those files were not all declared task inputs, and
an earlier build could leave them behind. A consumer that removed a contract reference retained the old usage claim in
its jar. Disabling verification after a successful build could retain a checked harness result. Neither file existence
nor task ordering establishes that a report describes the current inputs.

Optional source checks must remain separate from compilation and jar assembly. Including their results in the jar
would require running them before assembly, while checks of the finished jar necessarily run afterward.

## Options

- Run every check before assembling a jar. This changes consumer policy and cannot include finished-archive checks.
- Read whichever reports exist and stamp them with the current build. This preserves the stale-evidence defect.
- Separate artifact evidence from verification evidence, with explicit producer dependencies and input tracking.

## Decision

The embedded schema 4 manifest consumes only artifact-prerequisite reports. Each report file and producer outcome is
a declared input. A build-scoped service observes Gradle task completion through `BuildEventsListenerRegistry`.
Successful results include outputs Gradle validated as up-to-date or restored from cache. Skipped and failed producers
are unavailable, even when their output files remain. With task exclusions, an unobserved completion is unavailable;
the collector does not wait for an event that an excluded task will never emit.
Producer identities use the public [`Project.getBuildTreePath()`](https://docs.gradle.org/current/javadoc/org/gradle/api/Project.html#getBuildTreePath()) API, including the consumer's owning build. This API
requires Gradle 8.3 or newer; build-local task paths do not identify completion events in an included build.

A separate `myceliumVerificationEvidence` task collects current optional and post-artifact reports. It finalizes a Java
project's `check`, so a selected shipping task may itself depend on `check` without creating a dependency cycle. It can
also run directly. Reports retain their original result and scope; availability does not mean that a rule passed.

The sidecar records raw archive and report hashes. Linkage and capsule reports must match their own archive subject's
hash. Checked archives and shipping artifacts have distinct roles. Binding a shipping build requires successful
completion of every selected shipping producer and an embedded manifest matching the current artifact evidence.
Inspecting an old archive cannot satisfy that condition. Excluding the embedded producer omits its old manifest from
a newly assembled jar.

`artifactJars("*")` continues to inject the manifest into all Jar tasks, but verification selects the primary `jar`.
Finite selections verify those tasks. Optional checks do not become prerequisites of compilation or ordinary assembly.

## Evidence

`EvidenceSelfTest` uses a separate consumer build with one worker and configuration caching. It changes source usage,
changes pure code, disables checks, excludes producers, and selects a shipping jar that depends on `check`. Separate
subject tests reject mismatched archive hashes and preserve the report's own unchecked result. Gradle completion tests
exercise asynchronous delivery, skipped tasks and repeated observation of a producer.

## Consequences

Consumers can inspect both the claims carried by a jar and the verification reports associated with finished bytes.
Older report files no longer silently become current evidence. Schema readers must recognize the new artifact phase
and the separate verification sidecar. The collector does not broaden any check's scope or establish native behavior.
Later remapping or packaging steps need their own archive subject when their exact bytes matter.
