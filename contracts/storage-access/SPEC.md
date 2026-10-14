# storage.access@1 — Contract Specification


---

## 1. Scope

A machine that reaches a neighbouring block's storage meets a divide at once: each loader keeps block storage its own way.
Fabric looks stores up through its transfer API, items as variants and fluids in droplets of 81,000 to a bucket; NeoForge and
Forge look them up as block capabilities, fluids in millibuckets; and vanilla's `Container` and `WorldlyContainer`, with
slots each face may or may not reach, lie under all of them. Left to each mod the same mistakes recur: an insertion that
survives while the extraction it was paired with is released, a fluid amount rounded at the seam so a droplet is made or
lost, a face that reaches a slot its side forbids (a hopper under a furnace taking its fuel), a handle kept after its store
was broken, a simulation that mutates, a stack past its size.

- One **store** is the storage of one kind (items, or fluids) at one block, as one **face** of that block reaches it. A
  **port** finds it by level, position and face.
- A **move** takes goods of one kind from one store and gives them to another: simulated, then committed or released inside
  one nested scope of the caller's `inventory.transfer@1` transaction, so it moves goods from one store to the other or moves
  nothing.
- Amounts are in one declared unit per kind: items in items, fluids in **thousandths of a bucket**. A loader's own fluid unit
  is data (`eras/loaders.json`, §8); the seam converts exactly, and refuses a remainder rather than round it.
