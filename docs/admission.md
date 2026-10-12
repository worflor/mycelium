# What belongs in Mycelium

Mycelium's promise is one mod, written once, built for every loader and version it can reach, with evidence of what
holds. A mod might be a server-side command, an integration with another mod, or a total conversion. All of them can
build on it. Whatever is added must keep that promise for all of them, and must never make a mod weaker than it would
be without Mycelium.

## Principles

1. **Native is first-class, never a fallback.** A mod on Mycelium can do anything a mod without it can: mixins, access
   wideners, raw packets, custom rendering, world generation, another mod's API. That code lives in providers and
   native extensions (`DESIGN.md` §4, §5), calls the platform directly, and goes through no wrapper of Mycelium's.
   Mycelium makes the platform layer *small*, never *hidden*: contracts cover what is shared, and the rest stays as
   raw and as powerful as the platform allows.
2. **Pay only for what you use.** A mod that imports one contract carries that contract's model, privately, under its
   own namespace. It carries no runtime, no shared jar and no other code (`DESIGN.md` §1, §12). A mod that imports
   nothing still gets the build's checks and an evidence manifest.
3. **Promote on evidence.** A pattern becomes a contract when independent consumers show the same semantics, or when
   one consumer finds a bug that any implementation could make and no contract forbade. It never becomes one because
   it looks reusable. Until then it stays native, in the mod (`DESIGN.md` §4).
4. **The plugin knows no loader.** Loader and version knowledge is data (`eras/minecraft.json`, `eras/loaders.json`)
   or lives in a consumer's buildscripts. A task reads sources, bytecode, the registry and the era table, never a
   loader's API (`docs/decisions/0001-substrate.md`, `docs/decisions/0003-forge.md`).
5. **Everything stated is derived or checked.** If the repository says it, a build fails when it stops being true.
   The only exception is prose, whose truth stays with its author and reviewer
   (`docs/decisions/0004-the-repository-checks-itself.md`).

## A contract

Admit it when every answer is yes:

- **Is it a domain capability whose meaning does not depend on one mod?** Someone else could implement it, and a
  different mod could call it.
- **Does it have laws a do-nothing implementation fails?** If nothing a no-op does can break it, it is a utility or a
  vocabulary. That belongs in the mod's own kernels, not here.
- **Can you write the broken implementations?** Write one for each law, realistic and ideally the bug that motivated
  it. A law with nothing in the matrix is unproven.
- **Is it pure?** It can be stated without Minecraft types. If it needs them, it is a provider concern or a native
  extension.
- **Is its vocabulary its own?** It declares its own reasons, traits and ids, and composes with other contracts in
  the consumer, or builds on one it declares as a requirement (`docs/decisions/0005-what-a-contract-is-made-of.md`,
  `docs/decisions/0014-contracts-compose-by-declared-requirement.md`).

Not admitted:
- a wrapper over one platform API with no laws of its own (the platform already is that contract);
- a shared vocabulary or utility package (no laws a no-op fails, and it would couple every contract that used it);
- a feature of one mod that nothing else would call. Keep it in the mod; promote it when a second consumer needs it.

A law found later, by a consumer's red team or an issue, goes into the contract. Add the law, its label, and a
fixture that makes the mistake, and see the harness catch it. It is additive, and it stays in `@N` when no label or
required method changes. The shape of a package is `CONTRACT-PACKAGE.md`.

## A plugin task or option

- **Every consumer could need it, on every loader.** A check or a product that only one mod wants belongs in that
  mod's build.
- **It says what it checked.** A task with nothing to check says so, in its report and in the evidence manifest. It
  never passes vacuously.
- **Misconfiguration fails loudly**, where it is declared and again where it is decoded.
- **It cannot break a build that did not ask for it.** A new check warns, or is off, until a consumer opts in.
- **It is proven.** A case in `gradle-plugin/src/test/java/dev/woflo/mycelium/gradle/SelfTest.java` breaks the
  thing it checks and sees it fail, and a consumer's build runs it.
- **It is mapped.** `AGENTS.md` names its class and `README.md` names its task (both checked).

## An era, a loader, a version

- **Versions** come from Mojang's launcher manifest into `eras/versions.json` (`myceliumRefreshVersions`, by hand;
  builds stay offline). The repository check proves every version falls in exactly one era.
- **An era** is a row of `eras/minecraft.json`: its Java, loaders, names and walls. Each bound is a version the
  manifest names, or `<line>.x` for a whole line.
- **A loader** gets its package roots in `eras/loaders.json`, with where they were read. From then on the pure layer
  may not mention them (law P1), with no list kept anywhere else.
- **Name data** for a version is pinned in `eras/names.json` (`myceliumRefreshNames`, by hand), each file by SHA-256;
  the repository check proves every entry is for a real version some loader runs under that naming.
- A row's facts are re-verified against the loader's own maven when a consumer first ports to it (`docs/eras.md`).

## The harness

Every package runs on it, so a change to the harness is a change to every contract's evidence. It is Java 17 using
only `java.*`, and it must run when downgraded to Java 8. Its own rules are proven in `harness/test`: each class there
is a main that exits 0 when it holds, and every runner runs them before any package.

## A document

- **Living documents describe the present, timelessly**: `README.md`, `AGENTS.md`, `CONTRACT-PACKAGE.md`, `docs/`.
  They carry no "as of" state. A count appears only on `README.md`'s status line, where the repository check derives
  it. Every path they name is in this repository.
- **History** goes to `CHANGELOG.md` and `docs/decisions/`: what changed, what was run, what was found, dated.
- **Consumers are named in words, as provenance**: who a contract was written for, where a law was learned. Mycelium
  serves every mod that builds on it, so no document depends on one of them.
