# block.presentation@1 - the Mosaic

| | |
|---|---|
| Contract id | `block.presentation@1` (implementation namespace `mycelium.block_presentation.v1`) |
| Kind | Model contract (`CONTRACT-PACKAGE.md`): laws over a compiler and the plan it hands an adapter |
| Requires | `item.presentation@1` (its inputs, guards, templates, catalog and `Json`), and through it `item.component@1`: declared in `contracts/registry.json`, `docs/decisions/0014-contracts-compose-by-declared-requirement.md` |
| Origin | Cartomancy. The general compilation decision is `docs/decisions/0019-mosaic-block-presentation.md`; examples here are synthetic |
| Reference model | `src/`: pure Java 17, no Minecraft or loader imports, no dependency but `item.presentation@1`'s model |
| Claims discipline | Everything here is design-time. A passing harness proves the reference compiler and the matrix; it says nothing about how a game composes or draws a baked model. That evidence is a consumer's own client lane |

## 1. The problem

A block's look follows from its block state: one model (or a multipart list) per property combination, written out by hand. That
stops working when the look follows from **data**. A configurable display with four independent 32-valued fields has
`32^4` = 1,048,576 combinations. Its parts can read block entity data without representing every combination as a property.

The Sigil (`item.presentation@1`) solves the item half of this by choosing **one** model from a stack's component state. A
block that wears a different design on each side needs to draw **several** models, turned to face four ways, chosen by four
independent fields. The Mosaic states that picture once, as a list of **parts** over the fields of components, and gives the
platform adapter a plan and a **manifest**: the exact set of models its client has to have loaded.

The cost of a naive reading is the whole point of the contract. A Sigil program is compiled by enumerating every tuple of the
inputs it reads (capped at 4096). A mosaic of four 32-valued inputs spans 1,048,576 tuples, but each part reads only one of them
and spans 32. The Mosaic enumerates **per part**, so the cost is the sum of the parts' spans and not the product of every
domain, and a picture whose parts are independent costs nothing like its combinations.

## 2. The model

