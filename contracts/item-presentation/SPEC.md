# item.presentation@1 - the Sigil


## 1. The problem

An item that looks like its state is written twice today, once per model era:

- from 1.21.4: an item definition (`assets/<ns>/items/<id>.json`) that selects a model, typically a `select` on the
  `custom_model_data` strings;
- 1.14 to 1.21.3: one item model with an `overrides` list on the float `custom_model_data` predicate. The stack carries a
  number, an override matches when the stack's number is at least its own, the last match wins, so the list must be
  ascending, and the number is computed in Java with arithmetic that has to agree with that table.

They drift silently: a wrong float draws the wrong sprite and nothing fails. The Sigil states the fact once, as a
**program** over the fields of `item.component@1` components, compiles it at build time into each era's artefacts, and
evaluates the same program at run time, so the artefacts, the stack's selector and the program cannot disagree.

## 2. The model

| Type | Meaning |
|---|---|
| `Program` | `(item, base, inputs, cases)`: one item's presentation. Inputs are held sorted by name; the first case whose guard holds wins; no match draws `base` |
| `Input` | A named reading of one component field with a finite `Domain` and a default (the domain's first value unless stated): used when the component or field is absent |
| `Domain` | `Flag` (a bool field), `Present` (a unit component is on the stack), `IntRange(min, max)` (bounds within 2^31), `Choice` (listed texts of `[a-z0-9_]`) |
| `Case`, `Condition` | An ordered guard to a model. A guard is a conjunction of per-input `Equals`, `AnyOf` or `Within` (a range, either bound open). The model is a reference or a template: `example:item/band_mended_{seams}_cracked_{cracks}` |
| `Template` | The `{input}` placeholders of a model reference, and their expansion (a flag as `true`/`false`, a number in decimal digits, an enum text as is) |
| `Evaluator` | The pure evaluation: `evaluate(program, tuple)` is the model a tuple draws; `read(program, view)` reads a stack; `tuples`/`product` enumerate and size the domains. The oracle every artefact is held to |
| `ComponentView` | What a stack's components look like: `has(component)`, `field(component, path)`. `CarrierView` implements it over an `item.component@1` `CarrierSnapshot` and `ComponentRegistry` |
| `ComponentCatalog` | The components a program may read: network-visible or not, unit or record, each field's kind. A file the build reads; `disagreements(registry)` holds it to the registry's `ComponentDefinition`s |
| `Lock` | The consumer's committed codes: per item, each selection (its model reference) to a legacy code, append-only |
| `Realization`, `Target` | `item-definition`, `model-overrides` or `none`, as `eras/minecraft.json` gives a version; `Target` carries the version only to name it in a refusal |
| `SigilCompiler`, `ReferenceSigil` | `compile(program, lock, catalog, target)` to a `Compilation`: `Compiled(plan, artefacts)` or `Rejected(diagnostics)`. `maxTuples()` is its cap |
| `Plan`, `ReferencePlan` | What an adapter evaluates at run time: `select(view)` (total) and `selectorOf(selection)`; `toTree` is the plan resource, `ReferencePlan.parse` reads it back |
| `Selection`, `Selector` | The model a stack draws (`model`, `key`, `code`, `inDomain`; the base has neither key nor code), and what the adapter writes onto the stack: `None`, `Key(string)` or `Code(number)` |
| `Diagnostic` | A refusal a tool can switch on: a `Kind`, where, words, and for `LOCK_MISSING` the exact lines to add |
| `Json`, `Layout`, `SigilTool` | The canonical JSON reader and writer; where artefacts go; the whole compile over text, which the Gradle tasks call |

A **selection** is a distinct model a program can draw other than `base`; its key is the model reference. Partial
evaluation enumerates the tuples of the inputs some case reads (an input no case reads is dropped from the plan, and costs
nothing), evaluates the cases for each, and collapses tuples that reach one model into one selection.

### The program file


A guard value is a bare value (equals), an array (in) or `{"min": n, "max": n}` (range). A `present` input has no
`field`. `default` is optional on an input.

### The artefacts

- `item-definition`: `assets/<ns>/items/<id>.json`, a `minecraft:select` on `minecraft:custom_model_data` at string index 0,
  one case per selection in key order (`when` is the key), the base as `fallback`. With no selection it is the plain base model.
- `model-overrides`: `assets/<ns>/models/item/<id>.json`, the base as `parent` and an `overrides` list, one row per code
  in the lock (ascending), `predicate.custom_model_data` the code. A code the lock holds that no case produces any more keeps its
  row and draws the base, so a stack still carrying it is not drawn as its neighbour. The file is the item's own model, so a
  program may not draw `ns:item/<id>` itself (`MODEL_CLASH`): the base is another model.
- the plan, `META-INF/mycelium/presentation/<ns>/<id>.plan.json` in both eras: the program's read inputs and cases and the
  selections with their codes.

The stack's selector is the key (written as custom model data string 0) in the first era and the code (the custom model data number)
in the second; the base model writes nothing.

## 3. Laws

| Law | Statement | Labels |
|---|---|---|
| S1 Totality | Every tuple of the input domains evaluates to exactly one selection: a case's, or the base. The plan agrees with the evaluator on every tuple; the first matching case wins; a stack outside the domains draws the base (`inDomain` false), and an absent component reads as its default | `S1:totality-agrees-with-evaluator`, `S1:one-selection-per-tuple`, `S1:first-match-wins`, `S1:base-when-no-case`, `S1:unread-input-dropped`, `S1:out-of-domain-draws-base`, `S1:absent-component-reads-default` |
| S2 Agreement | For every tuple, the emitted item definition and the emitted item model, each interpreted by its own era's rules (string equality; ascending `>=` with the last match winning) and given the selector the plan writes for the tuple, draw the model the evaluator chose. The harness simulates the emitted bytes (`harness/Simulators.java`), not the emitter. The plan resource read back, a stack read through `ComponentView` and through a `CarrierSnapshot` agree with the plan; a retired code draws the base | `S2:modern-draws-evaluated-model`, `S2:legacy-draws-evaluated-model`, `S2:plan-resource-agrees`, `S2:adapter-reading-agrees`, `S2:carrier-view-agrees`, `S2:retired-code-draws-base` |
| S3 Stable codes | Adding a case or an input value never changes the code of an existing selection. The lock is append-only; a new selection takes the next code above every code the item ever had; a selection the lock lacks is a `LOCK_MISSING` refusal listing the exact lines to add, and those lines complete the lock | `S3:existing-codes-kept`, `S3:new-selections-append`, `S3:missing-lock-lists-lines`, `S3:lines-complete-the-lock` |
| S4 Override order | Legacy overrides are written strictly ascending by code, and every code is a positive whole number | `S4:overrides-strictly-ascending` |
| S5 Projection | Every declared input reads a network-visible component of the catalog, with the field and kind it says; anything else is refused at compile time. The catalog is held to the registry's definitions | `S5:server-private-rejected`, `S5:unknown-component-rejected`, `S5:type-mismatch-rejected`, `S5:catalog-agrees-with-registry` |
| S6 Determinism | The same program and lock produce byte-identical artefacts: keys sorted, fixed formatting, line feeds, printable ASCII only, whatever the platform, locale or the order the program lists its inputs and guards in; cases in key order | `S6:repeat-identical`, `S6:canonical-json`, `S6:ascii-and-line-feeds`, `S6:declaration-order-independent`, `S6:locale-independent`, `S6:cases-in-key-order` |
| S7 Bounded | A product of the read inputs' domain sizes above the cap (4096 tuples unless a compiler states another) is refused with a `TOO_LARGE` diagnostic **before** enumeration: the product saturates instead of overflowing, so a four-quintillion-tuple program is refused as fast as a large one | `S7:product-over-cap-rejected-before-enumeration`, `S7:product-at-cap-accepted` |

Beside the laws, the harness holds well-formedness (`P1:*`, a program that is malformed, unsound or checked by no one is refused, by kind;
`P1:compiler-rechecks-program`), the lock's file rules (`L1:*`), the targets (`R1:no-realization-rejected`: a version with no mechanism
is refused naming it; `R2:model-clash-rejected`) and the build tool over text (`T1:*`). Property-style checks compile 40 seeded programs of
random shape and hold each to S1, S2, S4 and S6.

