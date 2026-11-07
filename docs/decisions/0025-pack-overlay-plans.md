# Exact-format pack overlay plans

Date: 2026-11-07. Status: accepted.

A vanilla card-pack consumer needed one archive across schema eras. Target-selecting resource directories do not
solve installation: they still produce one archive per target. Hand-written format ranges duplicate the era table
and can silently select the wrong function or item schema.

`myceliumPackOverlays` compiles an explicit exact-target declaration into metadata and per-kind overlay directories.
The existing target-selecting `overlays { }` option keeps its meaning. The planner also has a small standalone Java
entrance for non-Gradle pack builders; it uses the existing Gson dependency and introduces no runtime.

Data and resource formats are separate spaces, so each overlay carries only its own tree. Shared identical assets
can live in the root; version-dependent functions cannot. Equal formats are indistinguishable and require identical
effective content. The consumer assembles and resolves the archive, checks byte agreement and tests the final ZIP
in the game. Planning does not translate schemas or migrate saved objects. The metadata's outer range is not a claim
that unlisted releases were tested.

The schema rules follow Mojang's [1.20.2](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-2)
and [1.21.9](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-9) release notes. Plugin self-tests
exercise mixed/modern metadata, minor versions, equal formats and malformed/unsupported declarations. Consumer
game receipts belong with the consumer.
