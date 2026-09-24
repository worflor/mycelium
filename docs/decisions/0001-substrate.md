# 0001 — Build substrate for multi-loader, multi-version consumers


## Context

A Mycelium consumer must build one intent for Fabric and NeoForge across at least two Minecraft versions
(currently 26.3 and 1.21.1) with replaceable build-tool providers (`DESIGN.md` §14 "Substrate").

## Options considered

1. **Stonecutter + Modstitch.** Modstitch has no published release; its repository pins Loom 1.15.5 and
   ModDevGradle 2.0.141 and last changed on 2026-04-01. No evidence it supports 26.x or Java 25.
2. **Stonecutter + plain Loom (via `dev.kikugie.loom-back-compat`) + ModDevGradle, one buildscript per
   loader.** This is what the Stonecutter maintainers' own multi-loader template does
   (`codeberg.org/stonecutter/template-multiloader`, updated 2026-08-31). Single source set, loader split by
   `//? if fabric {` comments, a `neoforge-mutex` build service to serialize NeoForge artifact creation.
3. **Unimined.** Not spiked; no need arose.

## Decision

Option 2. Somnium instantiates the official template with versions `1.21.1` and `26.3` × `fabric`,
`neoforge`; kernels are added to `main` via `sourceSets.main.java.srcDir("kernels/src")`; the Mycelium
plugin is applied in both loader buildscripts.

## Evidence

- `./gradlew projects` configures `:1.21.1-fabric`, `:1.21.1-neoforge`, `:26.3-fabric`, `:26.3-neoforge`.
- `:26.3-fabric:build` produces a jar; `:26.3-fabric:runGametest` boots a headless server and runs the probe.
- `:26.3-neoforge:build` produces a jar (ModDevGradle 2.0.147, NeoForge 26.3.0.8-beta).
- Loom 1.17.21 with loom-back-compat 0.4.2 handles the deobfuscated 26.x jar; Java 25 toolchain resolved.

## Consequences

- Build-tool providers remain replaceable: the loader buildscripts are the only files that know about Loom
  or ModDevGradle.
- Modstitch is not used; revisit only if it ships releases that track current Loom/MDG.
- Stonecutter replacements are reversible; source is kept in the `vcsVersion` (newest) form.
- 1.21.1 profiles are still to be evidenced (in progress at the time of writing).