## 4. Rejection matrix

| Fixture | What it gets wrong | Caught by |
|---|---|---|
| `EnumerationOrderCodes` | numbers selections 1, 2, 3 in enumeration order, ignoring the lock | S3 (`S3:existing-codes-kept`) |
| `DescendingOverrides` | writes the legacy overrides highest code first | S4 (`S4:overrides-strictly-ascending`), and S2 |
| `OffByOneLegacy` | tells the adapter to write code + 1 onto the stack | S2 (`S2:legacy-draws-evaluated-model`) |
| `LeakyProjection` | accepts an input over a server-private component | S5 (`S5:server-private-rejected`) |
| `HashOrderEmitter` | writes JSON keys in hash order | S6 (`S6:canonical-json`) |
| `NoOpCompiler` | draws the base model for every state | S1 (`S1:totality-agrees-with-evaluator`), and S2 |

`NoOpCompiler` emits well-formed artefacts and a plan that never errs; it is in the matrix because a compiler that ignores the program
must fail.

## 5. Using it

**Writing a program.** Keep programs in the consumer's presentation directory (by default src/main/presentation of the project): one JSON
file per item, a `components.json` catalog and a committed `presentation.lock.json`. The catalog states, for every component a program
reads, `networkSynced` (true or false), `unit` for a unit component, and `fields` (a dotted path to `bool`, `int` or `string`). Keep it honest
with a test of your own that builds the registry the game uses and asserts `catalog.disagreements(registry)` is empty.

