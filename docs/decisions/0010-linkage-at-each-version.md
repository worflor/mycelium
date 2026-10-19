# 0010 — Linkage at each version

| | |
|---|---|
| Date | 2026-10-06 |
| Status | Accepted (the self-tests pass; the `linkage-consumer` fixture builds and refuses its planted break) |
| Extends | `docs/decisions/0009-the-range-gate.md`, `docs/decisions/0008-capsule-runtime-linkage.md` |

## Context

The range gate (0009) records a version between two proofs as `interpolated`: a claim that rests on the era table's walls
being complete and on the artifact being the same across the segment. Nothing checked it. An artifact compiled against
one release can name a class, a method or a field another release in its range does not have, and the JVM answers
`NoClassDefFoundError` or `NoSuchMethodError` at the first call on that release, which no wall in a table predicts.

Whether a name exists on a release is a fact in published data: Searge's files for the MCP-named releases, Fabric's
intermediary, Mojang's official mappings, the game's own jar where it ships in its runtime names, an API's jar. Checking
a jar against that data needs no server. A consumer had written that check for Searge as a script beside its ladder; the
data and the check are the same for every mod in a family, so they belong here.

## Decision

`range { linkage { } }` in a build that declares a range adds `myceliumLinkage` (`LinkageTask`, `LinkageGate`), which holds
**the finished artifact**, the jar that ships (a capsule's own archive and its nested payload jars where a capsule is
declared), to the game's published names on **every version of its claimed range that has name data**. A build may also
supply `serverRuntimeArchive`, `serverRuntimeNamespace` and `serverRuntimeOwns` for its exact `minecraft` target. That
archive is the dedicated server's game classes in the artifact's runtime names; on that target, the gate intersects its
inventory with published names when available, or uses the inventory alone when no name row exists:

- Every class, method and field the artifact's classes reach must resolve on that version, and a reference that does not
  fails the build, naming the reference, the class that first uses it and the version. `References` reads class files: the
  constant pool, and the types signatures, annotations, handles and `throws` clauses name. A member named on one of the
  artifact's own classes that it does not declare is inherited, and is held on the first class outside the artifact its
  superclasses and interfaces reach, which is where the game would have to have it.
- A version whose references all resolve in published names is **`linked`** in the report: proven by the artifact's bytes
  and that version's data, without starting a server. When the optional server inventory is supplied, the exact target's game
  references must also resolve in that dedicated-server class hierarchy; if no published names row exists, that exact inventory
  can hold the target by itself. If hierarchy resolution leaves the supplied archive, the target is reported `unverified`; it
  is not claimed as linked. A version the range gate recorded as `proven` or `pending` keeps that status, while a version with
  neither name data nor an exact server inventory stays `interpolated` and says why. A version a capsule's own gate already
  linked against that version's final runtime view (0008) is `linked` without name data; the capsule's report must name the
  archive being read, by SHA-256.
- Linkage reads the finished artifact, so it cannot sit in the evidence manifest that is inside it. The manifest's range
  report says linkage is deferred, with where the report is; `myceliumLinkage` writes `build/mycelium/linkage/linkage.json`
  (each version's `run` and final `status`, what was checked and from which pinned inputs, the unresolved references, the
  artifact's SHA-256, and the optional server archive's SHA-256 and naming) and logs the counts. It is in `check` and opt-in:
  a build that declares no `linkage` block is unchanged.

### Which names, and where they come from

The naming a version is held to is what the era table says the artifact's loader runs under there (`names.runtime`), or
the `namespace` the build states (a plugin names no game class, only the API a server publishes: `api`). The data is a
lock, `eras/names.json`, one entry per naming and Minecraft version, each input pinned by SHA-256 at the URL it is published
at:

| Reads | For | What it says |
|---|---|---|
| `srg-flat` | Searge's `joined.srg` (MCP-named releases through 1.12.2) | classes by name; methods and fields by their numbered `func_` and `field_` names |
| `tiny-flat` | Fabric's intermediary (Tiny v2) | classes by name; methods and fields by their numbered names, with descriptors written in the runtime names |
| `jar` | the game's own jar where it runs in its own names (26.x), and an API's jar | every class and every member, with inheritance |
| `proguard-jar` | an obfuscated game jar and Mojang's mappings | the same, in Mojang's names, as Forge and NeoForge run them |

