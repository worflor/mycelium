# data.catalog@1


## Model

- `Source(id, text, packOrder)`: one offered file.
- `Parser<T>`: text to entry, or throw to reject.
- `Snapshot<T>(generation, entries, rejected)`: immutable; entries in id order.
- `Catalog<T>`: `current()` and `reload(sources, parser)`. `ReferenceCatalog` / `DataCatalog.create()`.

## Laws

| Law | Statement |
|---|---|
| T1 | Every valid source loads; entries iterate in id order whatever order they were offered |
| T2 | A bad source is rejected (with its id); every other source still loads |
| T3 | On the same id, a later pack wins, however the list is ordered |
| T4 | While a reload runs, `current()` is still the whole old snapshot: nothing is published half-done |
| T5 | Each reload's generation is higher than the last |
| T6 | A reload replaces everything: an entry whose file is gone is gone; an empty reload empties |
| T7 | Whatever the parser throws (or returns null), reload returns: a rejection, never an escape |
| T8 | A held snapshot is unchanged by later reloads and cannot be changed by its holder |
| T9 | Seeded: loaded = later-pack winners that parse; rejected = winners that don't (two files for one id in one pack: unspecified, excluded) |

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `AbortOnFirstBadCatalog` | The first bad file aborts the reload and escapes | T2 (`T2:reload-threw`) |
| `MergingCatalog` | A reload adds to what was there | T6 |
| `ListOrderWinsCatalog` | The last duplicate in the list wins, not the later pack | T3 |
| `IncrementalCatalog` | Publishes as it goes | T4 |

Not covered (a runtime concern): where sources come from (a resource manager, a folder), and when reload is called.
