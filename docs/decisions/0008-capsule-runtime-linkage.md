# 0008 — Capsule runtime linkage

| | |
|---|---|
| Date | 2026-10-03 |
| Status | Accepted (Mycelium checks and four consumer builds pass) |
| Extends | `docs/decisions/0007-downgrading-the-jars-own-classes.md` |

## Context

Some loaders inspect every class in the mod archive with an older class-file reader, while the mod's implementation is
carried in a nested jar and loaded on a newer JVM. A version ceiling on the outer archive alone does not establish
that the nested payload can load, nor that its final remapped names and members exist in the target runtime. Consumer
builds had duplicated capsule scanners and hierarchy lookups with different coverage.

## Decision

`CapsuleSpec` and `CapsuleTask` expose an opt-in `myceliumCapsule` check. A consumer declares the artifact jar, outer
Java ceiling, nested jar entries and their Java ceilings, ordered runtime artifact roots, and (where names are remapped)
the Tiny v2 namespaces and package prefixes whose source names must not remain. The build computes SHA-256 digests for
the exact archive, mapping file and runtime roots; Gradle dependency verification remains an additional consumer
policy, not an assumption made by the task.

The shared `Linkage` inventory checks referenced classes and members, including inherited class and interface members,
constructors only on their declaring class, invocation staticness, and visibility. Runtime roots are searched in their
declared order; the first definition wins, and shadowed class definitions are recorded with their hashes. A missing
class, member, mapping input, or nested archive fails the task;
a retained source name fails when its Tiny row maps it to a different runtime name; a missing row also fails for a
class inside a declared mapped prefix.
The outer capsule shares the runtime roots' loader group. Declared nested archives are scanned as a parent-first child
loader group: an identical parent definition wins, while a conflicting definition is rejected. Package-private and
private access is checked within the defining group, including reciprocal Java nest metadata.
Multi-release class entries are rejected by the class-entry identity check because the profile does not choose a target
Java release for selecting their effective definitions. Generic `Signature` attributes are not runtime bytecode
linkage and are not checked; a consumer that reflects on generic types needs separate evidence for those types. The
scanner includes object and array types in `StackMapTable` frames because the verifier uses them when loading a method.
Source-debug local-variable tables and line tables are not runtime references and are skipped. This is a linkage check,
not a replacement for JVM verification of every possible execution path.
Array-owner member references validate their component type. The array `clone()Ljava/lang/Object;` intrinsic and
virtual methods resolved from `java/lang/Object` are checked; fields, interface references and other invocation forms
on an array owner fail.
The gate runs in `check` only when a consumer configures a capsule profile.

Example:

```kotlin
mycelium {
    capsule {
        archive.set(tasks.named<Jar>("capsuleJar").flatMap { it.archiveFile })
        outerJava.set(6)
        runtimeArtifacts.from(configurations.runtimeClasspath)
        mappings.set(layout.buildDirectory.file("mappings/mcp-to-runtime.tiny"))
        mappingNamespaces("mcp", "runtime")
        mappedPrefixes.add("net/minecraft/")
        nestedJar("recon-payload/payload.jar", 8)
    }
}
```

The runtime roots must be the exact ordered archives against which the nested code runs, including Java API archives
when they are not represented by a supplied library jar. Consumer build scripts that need Java 8 bootstrap roots can
use `RuntimeBootClasspath.resolve(JavaLauncher, 8)` to obtain the launcher's ordered archive list; it rejects missing
archives except the known OpenJDK 8 template entry `jre/lib/sunrsasign.jar`, which the VM omits when absent, and records
that omission with the optional `jre/classes` directory. `rt.jar` is mandatory. A non-empty classes directory is
rejected because the capsule inventory currently accepts archive roots only. This matches OpenJDK 8 HotSpot's
`os.cpp` boot-path template and `classLoader.cpp` filtering of absent paths; it does not exempt references to classes
that the effective archives do not provide ([`os.cpp`](https://raw.githubusercontent.com/openjdk/jdk8u/master/hotspot/src/share/vm/runtime/os.cpp),
[`classLoader.cpp`](https://raw.githubusercontent.com/openjdk/jdk8u/master/hotspot/src/share/vm/classfile/classLoader.cpp)). The utility bounds launcher output to 1 MiB,
requires it to exit within 15 seconds, and terminates the retained process on timeout, interruption or output overflow.
The task checks class references in definitions, descriptors, instructions and stack-map frames, member descriptors,
method handles, annotation/record/nest metadata, and declared visibility; it does not replace a real
server boot or the consumer's runtime ladder.

## Evidence

- `SelfTest.capsuleLinkage` covers inherited class and interface methods, fields, interface `Object` methods, array
  `clone` and `Object` methods, owner access, parent-first resolution and stack-map-only types. Missing classes and
  members, missing array components, staticness/interface mismatches, invalid array operations and constructors,
  inaccessible members, missing Tiny rows, bad archive identities, hidden class ceilings, undeclared nested jars and
  conflicting runtime definitions fail.
- Plugin self-tests pass 274 assertions. The Mycelium root check passes 17 contract/package gates, 115 rejection
  fixtures, repository checks, and Java 8, 16, 17, 21 and 25 era gates.
- The four independent Forge capsule `check` builds pass against their declared runtime profiles: 1.2.5 scans
  1,824 capsule classes (254,958 class and 87,618 member references); 1.4.7 scans 1,828 (255,380 and 87,737) against
  the pinned five-transformer view; 1.5.2 scans 1,828 (255,386 and 87,735) against the pinned FML view; 1.6.4 scans
  1,827 (255,381 and 87,735) against the patched server view. These are build-time linkage checks; the separate
  real-server ladder remains runtime behavior evidence.