Each entry also says which class names it `owns` (the internal-name prefixes the data speaks for): a reference to any
other class is the loader's, a library's or the JDK's, and is not the game's to hold. A namespace states `reads` and `owns`
once and an entry may override them; it also says how `myceliumRefreshNames` finds a version's inputs (`resolve`).

A version with no entry has no name data, and the gate does not claim to have linked anything there. The repository check
holds every entry to the version list and the era table: the version is one the launcher names, and some loader runs under
that naming on it, since data nothing reads is a pin nobody checks.

Pins are fetched once into a cache keyed by SHA-256 (`NameCache`: Gradle's user home, `-Pmycelium.names.cache` or
`MYCELIUM_NAMES_CACHE`) and checked against the pin on every use; a corrupt cache entry is fetched again and a download that
does not match is refused, naming both hashes. A build that has run once links offline. A build run with `--offline` that
lacks an input records the version as unavailable, and leaves it where the range gate put it; online, the same lack fails the
build, since a version that could not be checked must not be reported as checked.

### What a flat reader can and cannot say

A `serverRuntimeArchive` is a build input for one target version, not a name-lock row: the consumer's loader build must
produce the dedicated-server game surface (not a merged development jar), remap it to the artifact's runtime namespace,
and state the game class prefixes it contains. The task requires that namespace to match the era table or the declared
`namespace`; where a name-lock row exists, its `owns` scope must match. The report hashes the exact archive. Only references
inside that game scope are compared; loader APIs, libraries and the JDK remain outside the check. The inventory resolves
methods and fields through its class hierarchy. It complements published mappings, whose flat rows can name a client-only
member, and it can check a target for which no published name row exists. It does not prove loader behaviour or replace a
real-server run.

The Searge and intermediary files number methods and fields and say nothing about the rest: a constructor, an enum's
`values`, a method the game inherits from a library and `toString` are neither found nor missing, and the report counts
them as not numbered. Inventories read the game's own classes and resolve a member through its hierarchy as the JVM does;
a member is absent only when the whole hierarchy it would be found in is in the inventory. A hierarchy that leaves it (a
game class extending a library's) is unknown at that edge, and the Java platform's classes are read from the JVM running the
build. Access, a static member used as an instance one and an interface called as a class are not held: the gate holds
what the game has, and what a reference does once it resolves is for a run.

### Classes an artifact chooses between

An artifact that carries several implementations and picks one at runtime by a probe of the server it is on (a plugin's
flavors) is not held to every one of them on every version: a flavor refuses a server it was not compiled for. `exclude(...)`
names the compiled class directories whose classes the archive carries and the gate does not hold; they remain the
artifact's own (so a call into them is not a reference to the game), and the report counts what was left out. Everything
else in the archive is held on every version.

### Refreshing the lock

`myceliumRefreshNames` (repository plugin only, by hand, never in a build) pins `--versions=<v,...>` of a
`--namespace=<name>` by fetching each version's inputs where the namespace's `resolve` says (`url`, `mojang` through the
launcher manifest, `maven-snapshot` for the newest timestamped jar of a snapshot directory) and adds the entry, never
changing one that is there. Without versions it reads every pinned URL again and fails naming each file that is no longer
the pinned one. The lock is written in one form, which the self-tests hold.

## What is not covered

- Releases with no entry in the lock (the report says so for each): the SRG-named releases after 1.12.2 (Forge 1.13 through
  1.20.4 run Searge numbered names whose data is MCPConfig's, not Searge's flat file), except where a consumer supplies an
  exact server-runtime archive for that target; Mojang's names on releases other than
  those pinned, intermediary where Fabric did not publish it, and API jars Spigot no longer serves.
- The loader's own API (Fabric API, Forge's and NeoForge's events), a library an artifact depends on, and Spigot's server
  internals: only the game, and a plugin's Bukkit API, are held.
- Reflection, Mixin targets and injection points written as text, and generic signatures: they are not references a link
  resolves.
- Behaviour. A reference that resolves can still do something else on a release; that is what a proof run is for.

## Consequences

- A range can be claimed wider than its proofs with a record of what was linked and what was only claimed: `proven` was
  run, `linked` resolves against that release's published names, `interpolated` is a claim.
- A change to the game between two releases that renames something the artifact uses is found by a build, before a server
  boots, and the build names the release it is found on; narrowing the range or a flavor is the fix.
- A consumer's own linkage script is a thin caller at most: the data, the check and the report are here.
- Adding a release's data is a row in the lock and a refresh, not a change to the gate.
