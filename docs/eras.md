# Eras: every Minecraft version

A mod built on Mycelium has two layers, and they go back in time differently.

| Layer | What it is | How it reaches an old version |
|---|---|---|
| **Pure** | Contract models, harnesses, a mod's kernels: plain Java, no Minecraft | Written once in modern Java; **downgraded bytecode** runs on each era's Java. Proven by `myceliumEras`. |
| **Platform** | The code that touches Minecraft and a loader | Rewritten where the game changed. **Stonecutter** keeps one source tree across versions; a **provider** per loader. |

The pure layer is proven today for every Java any Minecraft version runs on. The platform layer is proven for the
versions a consumer actually builds (Somnium: 26.3 and 1.21.1, Fabric and NeoForge). Every rung below that is a
port, taken one at a time.

## The pure layer: one gate per Java era

```kotlin
mycelium {
    eras(8, 16, 17, 21, 25)   // every Java a Minecraft version runs on
}
```

`myceliumEras` runs one task per era (`myceliumEraJava8`, `myceliumEraJava16`, ...):
- It takes each contract harness as `myceliumVerify` compiled it (release 17).
- On Java 17 and later, it runs those classes unchanged.
- On older Javas, JvmDowngrader first rewrites them to that era's class version and shades in backports of the
  newer Java APIs.
- Each era runs on a real JVM of that version, a Gradle toolchain provisioned if absent.
- The reference must stay clean and every rejection must still be caught, in every era. `check` depends on it.

**Evidence (2026-09-24):** all 13 contracts pass on Java 8, 16, 17, 21 and 25 (65 runs), every rejection caught.
Somnium's 19 kernel suites, compiled as Java 21, also pass downgraded on Java 8.

### Pure-layer law: own your determinism

The one thing that broke on Java 8 was `java.util.SplittableRandom`. Its Java 17 form moved its methods into the
`RandomGenerator` interface, and the downgrader has no Java 8 form for that. The fix is a law, not a workaround:
- **The pure layer owns any algorithm whose output is part of its meaning.** Seeded randomness is the main one: a seed
  must mean the same world on every version.
- Mycelium's `Seeded` and Somnium's `Rng` are SplitMix64 written out. Each is checked bit-identical to
  `SplittableRandom`: `harness/test/.../SeededIdentity` runs 160,000 live draws against it, and Somnium's `rng` kernel
  test checks a golden checksum. So no existing seed changed.
- Other Java 9+ APIs (`List.of`, records, sealed types, pattern matching, switch expressions) downgrade cleanly. Write
  them freely.

## The platform layer: the map

What a mod has to change between versions, by era. The **Java** column is what `eras(...)` covers.

| Minecraft | Java | Loaders | Names in the code | What changes for a mod |
|---|---|---|---|---|
| 26.x | 25 | Fabric, NeoForge | unobfuscated | Where Somnium lives now. |
| 1.21.2 – 1.21.x | 21 | Fabric, NeoForge | Mojang | Registry and holder changes, item model definitions (1.21.4). |
| 1.20.5 – 1.21.1 | 21 | Fabric, NeoForge | Mojang | **Data components replace item NBT** (1.20.5). Somnium's second target. |
| 1.20.2 – 1.20.4 | 17 | Fabric, NeoForge, Forge | Mojang | Networking rework (configuration phase). NeoForge split from Forge. |
| 1.18 – 1.20.1 | 17 | Fabric, Forge (1.20.1 also NeoForge's fork) | Mojang | World height −64..320 (1.18). 1.20.1 is the largest modded population of its time. |
| 1.17.x | 16 | Fabric, Forge | Mojang | Java 16. Chunk and world-height groundwork. |
| 1.14 – 1.16.5 | 8 | Fabric (from 1.14), Forge | Mojang (from 1.14.4), MCP | Fabric exists. 1.16 adds data-driven dimensions. |
| 1.13 | 8 | Forge, Legacy Fabric | MCP | **The Flattening**: block and item ids become names; data packs begin. |
| 1.8 – 1.12.2 | 8 | Forge, Legacy Fabric | MCP | 1.12.2 is the great modpack era. Block states (1.8). No data packs. |
| 1.0 – 1.7.10 | 8* | Forge (1.7.10: GTNH-era modding), Ornithe | MCP | Numeric ids, metadata. **Mycelium the block enters with Beta 1.9 / 1.0.** |
| Beta, Alpha | 8* | Babric (Beta 1.7.3), Ornithe, ModLoader (historic) | Feather/Ornithe | Everything is hand-rolled: no registries, no events. |

\* These versions shipped for Java 6, but they run on Java 8 today, and so does every modern loader for them.
Java 8 is the floor for all of it.

## How a consumer climbs down, rung by rung

Each rung is a Stonecutter version with its own `deps.*` and loaders. The pure layer comes for free (the eras gate).
The platform layer is ported only where the rung's column changed. Porting order: the changes from the table are
the hard walls, so take them one wall at a time.

1. **1.20.1** (Fabric, Forge): the Java 17 era, the biggest older audience; before data components.
2. **1.18.2**: the start of the new world height.
3. **1.16.5** (Java 8): the loaders' Java 8 era; data-driven dimensions still exist.
4. **1.12.2** (Forge, Legacy Fabric): no data packs; content comes from code.
5. **1.7.10**, then **Beta 1.7.3**: the collector's rungs.

A new provider (Forge, Legacy Fabric, Babric) is another buildscript beside the Fabric and NeoForge ones
(decision `0001-substrate.md`). The contracts do not change.
