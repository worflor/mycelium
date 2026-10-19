# Mycelium

**A build-time compatibility compiler you develop alongside your Minecraft mod.**

Porting a mod often means working out the same behavior again through a different API. Mycelium gives that behavior
a definition of its own, so your feature logic can stay independent of the versions and loaders that carry it.

**NAML: Not A Mod Loader.** It runs as part of your build. Your mod ships its own copy of the imported code, under
its own namespace, with no shared Mycelium runtime to install.

For example, item state can live in legacy NBT on one version and modern data components on another. Mycelium's
[component contract](contracts/item-component/SPEC.md) lets you describe the state once; its carrier bridges handle
the native storage. The same separation applies to other version and loader differences: give the behavior a contract,
then connect it to the platform.

## Build alongside your mod

1. **Structure the mod around Mycelium.** Give feature logic a clear boundary from native platform code, and connect
   the mod's build to a separate Mycelium checkout or fork.
2. **Define the behavior your features need.** A contract states what must hold across targets, with laws and tests.
   Reuse an existing one when it fits, or develop a new contract and its native implementations with the feature.

Mycelium supplies the shared machinery: target selection from era data, private source imports, bridge selection,
resource generation, Java compatibility gates, packaging, and evidence. You supply the feature's meaning; the compiler
handles the repeated compatibility work around it. Native code still has access to the platform's full capabilities.

Mycelium grows through use in real mods. Your checkout or private fork can grow with yours; contributing upstream is
optional. The [building guide](docs/building-with-mycelium.md) walks through the process, and the
[contract index](contracts/README.md) offers worked examples to explore.

## Confidence you can inspect

Each contract has a reference implementation and deliberately broken implementations. Its tests must accept the
reference and catch each broken one for the right reason, including an implementation that does nothing. Bugs found
while building mods become lasting checks of the behavior that matters.

The build also checks Java compatibility and native references against pinned target data. It records artifact
prerequisites in the jar and verification results in a separate sidecar. Those results are inspectable: model tests
establish contract behavior, linkage checks establish which
references resolve, and your mod's game tests establish what happens in the running game. Save-file and protocol
compatibility need their own tests. The [architecture guide](docs/architecture.md) explains the boundaries.

## Connect the builds

Keep Mycelium as a separate project beside your mod and include its Gradle plugin. The checkout may be upstream or
your own fork:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../mycelium/gradle-plugin")
}
```

```kotlin
// build.gradle.kts — a loader's build plugin may already apply java
plugins {
    java
    id("dev.woflo.mycelium")
}

mycelium {
    root = rootProject.file("../mycelium")
    namespace = "com.example.mymod.mycelium"
    minecraft = "1.21.1"
}
```

Contract selection follows references in your sources. Bridges are selected when referenced or explicitly requested;
loader-specific bridges also need `loader`. See the [plugin reference](docs/plugin.md) for configuration, generated
resources, check levels, and jar packaging. Pin the checkout revision used by your build.

For a reproducible consumer checkout, use the maintained [source wrapper](docs/source-lifecycle.md). It records local
contract and compiler work, checks the selected source before plugin loading, and prepares updates in a separate Git
checkout. The consumer chooses whether completion requires the captured source.

## Build and verify Mycelium

Use Gradle 8.3 or newer and a compatible JDK 17 or newer to run it. The repository wrapper selects its Gradle version.
Bridge linkage needs a toolchain as new as the newest era; Gradle provisions
toolchains when needed. The first build may download dependencies, JDKs, and pinned game archives.

```bash
./gradlew check
# Focused experimental linter regressions (Java 17, including Java 7 bytecode):
./gradlew -p gradle-plugin behaviorSelfTest
# Repeat on a modern compiler/runtime:
./gradlew -p gradle-plugin behaviorSelfTest '-Pmycelium.testJava=25'
./gradlew myceliumVerify -Pmycelium.contracts=world.copy@1
bash run-contracts.sh
```

On Windows, use `.\gradlew.bat` and `.\run-contracts.ps1`. The standalone runners check the harnesses only. Each reports
the reference result and whether each broken implementation was `CAUGHT` for its intended label.

| | |
|---|---|
| Status | Contracts: 24 packages, 344 rejection fixtures. `./gradlew check` runs the harnesses, era gates, bridge linkage, and repository checks. [Open work](docs/STATUS.md), [history](CHANGELOG.md). |
| Java | Contract models and harness: Java 17 source, checked on every era's Java. Gradle plugin: Java 17 bytecode. See [era gates](docs/eras.md). |
| Targets | `eras/minecraft.json` records version, Java, loader, naming, and realization data. An era row describes a target; it does not prove a mod or bridge works there. |
| Dependencies | Contracts and harness use the JDK only. The plugin uses Gson and ASM; era gates resolve JvmDowngrader and JDKs as needed. Bridge linkage reads pinned game archives. |

<details>
<summary>Task index</summary>

The [plugin reference](docs/plugin.md#tasks) describes each task and its inputs.

| Purpose | Tasks |
|---|---|
| Select and import | `myceliumDeriveContracts`, `myceliumImport`, `myceliumBridgeImport` |
| Check a mod | `myceliumVerify`, `myceliumBridges`, `myceliumUsage`, `myceliumIsolation`, `myceliumPurity`, `myceliumPureCompile`, `myceliumEraJava<N>`, `myceliumEras`, `myceliumSurface`, `myceliumRange`, `myceliumLinkage`, `myceliumCapsule`, `myceliumBehavior` (opt-in experimental linter) |
| Generate and package | `myceliumPresentation`, `myceliumPresentationLock`, `myceliumPackMeta`, `myceliumPureShip`, `myceliumDowngradeJar`, `myceliumEvidence`, `myceliumVerificationEvidence` |
| Maintain this repository | `myceliumRepository`, `myceliumBridgeLinkage`, `myceliumRecordProofs`, `myceliumRefreshVersions`, `myceliumRefreshNames` |

</details>

## Documentation

| Read | For |
|---|---|
| [Architecture](docs/architecture.md) | The layers, principles, and what each check proves |
| [Building with Mycelium](docs/building-with-mycelium.md) | Crossing a version or loader divide |
| [Building with Mycelium skill](.agents/skills/building-with-mycelium/SKILL.md) | Agent guidance for fresh mods and adoption in existing codebases |
| [Plugin](docs/plugin.md) | Consumer configuration and tasks |
| [Eras](docs/eras.md) | Version data, pure-layer laws, and Java gates |
| [Integrations](docs/integrations.md) | Isolating optional mod dependencies |
| [Contracts](CONTRACT-PACKAGE.md), [bridges](BRIDGE-PACKAGE.md) | Package formats and contribution rules |
| [Admission](docs/admission.md), [AGENTS.md](AGENTS.md) | What belongs here and how to work on it |
| [Decisions](docs/decisions/) | Dated rationale and evidence |
| [Founding design](DESIGN.md) | The frozen design record; later decisions take precedence |

## License

[Mozilla Public License 2.0](LICENSE) (MPL-2.0), including Mycelium source imported into a consumer. See the license for
its terms and source-distribution requirements.
