# chunk.hold@1

Keeping places loaded for several holders over an engine that does not count them. Minecraft keeps one load ticket per
chunk, type and level, not one per holder. 26.3's `TicketStorage` matches tickets on type and level alone, and 1.21.1's
`DistanceManager` on type, level and key. Taking a ticket twice keeps one ticket; dropping it once drops it for
everyone. So two systems that each hold a chunk take each other's hold away the moment either lets go, and writes into
that chunk fall into nothing.


## Model

- `Tickets<K>`: the engine's side, `take(place)` and `drop(place)`. `DedupedTickets` is the engine as it is: a set, and
  a count of takes.
- `Keeper<K, O>`: what the laws govern. `hold`, `release`, `releaseAll` (an owner holds until it lets go), `lease` and
  `expire` (held until a time unless asked for again; nobody has to let go of it), and `renew` (every held place's
  ticket taken again, so its timeout starts over).
- `Holds<K, O>`: the ledger. Each mutation answers whether the engine must act.
- `LedgerKeeper`: the reference, a `Holds` in front of a `Tickets`.

## Laws

| Law | Statement |
|---|---|
| H1 | Across any interleaving of holds, releases, releases-of-everything, leases and expiries (seeded, 4000 steps over 4 places and 3 holders), a place has the engine's ticket exactly while something holds it |
| H2 | Two owners hold a place and one lets go: the place keeps its ticket. The last to let go drops it |
| H3 | Letting go of what you do not hold drops nothing, including a place that is only leased |
| H4 | An owner letting go of everything drops exactly the places only it held; places others hold, or leased, keep theirs |
| H5 | A lease holds until its time and lapses then, by itself. Asked for again it is extended, never shortened |
| H6 | Renewing takes every held place's ticket again, owned or leased, and no place that is no longer held |

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `EngineDirectKeeper` | The engine alone: every release drops the ticket (what Somnium did) | H2 |
| `NeverDropsKeeper` | A ledger that never drops: nothing it held ever unloads | H1 |
| `StrangerDropsKeeper` | Anyone's release clears the place, owner or not | H3 |
| `EternalLeaseKeeper` | Leases never lapse | H5 |
| `ShortLeaseKeeper` | A lease asked for again is replaced, so a shorter ask cuts it short | H5 |
| `RenewsOwnersOnlyKeeper` | Renewal forgets leases, so a lease's ticket times out under it | H6 |
| `ReleaseAllOverreachKeeper` | Letting go of everything drops every place the owner touched, shared or leased ones too | H4 |
| `NoOpKeeper` | Keeps nothing: takes no ticket, drops none | H1 |

`NoOpKeeper` breaks no law about letting go, since it never drops anything wrongly. It is in the matrix because this is
a model contract: the laws govern a type, and a type that does nothing must still fail one (CONTRACT-PACKAGE.md).

## Using it

The engine adapter is two calls: vanilla's add and remove for one ticket type at one level. Use a ticket type of your
own, never one vanilla or another mod also takes (`PORTAL`, `FORCED`): the ledger counts only its own holders, and an
outside holder of the same type and level shares the one ticket, so a real portal going out takes your hold with it.
Renew every held place more often than the ticket type's timeout, and clear the ledger when the server stops, since the
tickets go with it. Take every ticket of that type through the ledger: a single raw add or remove elsewhere brings the
bug back.

Not covered: ticket levels as a range (a hold at radius 2 and another at radius 1 are different places here),
simulation versus loading, persistence across restarts (a dream's holds never need it). Add them as a minor version
when a consumer needs them.
