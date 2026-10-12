# What makes a good addition

Mycelium grows with the mods that use it. A contract, a task, an era row or a harness rule is added by the work that
needs it, in the same piece of work, and one mod is enough to start (`docs/decisions/0015-a-compiler-you-build-with.md`).
This page is not a gate against that. It says what a good addition looks like, so that what grows stays correct for
every mod that builds on it. The invariants every addition keeps are in `docs/architecture.md`; the loop for crossing a
divide is `docs/building-with-mycelium.md`.

## A contract

A contract is a good one when each answer is yes:

- **Does it declare a divide, in terms of what the mod means?** The model says what is stored, shown, kept or promised,
  without the mod's content. Another mod could implement it and another could use it. It need not have a second consumer
  today.
- **Does it have laws a do-nothing implementation fails?** If nothing a no-op does can break it, it is a utility or a
  vocabulary, and belongs in the mod's own kernels.
- **Can you write the broken implementations?** One for each law, realistic and ideally the bug that motivated it. A
  law with nothing in the matrix is unproven.
- **Is it pure?** It can be stated without Minecraft types, and holds on every Java an era runs on. If it needs
  Minecraft, that part is a provider's.
- **Is its vocabulary its own?** It declares its own reasons, traits and ids. It builds on another contract only through
  a declared requirement (`docs/decisions/0014-contracts-compose-by-declared-requirement.md`), and otherwise the mod composes them
  (`docs/decisions/0005-what-a-contract-is-made-of.md`).
- **Is version knowledge data?** What changes at which version is a row in `eras/`, never a number in code.

Where something fails these, it is not a contract. It is still welcome work, with a different home:

- a wrapper over one platform API with no laws of its own: the platform already is that contract, and the mod's provider
  calls it directly;
- a shared vocabulary or utility package: no law a no-op fails, and it would couple every contract that used it;
- a feature with no divide in it: it has nothing to cross, so it stays in the mod.

A law found later, by a mod's red team or an issue, goes into the contract: the law, its label, and a fixture that makes
the mistake, caught by the harness. It stays in `@N` when no label or required method changes. The shape of a package is
`CONTRACT-PACKAGE.md`. A new contract also gets its row in `contracts/README.md`, the index of worked examples.

## A plugin task or option

- **It works on every loader.** One mod can be the reason for it. What it cannot be is one mod's habit: a check or a
  product that only suits a single build's conventions belongs in that mod's build.
- **It says what it checked.** A task with nothing to check says so, in its report and in the evidence manifest. It
  never passes vacuously.
- **Misconfiguration fails loudly**, where it is declared and again where it is decoded.
- **It cannot break a build that did not ask for it.** A new check warns, or is off, until a consumer opts in.
- **It is proven.** A case in `gradle-plugin/src/test/java/dev/woflo/mycelium/gradle/SelfTest.java` breaks the thing it
  checks and sees it fail, and a consumer's build runs it.
- **It is mapped.** `AGENTS.md` names its class and `README.md` names its task (both checked), and `docs/plugin.md` says
  what it does.

## An era, a loader, a version

- **Versions** come from Mojang's launcher manifest into `eras/versions.json` (`myceliumRefreshVersions`, by hand;
  builds stay offline). The repository check proves every version falls in exactly one era.
- **An era** is a row of `eras/minecraft.json`: its Java, loaders, names and walls. Each bound is a version the manifest
  names, or `<line>.x` for a whole line.
- **A loader** gets its package roots in `eras/loaders.json`, with where they were read. From then on the pure layer may
  not mention them (law P1), with no list kept anywhere else.
- **Name data** for a version is pinned in `eras/names.json` (`myceliumRefreshNames`, by hand), each file by SHA-256; the
  repository check proves every entry is for a real version some loader runs under that naming.
- A row's facts are re-verified against the loader's own maven when a mod first ports to it (`docs/eras.md`).

## The harness

Every package runs on it, so a change to the harness is a change to every contract's evidence. It is Java 17 using only
`java.*`, and it must run when downgraded to Java 8. Its own rules are proven in `harness/test`: each class there is a
main that exits 0 when it holds, and every runner runs them before any package.

## A document

- **Living documents describe the present, timelessly**: `README.md`, `AGENTS.md`, `CONTRACT-PACKAGE.md`, `docs/`.
  They carry no "as of" state and no history. A count appears only on `README.md`'s status line, where the repository
  check derives it. Every path they name is in this repository.
- **Each fact has one home** and everything else links to it. The homes are in `AGENTS.md`, "Where knowledge lives".
- **A proposal says it is a proposal**, in its decision record's status and wherever a living document mentions it.
- **History** goes to `CHANGELOG.md` and `docs/decisions/`: what changed, what was run, what was found, dated.
  `DESIGN.md` and `archive/` are the founding record and are not rewritten.
- **Consumers are named in words, as provenance**: who a contract was written for, where a law was learned. Mycelium
  serves every mod that builds on it, so no document depends on one of them, and none points into another repository.
- **Do not add a document because information exists.** Ask what kind of understanding it gives and whether a home
  already fits.
