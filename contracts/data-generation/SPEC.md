# data.generation@1

One coherent, named view over several catalogs that name one another, published whole or not at all. Related catalogs
are validated together, and a consumer can identify the exact generation an operation read. Written for Cartomancy;
the examples below are synthetic.

| | |
|---|---|
| Contract id | `data.generation@1` (package `mycelium.data_generation.v1`) |
| Kind | Model contract, the shape of `../chunk-hold/SPEC.md` |
| Requires | `data.catalog@1`, its model only: a generation is assembled from catalog snapshots |

## 1. The problem

`data.catalog@1` publishes one catalog atomically: one file becomes one entry, a bad file is rejected without the
others, a later pack wins, and a reload is never seen half done. Data that spans catalogs does not stay coherent that
way. A recipe names ingredients, and a machine definition names recipes and processing rules. Reloading each
catalog on its own publishes, for a moment or for good, a view in which one catalog's new entries point at another's old
ones, or at nothing. And a record that must be reproducible later (a receipt, a saved operation, a log line) needs one
name for everything that decided it, the same name on every machine that holds the same data.

## 2. The model

- `Kind(id, policy)`: a catalog that takes part, offered under its id, and what a **broken** entry of it does. An entry
  is broken when its catalog rejected its file, when an ERROR finding names it, or when one of its references names
  nothing published. `REQUIRED`: the candidate is refused. `OPTIONAL`: the entry is left out and reported, and so is
  whatever referred to it, transitively.
- `Ref(kind, id)`: one entry. Both strings are non-empty and well-formed UTF-16. Refs order by kind, then id, by code
  point, which is the order of their UTF-8 bytes.
- `Diagnostic(severity, subject, path, code, message)`: one finding. `subject` is a `Ref`, or `null` for the candidate as
  a whole; `severity` is `ERROR`, `WARNING` or `INFO`, and only `ERROR` breaks anything.
- `Assembler<D, G>`, the mod's half:
  - `interpreter()`: the version of the code that gives the data its meaning;
  - `kinds()`: the kinds that take part, each id once;
  - `read(offered) -> Draft<D>`: reads every offered snapshot (one per kind, by id) into its own form `D`, a
    `Part(ref, content, references)` for each entry it read, and its findings. `content` is the entry's canonical text
    (for JSON data, its canonical JSON): every difference in it is a difference in what the entry does. An entry an
    ERROR finding names needs no part; every other offered entry has exactly one. Reading that depends on another
    catalog (a machine definition resolves its recipe references) happens here, and a judgement about one entry is a finding
    with that entry as subject;
  - `build(D, Resolved) -> Assembly<G>`: builds the value from exactly the surviving entries
    (`Resolved(number, hash, interpreter, manifest, diagnostics)`), or refuses the candidate as a whole. `Assembly` is
    `Built(value, diagnostics)` or `Refused(diagnostics)`; a `Built` that carries an ERROR finding is a refusal.
- `Generation<G>(number, hash, interpreter, manifest, value, diagnostics)`, immutable. `manifest` maps every published
  entry to its content fingerprint; `hash` is §3's hashing of the manifest and the interpreter.
- `Generations<G>`: `current()` (empty before the first publication) and `offer(snapshots) -> Outcome<G>`, where
  `Outcome` is `Published(generation)` or `Kept(current, diagnostics)`. `StandardGenerations` (or
  `DataGeneration.create(assembler)`) is the reference.

The split is deliberate: the assembler judges content, which only the mod understands; the contract judges what no
assembler should have to get right again: the offer's shape, accounting, references to a fixed point, the name, the
number and the publication. The contract never reads an entry's content; it hashes it.

### The reference, step by step

An offer runs alone (offers are serialised) and changes nothing until its last step:

1. **Shape.** One snapshot for every declared kind and none for any other, or the offer is refused (`offer:missing-kind`,
   `offer:unknown-kind`) without reading.
