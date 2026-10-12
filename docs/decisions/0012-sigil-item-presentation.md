# 0012 — The Sigil: item presentation compiled from component state


## Context

An item that looks like its state is written twice today, by hand, once per model era:

- 1.21.4 and later: an item definition (`assets/<ns>/items/<id>.json`) that selects a model, typically
  `select` on `minecraft:custom_model_data` strings.
- 1.14 to 1.21.3: one model with an `overrides` list on the float `custom_model_data` predicate, where the item carries a
  number, the overrides must be in ascending order (an override matches when the item's value is at least its own, and the
  last match wins), and the number is computed in Java with arithmetic that has to agree with that table.


## Decision

`item.presentation@1`: a presentation **program** declared once, over the fields of Rosetta components, compiled at build
time into each era's native artefacts, and evaluated at run time by the same pure model, so the stack's selector, the
generated assets and the program cannot disagree.

### The program (data, `src/main/presentation/<item>.json` in the consumer)


### Compilation (pure, in the contract; driven by a Gradle task)

1. **Partial evaluation.** Enumerate the input domains' product (bounded: a product over the cap is a compile error, not a
   memory error), evaluate the cases, and collapse tuples that reach the same model into one **selection** (its key is the
   model reference). Inputs no case reads are dropped from the plan.
2. **Stable codes.** Each selection gets a legacy float code from the consumer's committed **lock file**
   (`src/main/presentation/presentation.lock.json`): existing selections keep their codes forever, new ones append. A lock
   that is missing a selection fails the build with the exact lines to add (`myceliumPresentationLock` writes them); the
   build never edits sources by itself.
3. **Emission, per the target era's realization** (data in `eras/`, not code: which mechanism a version has):
   - `item-definition`: `items/<id>.json`, a `select` on `custom_model_data` strings index 0, cases in key order, fallback
     `base`.
   - `model-overrides`: the base model with `overrides` sorted ascending by code.
   - `none` (before 1.14): compilation fails for that target, naming the version.
   Output bytes are deterministic (sorted keys, fixed formatting).
4. **Run time.** The consumer's platform adapter asks the pure evaluator for a stack's selection from its `CarrierSnapshot`
   and writes the era's selector (the string key, or the locked float). The plan the adapter reads is generated with the
   assets, from the same compilation.

### The client projection

An input may only read a component whose definition is network-visible (`ComponentDefinition.withNetworkSynced`). A
program that reads a server-private field (a server_state deadline, say) is a compile error. Presentation never widens what
reaches the client by accident.

## Laws

| Law | Statement |
|---|---|
| S1 | **Totality.** Every tuple of the input domains evaluates to exactly one selection (a case or `base`). |
| S2 | **Agreement.** For every tuple, the emitted modern select and the emitted legacy overrides, each interpreted by its own era's rules (string equality; ascending `>=` with the last match winning), draw the model the evaluator chose. Checked by simulating both emitted artefacts, not by trusting the emitter. |
| S3 | **Stable codes.** Adding a case or an input value never changes the code of an existing selection; the lock is append-only. |
| S4 | **Override order.** Legacy overrides are emitted strictly ascending by code. |
| S5 | **Projection.** Every input reads a network-visible component; anything else is rejected at compile time. |
| S6 | **Determinism.** The same program and lock produce byte-identical artefacts on every platform. |
| S7 | **Bounded.** A domain product above the cap is rejected with a diagnostic, before enumeration. |

## Rejection matrix (broken compilers the harness must catch)

| Fixture | Wrong | Caught by |
|---|---|---|
| `EnumerationOrderCodes` | numbers selections by enumeration order, ignoring the lock | S3 |
| `DescendingOverrides` | emits overrides in descending order | S2, S4 |
| `OffByOneLegacy` | writes code + 1 into the stack's selector | S2 |
| `LeakyProjection` | accepts an input over a server-private component | S5 |
| `HashOrderEmitter` | emits JSON keys in hash order | S6 |
| `NoOpCompiler` | emits only the base model | S1/S2 |