| Type | Meaning |
|---|---|
| `Mosaic` | `(block, inputs, parts)`: one block's presentation. Inputs are the Sigil's `Input`, held sorted by name; parts are kept in the order declared |
| `Part` | `(when, model, turn)`: a guard (the Sigil's `Condition`s), a model reference or template, and a turn of 0, 90, 180 or 270 degrees clockwise seen from above about the block's centre. Every part whose guard holds is drawn, in declaration order |
| `Placed` | A part as drawn for one state: its expanded model, its turn and the index of the part |
| `Assembly` | What a block with this data draws: the `Placed` list and `inDomain` |
| `Assembler` | The pure assembly: `assemble(mosaic, tuple)`, `assemble(mosaic, view)`, `span(mosaic, n)`, `manifest(mosaic)`. The oracle every artefact is held to |
| `BlockRealization`, `BlockTarget` | `dynamic-model` or `none`, as `eras/minecraft.json` (`realizations`, `block.presentation`) gives a version; `BlockTarget` carries the version only to name it in a refusal |
| `MosaicCompiler`, `ReferenceMosaic` | `compile(mosaic, catalog, target)` to a `MosaicCompilation`: `Compiled(plan, artefacts)` or `Rejected(diagnostics)`. `maxSpan()` is its per-part cap |
| `MosaicPlan`, `ReferenceMosaicPlan` | What an adapter evaluates at run time: `assemble(view)` (total), `manifest()`; `toTree` is the plan resource, `ReferenceMosaicPlan.parse` reads it back |
| `MosaicTool`, `Layout` | The whole compile over text, which the Gradle task calls; where the plan goes |

The inputs, the guard language, the templates and the diagnostics are `item.presentation@1`'s: a mosaic is read by the same code that
reads a Sigil program, so the two cannot disagree about what `{"north": ["moon", "sun"]}` means. A mosaic adds the parts' `turn` and
the rule that every part whose guard holds is drawn.

### The mosaic file

`<name>.mosaic.json`, beside the Sigil programs:

```json
{
  "block": "example:display_panel",
  "inputs": {
    "north": {"component": "example:panel", "field": "north", "domain": {"enum": ["none", "stripe", "grid"]}},
    "east":  {"component": "example:panel", "field": "east",  "domain": {"enum": ["none", "stripe", "grid"]}}
  },
  "parts": [
    {"model": "example:block/panel/core"},
    {"model": "example:block/panel/side_{north}"},
    {"model": "example:block/panel/side_{east}", "turn": 90}
  ]
}
```

A part with no `when` is always drawn. Inputs read **block entity data**: the component is the same record a stack of the item
carries (a placed block and the item it came from share one component), declared `networkSynced`, because the client draws from
what the server sent it.

### The artefact

The plan, `META-INF/mycelium/presentation/<ns>/<path>.mosaic.plan.json`: the mosaic's read inputs and parts (canonical), the
realization, and the **manifest**. The models themselves are the consumer's assets; the compiler writes nothing a consumer
authored. An adapter loads exactly the manifest's models at model-loading time, and at bake time composes, for each block, the
parts `assemble` returns, each rotated by its turn, in order.

## 3. Laws

| Law | Statement | Labels |
|---|---|---|
| M1 Totality | Every state of the read inputs assembles to exactly the parts whose guards hold, and no others. The plan agrees with the evaluator on every state; an input no part reads is dropped; absent data, a value of the wrong kind and a value outside the domain each read as the input's default, `inDomain` says so, and no other part is affected | `M1:totality-agrees-with-evaluator`, `M1:every-holding-part-is-drawn`, `M1:no-part-when-guard-fails`, `M1:unread-input-dropped`, `M1:absent-component-reads-default`, `M1:out-of-domain-reads-default`, `M1:wrong-kind-reads-default`, `M1:hand-checked-lit-state`, `M1:hand-checked-dark-state`, `M1:guard-and-template-together`, `M1:guard-over-a-subset-of-values`, `M1:constant-mosaic`, `M1:empty-mosaic-draws-nothing`, `M1:properties-hold-on-random-mosaics` |
| M2 Order and turn | Parts are drawn in the order they are declared (a later part paints over an earlier one), each with exactly the turn it declares. A block read through a `ComponentView` and through the plan resource read back draws what the evaluator says | `M2:declaration-order`, `M2:turn-carried-exactly`, `M2:plan-resource-agrees`, `M2:adapter-reading-agrees`, `M2:swapped-parts-draw-swapped` |
| M3 Independence | An input no part reads changes nothing; an input only one part reads moves only that part | `M3:unread-input-changes-nothing`, `M3:input-only-moves-its-parts` |
| M4 Manifest | The manifest is exactly the set of models any state can draw: none missing (an adapter would meet a model it did not load) and none extra (it would load a model nothing draws), sorted, without repeats. The plan resource's manifest is held to the same, and a resource whose manifest is stale is refused when read | `M4:manifest-covers-every-drawn-model`, `M4:manifest-has-no-undrawn-model`, `M4:manifest-sorted-unique`, `M4:resource-manifest-covers-every-drawn-model`, `M4:constant-manifest`, `M4:empty-manifest` |
| M5 Projection | Every declared input reads a network-visible component of the catalog, with the field and kind it says; anything else is refused at compile time | `M5:server-private-rejected`, `M5:unknown-component-rejected`, `M5:unknown-field-rejected`, `M5:type-mismatch-rejected`, `M5:present-needs-a-unit-component`, `M5:present-on-a-unit-component-accepted` |
| M6 Determinism | The same mosaic produces byte-identical artefacts: keys sorted, fixed formatting, line feeds, printable ASCII only, whatever the platform, locale or the order the mosaic lists its inputs, guards and keys in. The order of the parts is the picture's own and is kept | `M6:repeat-identical`, `M6:canonical-json`, `M6:ascii-and-line-feeds`, `M6:declaration-order-independent`, `M6:locale-independent`, `M6:part-order-is-kept` |
| M7 Bounded | The span of **each part** (the product of the domains of the inputs that part reads) is at most the cap (4096 unless a compiler states another), checked from the domain sizes **before** anything is enumerated; the product saturates instead of overflowing. The product over every input is not bounded: parts are enumerated apart | `M7:part-span-over-cap-rejected`, `M7:product-over-cap-rejected-before-enumeration`, `M7:span-at-cap-accepted`, `M7:the-wide-mosaic-spans-more-than-the-cap`, `M7:independent-parts-beyond-product-accepted` |

Beside the laws, the harness holds well-formedness (`P1:*`, a mosaic that is malformed, unsound or checked by no one is refused, by
kind; `P1:compiler-rechecks-mosaic`), the target (`R1:no-realization-rejected`: a version with no mechanism is refused naming it) and the
build tool over text (`T1:*`). Property-style checks compile 40 seeded mosaics of random shape and hold each to M1, M4 and M6.

## 4. Rejection matrix

| Fixture | What it gets wrong | Caught by |
|---|---|---|
| `ReversedTurn` | hands the adapter every turn the wrong way round | M2 (`M2:turn-carried-exactly`) |
| `SwappedOrder` | hands the adapter the parts last-declared first | M2 (`M2:declaration-order`) |
| `ManifestOmitsTemplates` | lists only models named outright, none a template expands to | M4 (`M4:manifest-covers-every-drawn-model`) |
| `ManifestOverreach` | expands every template over every value, whether or not its guard holds there | M4 (`M4:manifest-has-no-undrawn-model`) |
| `LeakyProjection` | accepts an input over a server-private component | M5 (`M5:server-private-rejected`) |
| `HashOrderEmitter` | writes JSON keys in hash order | M6 (`M6:canonical-json`) |
| `CapIgnoring` | accepts and enumerates a part that spans more than the cap | M7 (`M7:part-span-over-cap-rejected`) |
| `NoOpMosaic` | draws no part for any state | M1 (`M1:totality-agrees-with-evaluator`), and M2, M4 |

`NoOpMosaic` emits a well-formed plan that never errs; it is in the matrix because a compiler that ignores the mosaic must fail.

## 5. Using it

**Writing a mosaic.** Keep it in the consumer's presentation directory (by default src/main/presentation of the project) as `<name>.mosaic.json`,
beside the Sigil programs and the `components.json` catalog they share. A file ending `.mosaic.json` is a mosaic and never an item
program. There is no lock: a mosaic has no legacy codes.

**Building.** In the consumer's build script:

```kotlin
mycelium {
    root = rootProject.file("../mycelium")
    minecraft = "1.21.1"                       // the era table chooses dynamic-model or none
    contracts("item.presentation@1", "block.presentation@1")
}
```

`./gradlew myceliumPresentation` (also run by `processResources`) compiles the item programs and the mosaics for the target
into `build/generated/resources/mycelium/presentation`, and fails with the diagnostic on any breach. The mosaic's plan is
`META-INF/mycelium/presentation/<ns>/<path>.mosaic.plan.json`.

**At run time.** The platform adapter reads the plan resource once (`ReferenceMosaicPlan.parse`), asks the platform to load the
models of `plan.manifest()`, and replaces the block's baked model with one that, for each block, builds a `ComponentView` over the
block entity's data and draws `plan.assemble(view).placed()`: each `Placed.model()` rotated `turn` degrees about the vertical
axis through the block's centre, in order. When the block entity's data changes, the adapter has the chunk remeshed.

**Example, a panel.** Four inputs of 28 values, a core and four sides: 614,656 states, 29 models in the manifest, a compile that
enumerates 28 tuples per side. Adding a design adds one value to the four domains and one model to the manifest and renumbers nothing.

## 6. Running the harness

From the Mycelium root: `bash run-contracts.sh block-presentation` (or `.\run-contracts.ps1 block-presentation`), or
`./gradlew myceliumVerify -Pmycelium.contracts=block.presentation@1`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT`; exit code 0.

## 7. Explicit non-claims

- No Minecraft or mod loader code was executed to produce this package. Whether a game composes the models the plan names, rotates them
  as the turns say, and draws them in order is the consumer's client lane.
- The compiler does not check that a model file exists, or what is in it: models are the consumer's assets. An adapter that cannot
  load a manifest model has a missing asset, and says so.
- Block **state** (facing, waterlogging, a property) is not an input: a mosaic reads components. A block whose picture also depends on its
  state keeps ordinary blockstate models for that part, and the mosaic composes on top.
- Lighting, animation, and per-frame effects are not covered: a mosaic is the static picture of a block's data. What glows or moves is
  the consumer's renderer.
- Item rendering of the same picture is the adapter's: the plan is the same for the held item, which carries the same component, but
  how an item composes models is not part of this contract.
- The catalog is held to the registry by `ComponentCatalog.disagreements`, which a consumer must call; the build reads only the file.
- A block whose data is outside a mosaic's domains draws with the defaults and says so (`inDomain`); the contract does not decide what
  a mod does about it.
