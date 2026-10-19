# Building with Mycelium

Build the mod and its Mycelium compiler alongside each other, as separate projects. The authoring work has two parts:
structure the mod with a boundary between feature logic and native platform code, then define the contracts its
features need. The compiler supplies the shared compatibility machinery described in the [plugin reference](plugin.md).

A **divide** is a difference between versions or loaders that changes what a feature must do. Model its meaning once,
then compile or adapt it for each target. [Existing contracts](../contracts/README.md) are optional reuse: take one when
it fits and helps, or author the declaration your feature needs. Adding a contract is part of building the mod.

The [architecture](architecture.md) explains the layers; [decision 0015](decisions/0015-a-compiler-you-build-with.md)
explains why Mycelium grows alongside its first consumer.

For agent-assisted fresh builds or adoption in an existing mod, see the [Building with Mycelium skill](../.agents/skills/building-with-mycelium/SKILL.md).

Consumer checkout setup, source locking, and compiler updates are described in the [source lifecycle guide](source-lifecycle.md).

## Recognise a divide

You are looking at one when the mod has, or is about to have:

- a Stonecutter `//? if`, a branch per loader, or a helper whose body is a version switch (`Compat.readLong`);
- the same fact written twice (a modern asset and a legacy asset; a table in JSON and arithmetic in Java that must agree);
- a value whose meaning changes across a wall in `eras/minecraft.json` (item NBT to components at 1.20.5, item model
  definitions at 1.21.4, `ResourceLocation` to `Identifier` at 1.21.11);
- a lifecycle the loaders expose differently (respawn, clone, dimension change, unload);
- a guarantee the mod relies on but nothing checks (state that must not survive a logout, a sprite that must match state).

One `//? if` around a renamed method is not worth a compiler: Stonecutter is the right tool for a rename. A divide is
worth modelling when it carries *meaning* (what the mod stores, shows, keeps, or promises), when it repeats, or when
getting it wrong is silent.

## Author the crossing

The contract supplies the feature's meaning and obligations. Use Mycelium's existing machinery to carry and check it;
extend the compiler when the feature needs a new kind of generated output or native realization.

1. **Specify what the mod means, without Minecraft.** Write `SPEC.md`, then the pure model: a component's record,
   a presentation program, a slot and its policies. Types, not maps of strings. Make the declaration specific to this
   divide; do not reach for a general mechanism.
2. **Write the laws.** What must be true on every target: round trips, agreement between targets, stability across
   additions, ordering, safety under corruption. Include positive obligations that a do-nothing implementation breaks,
   alongside safety laws.
3. **Write the broken implementations first.** One per law, ideally the bug the mod would really have made, and a `NoOp`.
   The harness must catch every one (`CONTRACT-PACKAGE.md`). If you cannot write a broken implementation, the law is not
   a law yet.
4. **Put version knowledge in data.** What changes at which version goes in `eras/` as a row the compiler reads, never as
   numbers in code (`docs/eras.md`).
5. **Write the bridge.** Where the divide is Minecraft itself (how a stack keeps data, how an event is delivered), add the
   contract's port and write the native half in `bridges/<slug>/`: a variant per realization, an overlay where an API shifts
   inside one, conformance cases for the port's laws on the real game (`BRIDGE-PACKAGE.md`). The realization table in
   `eras/minecraft.json` says which variant covers which version. `myceliumBridgeLinkage` checks compilation and references
   against pinned game data; the consumer runs the cases.
6. **Compile, don't branch.** Where the mod needs per-target artefacts (assets, generated tables, plans), a Gradle task
   in the plugin generates them from the declaration for the target's version. The mod's platform layer reads the
   generated plan; it does not re-derive it. Keep the task loader-blind (`docs/plugin.md`).
7. **Reuse where it helps.** A model that builds on another contract declares that requirement
   (`docs/decisions/0014-contracts-compose-by-declared-requirement.md`). An existing contract may be extended additively
   within `@N`, or as a new major when its meaning changes. Choose reuse for a good semantic fit; a new declaration is
   also ordinary feature work.
8. **Use it in the mod in the same piece of work.** Import it, replace the hand-written split, delete the duplicate, and
   prove it on every target with the mod's gametests. That mod evidence is what turns design-time laws into a claim
   about the game.
9. **Gate and record.** `./gradlew check` green here, the mod's own gates green there, a `CHANGELOG.md` entry, and a
   decision record if you chose between real alternatives (`docs/decisions/`).

Develop compiler-side work in the Mycelium checkout or private fork your mod includes through `includeBuild`
(`docs/plugin.md`). Keep the mod and compiler changes coordinated until both repositories' gates pass. You can submit
an upstream issue or pull request when useful; the mod can use its own compiler changes immediately.
Edit the consumer only with its owner's authorization, from its own repository.

## Crossings so far

Each worked example is one divide, crossed for one mod. Read the ones near your problem before you start: the SPEC says
what the laws mean, and the matrix shows what the mod would really have got wrong. The index is `contracts/README.md`.
Packages in progress are proposals in `docs/decisions/`; the board is `docs/STATUS.md`.

## What you may do here

Change contracts, the harness, the plugin, eras and documents as the work needs, inside the gates (`AGENTS.md`). Ask
first only for what is irreversible or reaches another consumer: changing an existing package's meaning in place (a new
major is the default answer), adding a dependency, publishing, or editing another repository.
