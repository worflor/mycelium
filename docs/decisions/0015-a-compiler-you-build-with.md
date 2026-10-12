# 0015 — Mycelium is a compiler you build with

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Accepted (Wolf, 2026-10-12: "it's like a compatibility layer compiler system framework thing u build with"). |
| Supersedes | the framing of `docs/admission.md` principle 3 ("promote on evidence ... until then it stays native, in the mod") and the catalogue reading of `contracts/` |

## Context

Agents sent to use Mycelium read it as a library of finished capabilities: they look through `contracts/` for something
that fits, use it if it does, and otherwise write the version split by hand in the mod. Wolf has had to correct this every
time. The documents invite it: the front page lists contracts first; `docs/admission.md` says a pattern "stays native, in the
mod" until a second consumer appears; the design is "frozen"; changing a package means "coordinate first". Read together,
they say *take what is on the shelf and touch nothing*.

That is not what Mycelium is for, and not how it has grown. Every package in `contracts/` was written *for* one mod, by the
work on that mod, when the mod met a divide it should not cross by hand: Rosetta (`item.component@1`) when Cartomancy met the
item NBT/component wall; `chunk.hold@1` when Somnium kept chunks; the linkage gate when Recon needed proof a jar links on a
real server. The packages are the server_state of building. What makes Mycelium valuable is the machine that produced them.

## Decision

**Mycelium is a compatibility compiler: the machinery a mod builds with to turn a version or loader divide into a
declaration, laws, and generated per-target artefacts, with evidence.** It is live, and grows with the mods that use it.

1. **When a mod meets a divide, grow Mycelium.** A divide is anything the mod would otherwise answer with a per-version or
   per-loader split by hand (`//? if`, a branch per loader, two copies of an asset, a magic number that must match a table).
   The default move is to model the divide in Mycelium (a pure declaration and its laws, an era row, a build-time
   compiler that emits each target's artefact) and have the mod use it, in the same piece of work. One consumer is enough
   to start: the laws, the rejection matrix and the consumer's gametests are the evidence, not a second mod.
2. **`contracts/` are worked examples and reusable parts, not a catalogue.** Read them to see how a divide was modelled;
   reuse one when it already says what your mod means; extend it (additively within `@N`, a new major otherwise) when it
   nearly does. Never bend a mod to fit a contract that models something else.
3. **Mycelium itself is editable by the work that needs it**, inside its gates: the harness, laws and rejection matrices,
   claims discipline, loader-blind plugin, everything stated derived or checked. Those gates are what make a change safe;
   permission is not.
4. **Declarations are domain-specific, never universal.** Each divide gets its own precise model (a presentation program,
   a slot's lifecycle policies, a component's codec). There is still no universal effect language, event bus or runtime
   DSL; there are many small compilers that share the machine (era data, import, verification, evidence).
5. **Contracts compose by declared requirement** (`docs/decisions/0014-contracts-compose-by-declared-requirement.md`). A
   compiler may build on another contract's model when the dependency is declared and acyclic.

What stays exactly as it was: no runtime shipped (everything imported becomes the mod's private code); native is
first-class (Mycelium makes the platform layer small, never hidden); a passing harness is design-time evidence only; a
no-op fails every contract; the plugin knows no loader; `DESIGN.md` stays frozen as the founding record, with decisions
going beyond it here.

## Consequences

- `README.md` and `AGENTS.md` open with the machine and the build-with loop, then the parts; the contract list moves to
  an index of worked examples with their provenance.
- `docs/admission.md` becomes "what makes a good addition" (laws a no-op fails, pure, a broken implementation per law,
  loader knowledge as data) instead of a gate against one-consumer work.
- `docs/building-with-mycelium.md` is the playbook an agent follows when a mod meets a divide.
- The ask-first list keeps only what is irreversible or reaches another consumer: changing an existing package's
  meaning in place, adding a dependency, publishing, touching another repository.
- A mod should be able to incubate a package in its own repository with the same gates before it moves here (a
  `localContracts` input to the plugin). Not built yet; the next plugin decision.