- A store that vanishes inside a move releases it.
- Out of scope: what a store is in the game (the seam's), which store a machine should reach (the caller's), item
  transformation, voiding stores, aliasing between positions (a double chest's halves, a multiblock's ports), and change
  notifications from outside the transaction (the game's own signals: a comparator update).

## 2. Types

| Type | Meaning | Invariants |
|---|---|---|
| `Face` | The face of the store's own block that is touched: a machine east of a chest touches its `EAST` | One of six |
| `Position`, `StoreAddress` | `(level, position, face)`; the level is an opaque token (the dimension's id) | Level non-blank; `sameStoreAs` compares level and position, not face |
| `Kind` | `ITEM` (counted in items) or `FLUID` (in thousandths of a bucket) | A store is of one kind, as every loader's lookup is |
| `Goods` | `(kind, variant)`: the variant is `inventory.transfer@1`'s `ResourceKey`, an opaque identity token (an item with its components) | Equality is identity |
| `Holding` | `(goods, amount)` | `amount >= 1` |
| `FluidUnit` | A unit by how many make a bucket; `DECLARED` is thousandths. `declaredStep`/`nativeStep` are the smallest amounts both say; `toNative`, `toDeclared` are exact or empty; `wholeDeclared` is the whole thousandths inside a native amount | `perBucket >= 1` |
| `Store` | One kind at one face, in declared units: `address`, `kind`, `present`, `contents`, `simulateExtract`, `simulateInsert`, `extract(…, tx)`, `insert(…, tx)` | S2 to S7 |
| `StoragePort` | `kinds()` the platform reaches; `find(address, kind)` | S1 |
| `SlottedContainer` | A vanilla-shaped item container: slots, a limit per slot and goods, `slotsFor(face)`, `canPlace`, `canTake`, a raw `put` | |
| `Sided` | The reference adapter: `Sided.face(container, address)` is the `Store` that face reaches | S2, S3, S4, S6, S7 |
| `NativeFluidStore` | A fluid store at one face in the loader's unit (`unit()`) | As a store, in native units |
| `Fluids` | The reference seam: `Fluids.face(native)` is the `Store` in thousandths | S5 |
| `Move` | `(from, to, goods, amount)` | |
| `MoveReceipt` | `(from, to, goods, requested, moved)` | `1 <= moved <= requested` |
| `MoveOutcome` | `Moved(receipt)` / `Rejected(reason)` / `Unsupported(detail)` / `Stale(vanished)` | Sealed |
| `Reason` | `ZERO_REQUEST`, `SAME_STORE`, `NO_SOURCE`, `NO_DESTINATION`, `EMPTY_SOURCE`, `FULL_DESTINATION`, `EXTRACTION_FAILED`, `INSERTION_FAILED`, `TRANSFER_FAILED` | This contract's own |
| `MoveFeedback` | Presentation and bookkeeping | Receives each receipt once, at the outermost commit |
| `MoveExecutor` | `move(port, move, tx, feedback)`; the reference is `StorageAccess.move` | |
| `Stores` | `Stores.backend(store)`: a store as an `inventory.transfer@1` `FlowBackend` | Declares `ROLLBACK_PARTICIPATION` and `IDENTITY_PRESERVING`, which S3 and `Goods` equality earn |

## 3. Nesting in inventory.transfer@1

This contract requires `inventory.transfer@1` and imports its model; it copies no transaction.

- **The transaction is `FlowTransaction`.** Every mutation a store makes, `extract` or `insert`, takes the caller's
  `FlowTransaction` and registers its exact undo there before it writes. Nothing a store does is final before the outermost
  `FlowTransaction` commits, and an abort anywhere above it runs every undo in reverse order (S3).
- **A move is one nested scope.** `StorageAccess.move` opens `tx.openNested()` for each attempt, extracts and inserts in it,
  and either commits it (its undos and deferred actions are adopted by `tx`, provisional until the outermost commit, as
  `FlowTransaction` makes every nested commit) or aborts it (released: both stores as before). It never opens an outermost
  transaction of its own and never commits the caller's.
- **The receipt waits for the outermost commit.** A `Moved` outcome is returned inside the transaction; the receipt's
  feedback is registered with `tx.onOuterCommit`, so it runs only if every scope above commits, and never after an abort.
- **The fluid seam nests too.** `Fluids.face` runs each native move in its own `openNested()` scope, so a remainder it
  refuses is released without touching the move's other work.
- **Several moves, and moves beside other work, share the caller's outermost transaction.** A machine operation that also takes
  a seed from a store and plants it, or a gathering that removes an item entity the mod keeps as an `inventory.transfer@1`
  provider, runs under one outermost `FlowTransaction`: everything commits or nothing does. `Stores.backend(store)` makes a
  store a provider for `Transfer.execute`, for pairing a store with any other provider.

## 4. Outcomes

- **`Moved(receipt)`**: exactly `receipt.moved()` left the source and arrived at the destination, in the caller's
  transaction; provisional until its outermost commit. `moved >= 1`; moving nothing is never a receipt.
- **`Rejected(reason)`**: nothing moved, whatever the caller then does with its transaction.
- **`Unsupported(detail)`**: the port reaches no storage of the goods' kind at all (a platform with no fluid API). Decided
  before anything is looked up.
- **`Stale(vanished)`**: a store the move touched vanished inside it; the move's scope was released and nothing moved.
- An undo that throws (a store that cannot restore itself) propagates: the outcome is unconfirmed, which is never an
  ordinary `Rejected` (`DESIGN.md` §7), and the caller aborts its transaction.

## 5. Laws (safety)

| Law | Statement | Labels |
|---|---|---|
| S1 | **Found by level, position and face.** `find` returns the store standing at the address's own level and position, answering for the face asked (its `address()` carries that face); nothing where no store of the kind stands, or where one has vanished; and a store placed again at an address is found fresh, never as a handle kept from before. | `S1:face-kept`, `S1:level-kept`, `S1:absent`, `S1:fresh-after-replace` |
| S2 | **Reading and simulating are pure.** `contents`, `simulateExtract` and `simulateInsert` change nothing anywhere. | `S2:simulate-mutates-nothing` |
| S3 | **A release restores.** Every `extract` and `insert` registers its exact undo on the transaction: after an abort every slot and tank is exactly as before, slot by slot, not merely in total. | `S3:abort-restores` |
| S4 | **A face reaches what its side allows.** Through a face a store gives only from the slots `slotsFor(face)` lists and only what `canTake` allows, and takes only into those slots and only what `canPlace` allows; `contents` shows only what the face may take. | `S4:takes-only-allowed`, `S4:places-only-allowed` |
| S5 | **Fluids cross the seam exactly.** A fluid amount moves in whole thousandths of a bucket: what a native store reports is read as the whole thousandths inside it, and a native move that is not a whole number of steps is refused (released), never rounded. Measured in the loaders' own units, no droplet or millibucket is created or destroyed by any move. | `S5:native-conserved`, `S5:remainder-refused` |
| S6 | **A vanished store is out of reach.** A handle whose store has vanished is not present, shows nothing, simulates nothing and moves nothing. A move during which a store it touches vanishes is `Stale`, and its scope is released: every store, the vanished one included, holds what it held before the move, and no feedback is given. | `S6:vanished-moves-nothing`, `S6:vanish-aborts` |
| S7 | **Nothing past its limit.** A slot never holds more of a goods than its limit for it (the container's stack limit and the goods' own, the smaller), and a store never accepts more than it can hold. | `S7:within-capacity` |
| A1 | **Both or neither.** After the outermost transaction ends, either the extraction and the matching insertion both survive (the source fell by exactly the receipt's amount, the destination rose by it, measured on each store in its own unit, and nothing else changed), or neither does. A destination that takes less than it promised leaves the rest with the source, and the receipt says what it took. | `A1:moved-both`, `A1:neither-unless-moved`, `A1:remainder-released` |
| F1 | **Finality.** No feedback before the outermost commit or after an abort; after the outermost commit, each receipt exactly once. | `F1:feedback-at-outer-commit`, `F1:feedback-once` |
| E1 | **Refusals say why and leave nothing.** Each edge of §7 gives its outcome, and a refused move changes nothing and gives no feedback even when the caller commits. | `E1:outcome` |

`U1:exact` labels the assertions of `FluidUnit`'s own arithmetic (T15), a final model class no implementation replaces
(`unmutated.json`).

## 6. Positive obligations

- **P1**: under the fixture assumptions (stores that keep what they promise, no concurrent change), a move commits
  `min(requested, what the source's face gives, what the destination's face takes)`: twelve iron asked of a chest of twenty
  into a slot with room for seven moves seven; half a bucket from a Fabric tank to a Forge tank moves five hundred
  thousandths; a whole bucket from a full cauldron moves. Returning less is incorrect, not conservative. Label
  `P1:moved-min`.
- **P2 (anti-evasion)**: an admissible move of items and one of fluids, on a port that declares both kinds, is never
  `Unsupported` or `Rejected`, and moves. Label `P2:no-evasion`.

## 7. Edge semantics

Checks run in this order, all before anything is mutated: the amount; one store twice; the kind against the port; the source;
the destination; then the simulations.

| Condition | Outcome |
|---|---|
| `amount <= 0` | `Rejected(ZERO_REQUEST)` |
| both addresses at one level and position, whatever their faces | `Rejected(SAME_STORE)` |
| the port reaches no storage of the goods' kind | `Unsupported` |
| no store of the kind at the source (nothing there, a chest asked for fluid, a vanished store) | `Rejected(NO_SOURCE)` |
| the same at the destination | `Rejected(NO_DESTINATION)` |
| the source's face gives none (fluids: not one whole thousandth) | `Rejected(EMPTY_SOURCE)` |
| the destination's face takes none | `Rejected(FULL_DESTINATION)` |
| the source gives nothing it promised, gives more than asked, or (fluids) gives a remainder the seam refuses | the scope released; `Rejected(EXTRACTION_FAILED)` |
| the destination takes all that was extracted | the scope committed into the caller's; `Moved(extracted)` |
| the destination takes some but less (it promised more room than it had) | the scope released; one more attempt for what it took; `Moved` of that if it then takes it all |
| the destination takes less again | the scope released; `Rejected(TRANSFER_FAILED)` |
| the destination takes nothing, or (fluids) takes a remainder the seam refuses | the scope released; `Rejected(INSERTION_FAILED)` |
| a store is not present after the extraction or after the insertion | the scope released; `Stale(its address)` |
| a nested scope commits and an outer one aborts | nothing remains; no feedback (F1, A1) |

A move releases and retries rather than returning a remainder through the source's own insertion (as `Transfer.execute`
does), because a sided source may not take back through its face what it gave through it: a furnace's output slot takes
nothing from below.

## 8. Units, and where each loader's is kept

The contract declares one unit per kind: an item is one item; a fluid is a thousandth of a bucket (`FluidUnit.DECLARED`). A
loader's own fluid unit is a fact about the loader, so it is data: each loader's row in `eras/loaders.json` has a `fluid`
object, `unit`, `perBucket` and `source` (Fabric and Quilt `droplet`, 81,000 a bucket; Forge and NeoForge `millibucket`,
1,000 a bucket; a loader with no row keeps no fluid storage a mod can reach). The plugin reads it strictly (`Loaders`) and a
buildscript reads it as `mycelium.fluidPerBucket(loader)`. No unit is written in this package's model.

The conversion is exact in steps: `declaredStep` thousandths are `nativeStep` native units (1 and 81 for droplets, 1 and 1
for millibuckets). A request converts to the native amount of its whole steps; a native amount reported converts to the whole
thousandths inside it, the rest staying where it is (100 droplets show as one thousandth and 19 droplets stay); and a native
move that is not a whole number of steps is released and moves nothing. A cauldron holds a bucket in three levels of 27,000
droplets, and a third of a bucket is not a whole number of thousandths: a machine can pour a full cauldron (1,000 thousandths) and
never one level of it.

## 9. Traits and admission

A port's `kinds()`, a store's promise in `simulateInsert` and a native store's unit are claims, not evidence: the harness
trusts none of them and holds every implementation to executed fixtures, and an overpromising store (Fabric's
`supportsInsertion` is true "otherwise or in doubt") is a provider behaviour every executor must handle (T11), not a broken
implementation. A loader's seam earns its claims on a game target only through the mod's game tests: one move of items and one
of fluids per loader, a sided vanilla container, a cauldron, a store broken mid-stroke (`DESIGN.md` §8.1).

## 10. Fixtures (normative)

The reference world is a `MemoryWorld` (`fixtures/MemoryWorld.java`) of levels and positions, each holding a
`MemoryContainer` (a chest; a furnace whose top reaches the input, whose sides reach the fuel and take only fuel, whose bottom
reaches the output and the fuel slot's empty buckets; an offering plate whose slot 0 takes from any face and gives nothing and
whose slots 1 to 9 give to any face and take nothing) or a `MemoryTank` (Fabric droplets, Forge millibuckets, or a cauldron of
three levels). Like the loaders' own objects they do not check whether they still stand; their handles must. `MemoryPort`
builds stores through the reference adapters. Ender pearls stack to sixteen.

| Fixture | Setup | Expectation |
|---|---|---|
| T1 items | chest of 20 iron and 5 gold; a one-slot chest holding 57 iron; 12 iron | `Moved(7)`; 13 and 64 iron, gold untouched; no feedback before the commit, one after |
| T2 fluids | Fabric tank of a bucket; empty Forge tank; 500, then 1,500 back | `Moved(500)`: 40,500 droplets and 500 millibuckets; back `Moved(500)`: 81,000 and 0; the world's water unchanged |
| T3 anti-evasion | admissible items and admissible fluids | each `Moved` |
| T4 edges | zero; a chest's top to its own north face; an empty position as source and as destination; fluid asked of a chest; a source in the nether where nothing stands; an empty source; a destination full of other goods; fluids on an items-only port | the §7 outcome each; the world and the feedback untouched after the caller commits |
| T5 lookup | a furnace's top face; one chest in each level at one position; an empty position; a chest broken and another placed | the top face answers as the top and fills the input; each level's own chest; nothing where nothing stands; the new chest, present, after the replace |
| T6 sided | a furnace holding input, fuel and output; an offering plate holding its offering and a shelf item | from below: only the output shows and moves; from a side: only fuel enters; from the top: input enters, the output untouched; the plate: shows and gives only its shelf, takes only more of its offering; moving the offering out is `EMPTY_SOURCE` |
| T7 capacity | 40 pearls into two empty slots | simulation 32; `Moved(32)`; no slot past 16 |
| T8 pure | every face and kind of a chest, furnace, plate, Fabric tank and cauldron: contents and both simulations of every goods | the world unchanged |
| T9 release | a chest of `[–, –, 10 iron, 3 gold]`: extract 4 iron and insert 20 gold, abort; a tank: extract 300, abort | the slots exactly as before; the tank at 81,000 |
| T10 both or neither | a move in a nested scope that commits, under an outermost transaction that aborts; then one that commits | after the abort: 20 and 57, no feedback; after the commit: 13 and 64, feedback once, none before |
| T11 remainder | a destination with room for 4 that promises 7; one with no room that promises 7 | `Moved(4)`: source 16, destination 64, the receipt and the feedback 4; `Rejected(INSERTION_FAILED)` and the world untouched |
| T12 exact units | 100 droplets to a Forge tank; 60 droplets; 667 thousandths from a full cauldron; 1,000 from it | `Moved(1)`, 19 droplets stay; `EMPTY_SOURCE`, 60 stay; not moved, the cauldron full; `Moved(1000)`, the cauldron empty; the world's water unchanged throughout |
| T13 vanish | handles kept to a chest and a tank that are then broken; moves whose destination, whose source, and whose fluid destination vanish on their first write | the handles show, simulate and move nothing and the broken stores keep their goods; each move `Stale`, the world as before, no feedback |
| T14 nested in transfer | two stores as `Stores.backend` providers moved by `Transfer.execute` | `Committed(7)`: 13 and 64, one receipt |
| T15 units | `FluidUnit` of droplets, millibuckets and thirds of a bucket | exact conversions, empty remainders, round trips |
| T16 seeded | 40 seeds of 12 steps: two levels of chests, a furnace, a plate, Fabric and Forge tanks and a cauldron, randomly filled; random moves of random goods between random faces, nested or not, committed or aborted, with stores that vanish on their next write and are replaced | after every step: every goods' world total unchanged (fluids exact); a committed move changed source and destination by its receipt in their own units and fed it back once; anything else changed nothing and fed nothing back; the plate's offering never fell and its shelf never rose; the furnace's output never rose; no slot past its limit |

**Rejection matrix**

| Implementation | Violates | Caught by |
|---|---|---|
| `NoOpPort` (finds every store, reports honestly, moves nothing) | P1 | `P1:moved-min` |
| `TimidExecutor` (handles items only: fluids reported unsupported on a port that reaches them) | P2 | `P2:no-evasion` |
| `SelfMoveExecutor` (never asks whether both addresses are one store) | E1 | `E1:outcome` |
| `MirroredFacePort` (passes the toucher's facing, not the face touched) | S1 | `S1:face-kept` |
| `LevelBlindPort` (looks every position up in the first level that has a block there) | S1 | `S1:level-kept` |
| `PhantomPort` (wraps an empty position as an empty store) | S1 | `S1:absent` |
| `CachingPort` (keeps handles by address and hands them out again) | S1 | `S1:fresh-after-replace` |
| `CommittingSimulationAdapter` (simulates an extraction by doing it in a transaction it commits) | S2 | `S2:simulate-mutates-nothing` |
| `CompactingUndoAdapter` (an extraction's undo refills the first slot that takes the goods) | S3 | `S3:abort-restores` |
| `SidelessAdapter` (every slot for every face, no per-goods check) | S4 | `S4:takes-only-allowed` |
| `SlotListOnlyAdapter` (the face's slots, but neither `canPlace` nor `canTake`) | S4 | `S4:places-only-allowed` |
| `RoundingFluidSeam` (converts to the nearest thousandth: 60 droplets move as 81) | S5 | `S5:native-conserved` |
| `FlooringFluidSeam` (keeps an inexact native move and reports the whole thousandths in it) | S5 | `S5:remainder-refused` |
| `StaleHandleAdapter` (never asks whether its container still stands) | S6 | `S6:vanished-moves-nothing` |
| `UnwatchfulExecutor` (never asks whether a store vanished before committing) | S6 | `S6:vanish-aborts` |
| `OverstackingAdapter` (the container's stack limit, not the goods' own) | S7 | `S7:within-capacity` |
| `NonParticipatingInsertAdapter` (inserts without registering an undo: the insertion survives the extraction's release) | A1 | `A1:neither-unless-moved` |
| `SplitCommitExecutor` (inserts in a transaction of its own, committed at once) | A1 | `A1:neither-unless-moved` |
| `ClaimingExecutor` (the receipt says what the simulations planned, not what the destination took) | A1 | `A1:moved-both` |
| `NoReleaseExecutor` (commits when the destination took less than was extracted) | A1 | `A1:remainder-released` |
| `EarlyFeedbackExecutor` (feedback as soon as the move holds) | F1 | `F1:feedback-at-outer-commit` |
| `SilentExecutor` (never calls feedback) | F1 | `F1:feedback-once` |

The broken ports, adapters, seams and executors are the reference with one flaw each (`fixtures/PortVariants.java`,
`fixtures/SidedVariants.java`, `fixtures/FluidVariants.java`, `fixtures/ExecutorVariants.java`); `fixtures/Providers.java`
holds the provider behaviours that are not bugs (an overpromising store, an items-only platform). Labels are the law labels
above; see `harness/StorageAccessContractTests.java`.

## 11. Using it

A mod imports the model under its own namespace (`mycelium { contracts("storage.access@1") }`, which brings
`inventory.transfer@1` with it); with the example namespace the packages are `example.mycelium.storage_access.v1.model`
and `example.mycelium.flow.v1.model`.

**The seam, per loader.** The platform layer implements `StoragePort` once per loader, and nothing else:

- `kinds()`: `ITEM` and `FLUID` where the loader has a fluid storage API (`eras/loaders.json`, `fluid`), else `ITEM`.
- `find(address, kind)`: resolve `address.level()` to the server's level, `address.position()` to a block position, and
  look the store up with `address.face()` as the side touched (Fabric: `ItemStorage.SIDED` or `FluidStorage.SIDED`
  `.find(level, pos, direction)`; NeoForge: its item or fluid block capability queried with the position and that side,
  through a capability cache whose invalidation ends the handle; Forge: the block entity's capability for that side).
  Return empty where nothing answers; never cache a handle past its store (S1, S6).
- Items: a `Store` over the loader's item storage, whose `present()` is false once the block entity is removed or replaced.
  Fabric's `Storage<ItemVariant>` already applies a `WorldlyContainer`'s sides; where only a vanilla `Container` is found,
  wrap it as a `SlottedContainer` and use `Sided.face(container, address)`. The mod's own sided blocks (an offering plate, a
  machine buffer) may be served the same way. `Goods.variant()` is the seam's token for an item with its components; the seam
  keeps the map from token to native variant.
- Fluids: a `NativeFluidStore` over the loader's fluid storage in its own unit, `unit()` being `new FluidUnit(perBucket)`
  with the loader's own constant (Fabric's `FluidConstants.BUCKET`, NeoForge's and Forge's `FluidType.BUCKET_VOLUME`), then
  `Fluids.face(native)`. The mod's game test holds that constant to `mycelium.fluidPerBucket(loader)`.
- Each `extract` and `insert` registers on the `FlowTransaction` it is given an undo that restores exactly what it changed
  (S3). The reference adapters show the shape: the undo is registered before the write and puts each slot back as it was,
  which is sound on every loader for a vanilla `Container`. Bridging a loader's own transactions (Fabric's `Transaction`,
  NeoForge's) to `FlowTransaction`'s scopes, so that releasing a nested scope releases exactly its native work, is the seam's
  design and is proven by the mod's game tests; a native change the seam cannot undo exactly is not one it may make. Side
  effects (a `setChanged`, a neighbour update) wait for the outermost commit, as the loaders' own transaction rules ask.

**The caller.** A stroke is:

```java
FlowTransaction tx = FlowTransaction.open();
MoveOutcome out = StorageAccess.move(port, new Move(from, to, goods, amount), tx, receipt -> stock.apply(receipt));
// further work of the same stroke (a seed planted, a hand emptied) under tx
tx.commit();   // or tx.abort(): nothing moved, nothing fed back
```

`Moved` carries the receipt; `Rejected`, `Unsupported` and `Stale` say why nothing moved (an adapter mapping: `NO_SOURCE`
is no store, `EMPTY_SOURCE` nothing to take, `FULL_DESTINATION` no room). A store's `contents()` is the inspection a planner
reads (what a face may take), and `simulateInsert` the room a destination offers. Work that pairs a store with something that
is not a store (an item entity gathered, drops collected) calls the store's `insert` or `extract` with the same transaction,
or `Transfer.execute` with `Stores.backend(store)` and the other provider.

## 12. Versioning and packaging

This is `storage.access@1`. A new law, label or reason that keeps every existing one stays in `@1` and is noted in
`CHANGELOG.md`; a change to a law's meaning, an outcome or a required method is `@2`. `mycelium.storage_access.v1` is
development-time; consumers import `model` under their own prefix, with `inventory.transfer@1`'s model, and `fixtures` and
`harness` never ship.

## 13. Running the harness

From the Mycelium root: `bash run-contracts.sh storage-access` (or `.\run-contracts.ps1 storage-access`), or
`./gradlew myceliumVerify -Pmycelium.contracts=storage.access@1`. Expected: the reference clean, every matrix entry `CAUGHT`,
exit code 0.

## 14. Explicit non-claims

- No Minecraft or loader code was executed to produce this package. Whether a loader's lookup, sides, units and transactions
  behave as the seam declares is the mod's game-test evidence, per loader.
- A store that vanishes inside a transaction is released by the move it touched; what the game does with a removed block's
  goods (drops them at once, or after the transaction closes) is the platform's. A platform that drops a removed block's
  goods before an enclosing transaction ends must not let that block be removed inside it, which is what the loaders' rule to
  defer side effects to the outermost close already asks.
- A store that vanishes after its move's scope committed but before the caller's outermost commit (by later work of the same
  transaction) is not detected by this contract; a caller that breaks blocks in the same transaction checks the stores it
  moved goods through before committing.
- Aliasing (two positions over one storage, a double chest's halves), voiding stores and concurrent mutation from another
  thread are out of scope.
- The declared unit fixes how amounts cross the seam, not how a mod shows them.
