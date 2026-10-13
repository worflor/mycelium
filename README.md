# Mycelium

Mycelium grows in the seams. Between one Minecraft version and the next, between Fabric and NeoForge and Forge, between
what a mod means and what each platform lets it say, it threads through and carries that meaning across.

**NAML: Not A Mod Loader.** (Also: Not Another Mod Loader.) Mycelium ships no runtime and replaces no loader. It lives
between them, at development time, as the machine a mod builds with.

That machine is a compatibility compiler. A mod meets a divide (something true on one version or loader and different on
another) and turns it into:

- **a declaration**: a pure model of what the mod means, with no Minecraft in it;
- **laws and a rejection matrix**: what must hold on every target, and a broken implementation for each law, a
  do-nothing one among them, that the harness must catch;
- **era data**: what changes at which version, as rows the compiler reads;
- **build-time compilers**: Gradle tasks that import the model under the mod's own namespace and emit each target's
  artefacts;
- **gates and evidence**: checks that fail the build, and a manifest in every jar of what ran and what did not.

Mycelium is live. It grows with the mods that use it: when your mod meets a divide, you cross it here, once, for every
target, and your mod uses the result in the same piece of work. It is built to be correct before it is friendly, because
its main users are agents, and precision is a feature.

## Building with it

1. Say what the mod means, without Minecraft. Write the laws and the broken implementations.
2. Put version knowledge in `eras/`, not in code.
3. Compile per-target artefacts from the declaration; do not branch by hand.
4. Use it in the mod in the same piece of work, and prove it on every target.
5. Gate and record: `./gradlew check`, and a `CHANGELOG.md` entry.

The playbook, with how to recognise a divide, is `docs/building-with-mycelium.md`. The packages in `contracts/` are
worked examples of earlier crossings, not a shelf to choose from (`contracts/README.md`).

## Starting a mod

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("../mycelium/gradle-plugin") }   // wherever your checkout is

// build.gradle.kts
plugins { id("dev.woflo.mycelium") }
mycelium {
    root = rootProject.file("../mycelium")
    namespace = "com.example.mymod.mycelium"      // artifact-owned prefix
    minecraft = "1.21.1"                          // the era: its Java, its gate, its evidence
    // contracts, integration gates and the jar that ships are derived from your sources; checks { } sets a check's level
}
```

Everything the plugin does, and every option, is `docs/plugin.md`. Its tasks are `myceliumDeriveContracts`, `myceliumImport`, `myceliumVerify`,
`myceliumBridgeImport`, `myceliumBridges`, `myceliumUsage`, `myceliumIsolation`, `myceliumPurity`, `myceliumPureCompile`, `myceliumEraJava<N>` (and `myceliumEras`),
`myceliumPureShip`, `myceliumDowngradeJar`, `myceliumCapsule`, `myceliumSurface`, `myceliumRange`, `myceliumLinkage`,
`myceliumPresentation`, `myceliumPresentationLock`, `myceliumPackMeta` and `myceliumEvidence`. For this repository alone: `myceliumBridgeLinkage` (every bridge variant against the game), `myceliumRepository`,
`myceliumRecordProofs`, `myceliumRefreshVersions` and `myceliumRefreshNames`.

To run this repository's own checks:

```bash
./gradlew check                                              # everything the repository promises
./gradlew myceliumVerify -Pmycelium.contracts=world.copy@1   # one contract through the plugin
bash run-contracts.sh                                        # every harness without Gradle (or .\run-contracts.ps1)
```

Each harness prints the reference result, then every broken implementation `CAUGHT` or `MISSED` for its intended label,
then `Result: PASS|FAIL`. A green exit is one input to acceptance, never the decision (`DESIGN.md` §11).

| | |
|---|---|
| Status | Contract models and rejection matrices are checked by the repository build. |
| Java | Contracts and harness: written for 17, proven on every Java an era runs on (`docs/eras.md`). Plugin: built for 17. |
| Loaders | Whatever `eras/minecraft.json` lists for an era: Fabric, Quilt, NeoForge and Forge on modern versions, the Bukkit family (Spigot, Paper, Purpur, Folia) for plugins, and the loaders of older eras. The plugin knows none of them: each is a row of data. |
| Dependencies | None at design time. The plugin uses Gson and ASM; era gates fetch JvmDowngrader and each era's JDK when needed. |

## What Mycelium does not claim

- That a contract passing its harness behaves correctly in a real game. That is the mod's gametest evidence, per target.
- That source portability implies save or protocol portability (`DESIGN.md` §8.6).
- That every feature translates. Native work is first-class, and is tracked as native (`DESIGN.md` §4).

## Where to go next

| You want | Read |
|---|---|
| To work on this repository | `AGENTS.md` |
| To cross a divide in a mod | `docs/building-with-mycelium.md` |
| How the machine fits together | `docs/architecture.md` |
| To use the plugin | `docs/plugin.md` |
| What a good addition looks like | `docs/admission.md` |
| What a contract package is made of | `CONTRACT-PACKAGE.md` |
| Worked examples of crossings | `contracts/README.md` |
| Versions, eras and the pure layer's laws | `docs/eras.md` |
| Optional integrations kept isolated | `docs/integrations.md` |
| Why a choice was made | `docs/decisions/` |
| What is open | `docs/STATUS.md` |
| The founding design (v0.4, frozen) | `DESIGN.md` |

## License

Mycelium is licensed under the [Mozilla Public License 2.0](LICENSE). The licence applies file by file: a mod built with
Mycelium, including a paid or closed one, keeps its own licence, while Mycelium's files, and any changes made to them,
stay under MPL-2.0 wherever they are carried, including contract models and stubs copied into a mod's jar. The licence
grants no rights to the name Mycelium; a modified fork is published under a name of its own.
