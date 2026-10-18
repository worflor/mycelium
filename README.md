# Mycelium

**A build-time compatibility compiler you develop alongside your Minecraft mod.**

Mycelium grows in the seams: between Minecraft versions, between loaders, and between a mod's intent and the platform
that carries it. **NAML: Not A Mod Loader.** Imported models and bridges become private code under your mod's namespace;
there is no shared Mycelium runtime to install.

Building with it has two parts:

1. **Structure the mod around Mycelium.** Give feature logic a clear boundary from native platform code, and connect
   the mod's build to a separate Mycelium checkout or fork.
2. **Define the contracts the mod needs.** A contract states a feature's meaning across targets, with laws and tests.
   Reuse an existing one when it is convenient and fits; otherwise build the declaration your feature needs.

Mycelium supplies the shared machinery: target selection from era data, private source imports, bridge selection,
resource generation, Java compatibility gates, packaging, and evidence. Defining a contract is feature work: you
supply its meaning, and the compiler handles the repeated compatibility work around it.

The compiler grows with each mod that builds on it. Add missing contracts or target realizations alongside the feature
that needs them, in your own checkout or private fork. Upstream issues and pull requests are optional ways to
contribute. The [building guide](docs/building-with-mycelium.md) explains the loop; the
[contract index](contracts/README.md) contains examples, not prerequisites.

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

## Build and verify Mycelium

Use a JDK 17 or newer to run Gradle. Bridge linkage needs a toolchain as new as the newest era; Gradle provisions
toolchains when needed. The first build may download dependencies, JDKs, and pinned game archives.

```bash
./gradlew check
./gradlew myceliumVerify -Pmycelium.contracts=world.copy@1
bash run-contracts.sh
```

On Windows, use `.\gradlew.bat` and `.\run-contracts.ps1`. The standalone runners check the harnesses only. Each reports
the reference result and whether each broken implementation was `CAUGHT` for its intended label.

| | |
|---|---|
| Status | Contracts: 21 packages, 326 rejection fixtures. `./gradlew check` runs the harnesses, era gates, bridge linkage, and repository checks. [Open work](docs/STATUS.md), [history](CHANGELOG.md). |
| Java | Contract models and harness: Java 17 source, checked on every era's Java. Gradle plugin: Java 17 bytecode. See [era gates](docs/eras.md). |
| Targets | `eras/minecraft.json` records version, Java, loader, naming, and realization data. An era row describes a target; it does not prove a mod or bridge works there. |
| Dependencies | Contracts and harness use the JDK only. The plugin uses Gson and ASM; era gates resolve JvmDowngrader and JDKs as needed. Bridge linkage reads pinned game archives. |

### Task index

The [plugin reference](docs/plugin.md#tasks) describes each task and its inputs.

| Purpose | Tasks |
|---|---|
| Select and import | `myceliumDeriveContracts`, `myceliumImport`, `myceliumBridgeImport` |
| Check a mod | `myceliumVerify`, `myceliumBridges`, `myceliumUsage`, `myceliumIsolation`, `myceliumPurity`, `myceliumPureCompile`, `myceliumEraJava<N>`, `myceliumEras`, `myceliumSurface`, `myceliumRange`, `myceliumLinkage`, `myceliumCapsule` |
| Generate and package | `myceliumPresentation`, `myceliumPresentationLock`, `myceliumPackMeta`, `myceliumPureShip`, `myceliumDowngradeJar`, `myceliumEvidence` |
| Maintain this repository | `myceliumRepository`, `myceliumBridgeLinkage`, `myceliumRecordProofs`, `myceliumRefreshVersions`, `myceliumRefreshNames` |

## Evidence and limits

A passing harness proves the model and its rejection matrix. Linkage checks resolve references against pinned data.
Neither proves behavior in a running game: that evidence comes from a consumer's game tests on each target.
Source portability also does not establish save-file or protocol compatibility. Features that need native code retain
the platform's full capabilities.

## Documentation

| Read | For |
|---|---|
| [Architecture](docs/architecture.md) | The layers, principles, and what each check proves |
| [Building with Mycelium](docs/building-with-mycelium.md) | Crossing a version or loader divide |
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
