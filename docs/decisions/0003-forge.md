# 0003 — Forge as a third loader

| | |
|---|---|
| Date | 2026-09-24 |
| Status | Accepted |
| Extends | `0001-substrate.md` (Stonecutter, one buildscript per loader) |

## Context

`0001` chose a substrate for Fabric and NeoForge. Forge is still a live loader on every version Mycelium cares about:
the modern versions (Forge 52 for 1.21.1; 64, 65 and 66 for 26.1.2, 26.2 and 26.3, read from
`maven.minecraftforge.net` on 2026-09-24), and nearly every rung below 1.20.2, where it is the only loader besides
Fabric (`docs/eras.md`). Opening consumers to Forge opens the older rungs too, and the integrations that exist there.

## Options considered

1. **ForgeGradle 7** (`net.minecraftforge.gradle` 7.0.40, with `net.minecraftforge.jarjar` 0.2.3). Forge's own
   tooling; builds and runs 1.21.1 and 26.3 from one buildscript.
2. **ModDevGradle's legacy plugin** (`net.neoforged.moddev.legacyforge`). Stops at Forge 1.20.1. Kept for the rungs
   below 1.20.2, when a consumer reaches them.
3. **Unimined.** Not spiked; FG7 covered both modern versions.

## Decision

Option 1 for Forge from 1.20.5 up, option 2 for 1.20.1 and older. A consumer adds `build.forge.gradle.kts` beside its
Fabric and NeoForge buildscripts and lists `forge` in its Stonecutter matches. It applies the Mycelium plugin the same
way as the other two, so contracts, usage, isolation, purity and the era gates run unchanged. The plugin never needed to
know which loader it builds for. That was the point of keeping loader knowledge in the buildscripts (`0001`).

## What Forge needs that the other two do not

- **`net.minecraftforge.gradle.merge-source-sets=true`** (gradle.properties). Otherwise the dev run finds classes and
  resources in separate places and constructs no mod.
- **A `pack.mcmeta`.** Fabric and NeoForge make one for a mod; Forge does not serve a mod's data without it
  ("Missing metadata in pack mod:…"). Generate it for the target version in the Forge build. From 1.21.9 it takes
  `min_format`/`max_format`, not `pack_format`.
- **Mixin: a config and its level.** The dev run is told the config (`--mixin.config=`); the jar names it in its
  manifest (`MixinConfigs`). Forge ships Mixin 0.8.7, whose compatibility levels stop at `JAVA_21`. Its ASM still reads
  26.x's Java 25 classes, so the level is capped at 21 and nothing else changes. No annotation processor: Forge from
  1.20.6 runs on Mojang's names, so there is no refmap.
- **MixinExtras.** Forge 66 (26.x) ships it (0.5.4); Forge 52 (1.21.1) does not, and the mod's jar carries it
  (jar-in-jar under `META-INF/jarjar/`; the newest copy present wins).
- **The evidence, once.** Forge's jar-in-jar task repacks the slim jar, which already carries
  `META-INF/mycelium/evidence.json`, and the plugin adds the evidence to every jar task. The same file arrives twice,
  and Gradle refuses the duplicate. The consumer's Forge buildscript keeps one (`duplicatesStrategy = EXCLUDE` on that
  task); the plugin still knows nothing of loaders.
- **Two event buses.** Forge 52 has one bus, and a listener cancels with `setCanceled`. Forge 66 (EventBus 7) gives each
  event its own `BUS`, and a listener that returns `true` cancels. A consumer can write each handler once as a plain method
  and keeps the two bus bindings apart, so only the bindings know the era.
- **Gametests on 26.x are data.** Forge 66 has no event that registers a test (`ForgeGameTestHooks.gatherTests` exists
  but nothing calls it). A test is vanilla's own: a function in `TEST_FUNCTION` and an instance in the `test_instance`
  data registry. Register functions and supply instances in the test build; check that each case has an instance.

## Evidence

Consumer qualification covered Forge 52.1.16 on 1.21.1 and Forge 66.0.3 on 26.3. These are recorded runs, not a
claim that every target has game evidence.

Consumer qualification covered Forge 52.1.16 on 1.21.1 and Forge 66.0.3 on 26.3. These are recorded runs, not a
claim that every target has game evidence.

Consumer qualification covered Forge 52.1.16 on 1.21.1 and Forge 66.0.3 on 26.3. These are recorded runs, not a
claim that every target has game evidence.

- A third loader exposed test-order dependence in shared world state. Keep fixtures isolated: a new loader
  is a new test order (`docs/eras.md`, "Found in practice").

## Consequences

- Declare optional integrations only for targets on which their dependency is available.
- `eras/minecraft.json` lists Forge on the unobfuscated era (26.x).
- The rungs below 1.20.2 are reachable by the same pattern, with ModDevGradle's legacy plugin in place of FG7.
