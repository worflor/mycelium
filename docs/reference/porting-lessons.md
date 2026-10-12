# Porting lessons

What running a whole suite on a version found, when compiling for it had found nothing. Lookup material for anyone
porting a platform layer to another version or loader; the discipline it supports is in `docs/eras.md` (the platform
layer) and `docs/building-with-mycelium.md`.

Compiling for a version proves little. Running a mod's whole gametest suite there finds what compiling never does. These
were learned porting a consumer across 26.x and 1.21.x on three loaders (`CHANGELOG.md` has the dates).

- **A copy that is not a copy.** 1.21.1's `PalettedContainer.copy()` keeps the source's palette and resize handler
  (`SingleValuePalette.copy()` returns itself). A write into the "copy" resizes the original, and the copy's own write
  fails, or crashes the server tick later ("value N is not in the specified inclusive range"). 26.x's is a real copy.
  Copy sections through one function that rebuilds them cell by cell where the platform's copy is shallow.
- **Lambdas have different names in different dev jars.** A mixin that targets a lambda by name
  (`lambda$create$1`) finds it where the runtime is Mojang-named (26.x; NeoForge 1.21.1), but not in Fabric 1.21.1's
  dev jar, where lambdas keep intermediary names (`method_40377`). With `require = 0` the miss is silent. List both
  names, and check with `javap -p`.
  `Position` the first time 1.21.1 ran a trip. A validating model is worth more on the older target, where fewer people
  look.
- **A new loader is a new test order.** Tests that run many at a time on a packed grid share world state. A test of
  shared state passed on two loaders by luck and failed on the third's order, every run. A test of shared state runs off
  the grid.
- **An event that says "loaded" can mean "seen".** Fabric's entity-load hook fires whenever an entity's tracking
  starts, including while the level walks a chunk's entities as the chunk comes into view. Removing an entity there
  breaks the walk and crashes the server. NeoForge and Forge ask before the entity is placed. Work that adds or removes
  entities waits for the tick's end.

The rule that follows: a rung is not ported until its whole suite runs there, not only its build.