**Building.** In the consumer's build script:

```kotlin
plugins { id("dev.woflo.mycelium") }

mycelium {
    root = rootProject.file("../mycelium")
    minecraft = "1.21.4"                       // the era table chooses item-definition, model-overrides or none
    contracts("item.presentation@1")           // imports item.component@1 with it, under the mod's namespace
}
```

`./gradlew myceliumPresentation` (also run by `processResources`, so by every build) compiles the programs for the target into
`build/generated/resources/mycelium/presentation`, which joins the main resources, and fails with the diagnostic on any breach. When the
lock lacks a selection the failure lists the lines to add; `./gradlew myceliumPresentationLock` appends them to the lock in the source
tree. A reviewer sees the added lines in a diff; the build never edits a source.

**At run time.** The platform adapter reads the plan resource once (`ReferencePlan.parse`), builds a `ComponentView` over the stack
(`new CarrierView(snapshot, registry, catalog)` on `item.component@1`, or its own), and writes the selector:

```java
Selection s = plan.select(view);
switch (plan.selectorOf(s)) {
    case Selector.Key k -> /* custom_model_data strings[0] = k.key() */;
    case Selector.Code c -> /* custom_model_data number    = c.code() */;
    case Selector.None n -> /* no custom model data */;
}
```

**Example, a band.** The program above has seams 0 to 3, cracks 0 to 2 and broken. It spans 24 tuples and collapses to 12 selections and the
base. Adding a case that draws a new model appends one line to the lock and renumbers nothing. Removing a case leaves its lock lines, and the code
then draws the base.

## 6. Running the harness

From the Mycelium root: `bash run-contracts.sh item-presentation` (or `.\run-contracts.ps1 item-presentation`), or
`./gradlew myceliumVerify -Pmycelium.contracts=item.presentation@1`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 7. Explicit non-claims

- No Minecraft or mod loader code was executed to produce this package. The era simulators encode how the item definition `select` and the
  model `overrides` behave as the decision describes them; whether a game draws the model they predict is the consumer's gametest and client lane.
- The catalog is held to the registry by `ComponentCatalog.disagreements`, which a consumer must call; the build reads only the file.
- Composition (layered models), a renderer cache keyed on the fields a model reads, and music discs (`item_model` per disc) are not covered.
- A stack whose state is outside a program's domains draws the base model and says so (`inDomain`); the contract does not decide what a mod does about it.