2. **What was offered.** Every snapshot entry is an offered `Ref`; every file the catalog rejected is a broken one
   (`catalog:rejected`, the catalog's reason).
3. **Read.** The assembler's findings are collected. An ERROR naming the candidate, or an entry of an undeclared kind,
   refuses; an ERROR naming an entry breaks that entry.
4. **Accounting.** A part for an entry no snapshot offered (`assembler:unoffered-part`), a second part for one entry
   (`assembler:duplicate-part`), or an offered entry with neither a part nor an ERROR (`assembler:unlisted-entry`)
   refuses: the assembler has lost track of the data, and its name would not cover what it built.
5. **References.** The survivors are the largest set of sound parts closed under reference. Each referrer that loses a
   target is reported (`reference:dangling`, naming the target and why it is missing) and leaves; a required one
   refuses. Resolution continues after a refusal is certain, so every finding is found (G6).
6. **Name and number.** The manifest is the survivors with their fingerprints; the hash is §3's; the number is the live
   generation's plus one (1 for the first).
7. **Build.** The assembler builds from the manifest's entries. A refusal, or an ERROR, refuses
   (`assembler:refused` when a refusal gives no reason).
8. **Publish.** The new generation, its value and every finding, replaces the live one in a single write.

A refusal at any step returns `Kept(live, every finding so far)` and leaves the live generation exactly as it was.
Whatever the assembler throws becomes `assembler:threw` and a refusal; only a `VirtualMachineError` escapes. Findings
are reported sorted (candidate first, then by subject, code, path and message), so one offer always reports alike.

## 3. The hash

Fixed. An implementation that names a generation any other way breaks G3, and the harness pins the encoding with golden
values, computed by the reference and independently by a few lines of Python over this section.

```
fingerprint(content)   = hex(SHA-256(utf8(content)))
generation(i, manifest) = hex(SHA-256(
    frame("mycelium:data.generation@1") || frame(i) || u32(n) ||
    for each (ref, fingerprint) of the manifest, by kind then id in code point order:
        frame(ref.kind) || frame(ref.id) || frame(fingerprint)))
frame(s) = u32(byte length of utf8(s)) || utf8(s)
u32      = four bytes, big-endian
hex      = lower case
utf8     = strict: an unpaired surrogate is refused, never replaced
```

Length framing makes the listing injective; the code point sort keeps every map, list, offer and pack order out of it
(Java's `String.compareTo` is UTF-16 order, which differs above U+FFFF, and is the wrong one); the interpreter is in it,
so a change of the code that gives the data its meaning is a change of name. The hash covers the published entries and
the interpreter only: not the number, not the findings, not the kinds' policies, not entries that were left out.
`Hashing.fingerprint` and `Hashing.generation` are the model's implementation; a client given a manifest can verify a
hash with them.


## 4. The laws

| Law | Statement | Labels |
|---|---|---|
| G1 | **Whole or nothing.** `current()` is always one published generation, entire: never part of a candidate (while an offer runs it is the previous one), never one generation's name on another's value. A refused candidate is `Kept`, and the live generation after it is the one before it, unchanged in every field. A publication is current when the offer returns | `G1:never-partial`, `G1:never-mixed`, `G1:refused-is-kept`, `G1:refused-keeps-current`, `G1:published-is-current` |
| G2 | **References resolve.** In a published generation every reference of every entry names a published entry. A broken required entry refuses the candidate; a broken optional entry is left out with everything that refers to it, transitively, and each is named by a finding. Seeded: the published entries are exactly those from which only present, sound entries are reachable, and the candidate is refused exactly when a required entry is not among them (or the build refuses) | `G2:no-dangling`, `G2:required-dangling-refuses`, `G2:optional-dropped-transitively`, `G2:broken-optional-left-out`, `G2:dropped-reported`, `G2:seeded-refusal`, `G2:seeded-survivors` |
| G3 | **One name.** The hash is §3's hashing of the published entries' contents and the interpreter: the same data gives the same name whatever the offer's map order, the assembler's listing order or the number of times it is offered; any change of an entry's content, id or kind, or of the interpreter, changes it; and it equals the golden values | `G3:order-independent`, `G3:content-sensitive`, `G3:interpreter-sensitive`, `G3:fixed-hashing`, `G3:golden` |
| G4 | **Numbers rise.** The first publication is number 1 and each next is one more; a refusal takes no number | `G4:monotonic`, `G4:consecutive` |
| G5 | **Held is held.** A generation someone holds never changes, whatever is offered after, and its manifest and findings cannot be changed by its holder | `G5:immutable` |
| G6 | **Every finding at once.** A refusal names every problem found in every step that ran, each with its subject (or none, for the candidate) and code; a refusal is never silent | `G6:all-findings`, `G6:named`, `G6:refusal-says-why` |
| G7 | **Accounted.** Every offered entry and every rejected file is in the published manifest or named by a finding; nothing is published that was not offered. An assembler that loses or invents an entry refuses the candidate; a broken required entry (rejected by its catalog, or named by an ERROR) refuses it; a rejected optional file is reported and left out with what named it | `G7:accounted`, `G7:nothing-invented`, `G7:unlisted-refuses`, `G7:unoffered-refuses`, `G7:required-rejection-refuses`, `G7:required-error-refuses`, `G7:optional-rejection-left-out` |
| G8 | **Contained.** Whatever the assembler throws, and whatever shape the offer has, `offer` returns: a refusal with the reason, the live generation serving | `G8:never-throws`, `G8:threw-is-kept`, `G8:offer-shape` |

### Positive obligation

- **P1.** A consistent candidate (every entry sound, every reference resolved, the build succeeds) is published, whole,
  with its warnings, and becomes current. A `Generations` that keeps the old one forever is incorrect, not cautious.
  Labels `P1:consistent-published`, `P1:every-entry-published`, `P1:warnings-carried`.

G5 is kept by the model's types: `Generation` copies what it is given into unmodifiable collections, so an
implementation cannot hand out a generation it can later change, and no matrix entry can make that mistake. The value
`G` is the assembler's, and keeping it immutable is the assembler's part (§6).

## 5. Rejection matrix

Every broken implementation in `fixtures/` is the reference with one step wrong (`Pipeline` is the reference algorithm
with a hook at each step; it is not a matrix entry).

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `PiecewiseGenerations` | publishes as each catalog arrives: the first kind alone, then the first two | `G1:never-partial` |
| `MixedGenerations` | keeps the name apart from the value and writes the new name before the build: a reader sees one generation's hash on another's data | `G1:never-mixed` |
| `FailOpenGenerations` | publishes a refused candidate as whatever survived | `G1:refused-is-kept` |
| `DisturbingGenerations` | on a refusal, rewrites the live generation to carry the refused candidate's findings | `G1:refused-keeps-current` |
| `DanglingTolerantGenerations` | never resolves references, so a candidate with a dangling reference publishes | `G2:no-dangling` |
| `ShallowDropGenerations` | drops an optional entry whose reference dangles in one pass, keeping what referred to it | `G2:optional-dropped-transitively` |
| `PartOrderHashGenerations` | hashes entries in the order the assembler listed them (file order) | `G3:order-independent` |
| `MapOrderHashGenerations` | hashes kind by kind in the offered map's iteration order | `G3:order-independent` |
| `Utf16OrderHashGenerations` | sorts with `String.compareTo` (UTF-16 order), not by code point | `G3:golden` |
| `ContentBlindHashGenerations` | fingerprints an entry by its id, not its content | `G3:content-sensitive` |
| `InterpreterBlindHashGenerations` | leaves the interpreter out of the hash | `G3:interpreter-sensitive` |
| `ConstantNumberGenerations` | numbers every generation 1 | `G4:monotonic` |
| `FirstFindingGenerations` | reports up to the first error and stops | `G6:all-findings` |
| `TrustingGenerations` | takes the assembler's parts as the generation: a forgotten entry vanishes unreported, an invented one publishes | `G7:accounted` |
| `EscapingGenerations` | lets what the assembler throws escape the offer | `G8:never-throws` |
| `NoOpGenerations` | never publishes | `P1:consistent-published` |

`NoOpGenerations` is never partial, mixed, dangling or wrongly named. It is in the matrix because this is a model
contract, and a type that does nothing must still fail one of its laws (CONTRACT-PACKAGE.md).

## 6. Using it

A mod keeps one `Generations` on the server and gives it an assembler. On a datapack reload it reloads each of its
catalogs (`data.catalog@1`: one source per file per pack, later pack wins), offers every catalog's snapshot together,
and either publishes or keeps the live generation, logging every finding either way. Everything that resolves a rule
reads `current()` once and keeps what it resolved; a record names the generation by its hash. The assembler:

- gives every entry it reads a part whose `content` is canonical (the same data, whatever its file's formatting, gives
  the same text), and lists every entry the part's data names, in any kind;
- raises `interpreter()` whenever its code changes what a record means;
- names a required entry that must exist and was not offered (a required processing rule) in an
  ERROR finding with that entry as subject, which refuses the candidate as any broken required entry does;
- judges each entry in `read` (an ERROR with the entry as subject), and the candidate as a whole in `build`; a
  whole-candidate check that could blame one optional entry belongs in `read`, against the other snapshots, so the entry
  can leave alone instead of refusing everything;
- builds a value that is immutable, from exactly the manifest's entries.


## 7. Not covered

- Where the sources come from and when a reload is triggered (the platform's), and what the platform does when there
  is no generation at all (the first offer refused).
- Sending a generation to clients: a mod sends what clients need with its hash, and a client can check the hash against
  a manifest with `Hashing`.
- Content-level lints: the assembler's own findings, which this contract carries and acts on by severity but never
  judges.
- Two offers at once: offers are serialised, and an offer made while another runs waits for it.

## 8. Running the harness

From the Mycelium root: `bash run-contracts.sh data-generation` (or `.\run-contracts.ps1 data-generation`), or
`./gradlew myceliumVerify -Pmycelium.contracts=data.generation@1`. Expected: the reference clean, every matrix entry
`CAUGHT`, exit code 0.
