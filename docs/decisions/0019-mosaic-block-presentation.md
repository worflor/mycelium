# 0019 — The Mosaic: block presentation composed from component data

| | |
|---|---|
| Date | 2026-10-13 |
| Status | Accepted; reference and rejection matrix implemented |
| Builds on | item.presentation@1 and declared contract requirements (decision 0014) |

## Context

Item presentation chooses one model. A block can need several independently selected parts, each with its own rotation.
Enumerating every combination couples independent fields and makes resource generation unnecessarily expensive. The
compiler also needs to tell the client exactly which models it must load.

## Options

- Add multiple-match behaviour to item programs: changes their first-match meaning.
- Enumerate complete block states: multiplies independent domains and reaches the compilation cap unnecessarily.
- Compile each model part over the inputs it reads: preserves independence and gives an exact model manifest.

## Decision

`block.presentation@1` declares an ordered list of guarded model parts. It reuses the Sigil's inputs, guards, templates,
and component catalogue through a declared requirement. Every matching part draws, with its stated quarter-turn.

Enumeration and the span cap apply per part. The manifest is exactly the union of models those parts can draw, sorted
and deduplicated; a plan with a stale manifest is rejected. Consumer-authored models remain assets, not compiler output.
Blockstate properties remain in ordinary blockstate models. Components supply the block entity data.

myceliumPresentation compiles *.mosaic.json beside item programs, using the separate `block.presentation` realization
table in `eras/minecraft.json`. Mosaics need no legacy numeric-code lock.

## Evidence

The pure reference and rejection matrix test assembly, ordering, projection, manifest completeness, determinism, and
per-part bounds. Plugin self-tests exercise compilation and task inputs. These establish model and build evidence;
client rendering, rotation, and model loading still require a consumer's game tests.

## Consequences

Independent parts scale with the sum of their spans. Item program semantics stay unchanged. Native rendering belongs to
the consumer adapter; the plan and its manifest provide the checked boundary.
