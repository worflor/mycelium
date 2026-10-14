# 0019 — The Mosaic: block presentation composed from component data


## Context


Written the vanilla way that is `28^4` = 614,656 blockstate variants (or a multipart list with a case per side per design, written by hand
and kept in step with the Java that stores the choice). Block states cannot hold it, and a block entity has to, so the look follows from block
**entity data** that the block state does not carry. Mod loaders each have a way to draw a block from such data (Fabric's renderer API
from 1.14; model data on Forge and NeoForge), none to say which models the data may name, and nothing that proves the set of models loaded
is the set drawn: the usual failure is a model a state names that was never loaded, which draws as the missing-texture cube, in one
configuration out of thousands.

## Decision

`block.presentation@1`: a block's picture declared once as a **mosaic**: the Sigil's inputs over component fields, and an ordered list of
**parts**, each a guard, a model (or a template over input values) and a quarter-turn. Every part whose guard holds is drawn, in order.

- **Read through the Sigil.** A mosaic is parsed by the Sigil's own `Program` reader (the inputs, guards and templates are checked by the
  code that checks them for items), so there is one guard language. The contract `requires` `item.presentation@1`.
- **Per-part enumeration (M7).** The compiler computes the **manifest**, every model any state can draw, by enumerating each part over only the inputs
  that part reads. Its cost is the sum of the parts' spans; the cap (4096, as the Sigil's) applies to each part's span, checked from the domain
  sizes before anything is enumerated. A panel of four 28-valued inputs spans 614,656 states and costs 28 tuples a side.
- **The manifest is a law (M4).** It lists exactly the models any state can draw: none missing, none extra. An adapter loads those models and no
  others, and a plan whose manifest is stale is refused when read.
- **No new realization of the models.** The models are the consumer's assets and the compiler writes none; it writes the plan. The era table
  gains a `block.presentation` table among its `realizations` (`dynamic-model` from 1.14, `none` before) rather than a column on `item.presentation`'s, because the two tables'
  ranges and realizations differ and each is proven to cover every real version exactly once.
- **Block state is not an input.** A mosaic reads components. What follows from facing or a property stays in ordinary blockstate models, and
  the mosaic composes on top of it.
- **No lock.** A mosaic has no legacy codes, so no lock and no ascending-order law. The plan names models, never numbers.

### The plugin

`myceliumPresentation` compiles `*.mosaic.json` in the presentation sources through `MosaicTool` (the contract's own text entry), beside the
item programs it already compiles, with the block table's realization. A `*.mosaic.json` is never an item program. The two contracts' compiled
models are kept apart, the task tracks both contracts' models as inputs, a problem in either is the same `PR1` finding, and a build with no mosaics behaves exactly as before.

## Alternatives

- **A second case semantic in the Sigil** (draw every matching case). Rejected: it changes the Sigil's first-match law (S1), and an item
  presentation that draws several models is a different promise from one that picks one.
- **Enumerate every state.** Rejected: 614,656 states for one block, and `27^4` more for every extra side-like input. The per-part rule is what
  makes the contract usable for the case it was written for.
- **Block states as inputs.** Rejected for this version: it needs a catalog of block state properties and a reader for them, which
  is a second input kind. Components carry the data the block entity holds; state stays vanilla.
- **Generate the block models.** Rejected: designs are authored, and a generator that wrote them would hide the one thing a mod's artist decides.

## Consequences

- The consumer declares the picture once; adding a design is a value in the domains and a model in the manifest, and nothing is renumbered.
- A model named by a state but missing from the manifest is impossible by M4, so a missing-model cube cannot come from the mosaic.
- The contract proves the plan and the manifest, not the pixels: whether the loader composes the models, rotates them as the turns say and
  draws them in order is the consumer's client lane (SPEC §7).