## The era data

A `presentation` realization per version range in `eras/minecraft.json` (beside the existing 1.21.4 wall): `item-definition`
from 1.21.4, `model-overrides` from 1.14 to 1.21.3, `none` before. The compiler reads the row for the target's version;
it holds no version numbers of its own.

## First proof (Somnium)


## Later

Music discs (a different mechanism, `item_model`, per disc); composition (`composite` models on the modern side, a generated
model adapter on the legacy side) when a consumer needs layered presentation; dependency tracking so a renderer cache keys
only on the fields a model reads.

## Decided while building it

The decision above stands. Where it was silent or wrong, this is what was decided and built (`contracts/item-presentation/SPEC.md` is
normative for all of it).

- **How it builds on Rosetta.** `item.component@1` and `item.presentation@1` are separate contracts, and 0005 forbade a model to
  mention another's package. Decision 0014 added declared requirements: the registry row of `item.presentation@1` says
  `requires: ["item.component@1"]`, and the model uses Rosetta's types directly. `CarrierView` reads a `CarrierSnapshot` through a
  `ComponentRegistry` (an absent component reads as its registered default, a removed one as absent), and
  `ComponentCatalog.disagreements(registry)` holds the catalog (below) to the registry's `ComponentDefinition`s: network visibility
  (`networkSynced`), unit or record, field names (`RecordCodec.fieldNames`).
- **A catalog file.** Law S5 needs to know which components are network-visible, and a Gradle build cannot load a consumer's registry.
  The build therefore reads a `components.json` catalog beside the programs: per component `networkSynced`, `unit`, and each leaf field's kind
  (`bool`, `int`, `string`). It is the consumer's to keep honest: a test of its own asserts `catalog.disagreements(registry)` is empty.
  Every declared input is checked, read or not, and so are the component, the field path and the kind its domain needs.
- **An input has a default**, used when its component or field is absent (the domain's first value unless the program states one), because
  Rosetta elides defaults and a stack that carries nothing must still evaluate (S1).
- **A stack outside the domains draws the base model** and says so (`Selection.inDomain` false). The plan's `select` is total.
- **The lock** is one file for the project, per item, entries ascending by code, one per line; codes run 1 to 2^24 (a float counts whole numbers
  exactly to there). It is required for every target, modern included, so a build for one era never allocates differently from another.
  A selection the program stops producing keeps its entry forever; in the item model it keeps a row that draws the base, because under
  "at least, and the last match wins" a stack still carrying that code would otherwise draw its lower neighbour (`S2:retired-code-draws-base`).
- **The base is not the item's own model.** The legacy artefact is the item's own model file with `parent` set to the base and the overrides,
  so a program naming `ns:item/<id>` as a model for item `ns:<id>` would make the model its own parent. That is a `MODEL_CLASH` refusal for
  the legacy target; the base is another model (`ns:item/<id>_base`, say).
- **A selection's key is its model reference**; tuples reaching `base` are not a selection (no key, no code, no case, no override).
- **The era data is its own table.** The realization ranges (1.14 to 1.21.3, 1.21.4 on) cross the era rows, so `eras/minecraft.json` gets a
  `presentation` table of ranges beside `eras`; `myceliumRepository` proves every real version falls in exactly one.
- **The tasks.** `myceliumPresentation` runs before `processResources` and writes into `build/generated/resources/mycelium/presentation`,
  which joins the main resources; `myceliumPresentationLock` edits the committed lock on request. Both run the contract's own model
  (compiled from the checkout with its requirement) through `SigilTool`, which takes and returns text, so the build holds no rule of its
  own. The plan resource is `META-INF/mycelium/presentation/<ns>/<id>.plan.json`.

## Evidence

Landed in `contracts/item-presentation`; the six fixtures of the matrix are all `CAUGHT` for their label and the reference is clean.
