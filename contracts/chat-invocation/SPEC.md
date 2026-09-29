# chat.invocation@1

Turning public chat into private requests without listening to it. A server-side assistant is addressed in chat
(`@mod what's the tps`), and a good answer often needs what was said just before. The obvious implementations get one
of two things wrong: they forward chat to something off-server so it can decide what is addressed to it (a listener),
or they keep everything said and hand all of it over on the first question (a recorder). This contract is the gate
between the two. Every chat line passes through it; only an invocation leaves, carrying a bounded, recent, opt-out-
respecting snapshot of what came before it.

Written for Recon (`dev.woflo.recon`, the `@mod` server agent). The laws are the product's "not creepy" requirement
stated so a harness can hold an implementation to it.

## Model

- `Line(principal, text, at)`: one chat line. `at` is a caller-supplied time in milliseconds (the model reads no clock).
- `Rules(prefixes, maxQueryChars, windowLines, windowMs, burst, refillMs)`: the trigger words, the longest query
  accepted, how many earlier lines a snapshot may hold and how old they may be, and a token bucket per principal
  (`burst` invocations at once, one more every `refillMs`). `Rules.defaults()` is `@mod`, 256 chars, 12 lines,
  2 minutes, 3 at once, one per 10 s.
- `Triggers.query(rules, text)`: the query an addressed line carries, `""` for a bare trigger, `null` when the line is
  not addressed at all.
- `Gate`: `offer(line)` for every chat line, `optOut(principal, out)`, and `readyInMs(principal, now)`: how long until
  that principal's next addressed line would be admitted (0 when it would be now, `Gate.UNKNOWN` (-1) from a gate
  that cannot say). Asking spends nothing and changes nothing.
- `Outcome`: `Passed` (not an invocation; nothing leaves), `Committed(Invocation)`, `Rejected(principal, reason)` with
  `Reason` one of `EMPTY_QUERY`, `TOO_LONG`, `RATE_LIMITED`.
- `Invocation(principal, query, at, snapshot)`.
- `ChatGate`: the reference.

A line is addressed when, after leading whitespace, it starts with a trigger (ASCII case-insensitive) followed by the
end of the line, whitespace, `,` or `:`. `@modpack`, `hi @mod` and `x@mod.com` are not addressed.

## Laws

| Law | Label | Statement |
|---|---|---|
| L1 | `L1:non-invocation-never-delivered` | A line that is not addressed yields `Passed`, and `Passed` carries nothing |
| L1 | `L1:prefix-needs-a-boundary` | A word that merely begins with a trigger is not addressed |
| L2 | `L2:snapshot-bounded` | A snapshot holds at most `windowLines` lines, and none when `windowLines` is 0 |
| L3 | `L3:snapshot-fresh` | A snapshot holds no line older than `windowMs` at the invocation's time, and none from after it |
| L4 | `L4:invocations-private` | An invocation (committed or rejected) never appears in any later snapshot |
| L5 | `L5:rate-bounded` | Over any span of `T` ms one principal has at most `burst + T / refillMs` invocations committed (seeded, 3000 lines) |
| L6 | `L6:opt-out-honoured` | Once a principal opts out, none of their lines, earlier or later, appear in any snapshot, until they opt back in (and then only later lines) |
| L7 | `L7:no-silent-truncation` | A query longer than `maxQueryChars` is rejected `TOO_LONG`, never shortened and committed |
| L8 | `L8:empty-is-rejected` | A bare trigger is rejected `EMPTY_QUERY`: zero effect is a rejection, never a receipt |

## Positive obligations

| Obligation | Label | Statement |
|---|---|---|
| P1 | `P1:invocation-delivered` | An addressed line with a query, under budget, is committed with exactly that query (trimmed) |
| P2 | `P2:snapshot-in-order` | The snapshot is exactly the most recent eligible lines, oldest first |
| P3 | `P3:under-budget-admitted` | A principal under budget is admitted, and is admitted again once the bucket refills |
| P4 | `P4:ready-in-is-exact` | `readyInMs(p, t)` is 0 exactly when an addressed line from `p` at `t` is admitted; otherwise it is the exact wait: a line at `t + readyInMs - 1` is still `RATE_LIMITED`, one at `t + readyInMs` is admitted; a principal never seen is ready at once; asking twice answers the same (seeded, five bucket shapes, two principals, 200 steps each) |

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `NoOpGate` | Never commits anything: private by doing nothing | `P1:invocation-delivered` |
| `LeakyGate` | Commits every line (a listener) | `L1:non-invocation-never-delivered` |
| `EagerGate` | `startsWith` without a boundary: `@modpack` is a question | `L1:prefix-needs-a-boundary` |
| `UnboundedGate` | Keeps every line (a recorder) | `L2:snapshot-bounded` |
| `StaleGate` | Ignores age | `L3:snapshot-fresh` |
| `EchoGate` | Records invocations into the window like any line | `L4:invocations-private` |
| `GreedyGate` | No rate limit | `L5:rate-bounded` |
| `DeafGate` | Ignores opt-out | `L6:opt-out-honoured` |
| `TruncatingGate` | Cuts a long query to fit | `L7:no-silent-truncation` |
| `ShuffledGate` | Snapshot newest first | `P2:snapshot-in-order` |
| `StingyGate` | A bucket that never refills | `P3:under-budget-admitted` |
| `MuteWaitGate` | Rate-limits correctly but always says the wait is 0 | `P4:ready-in-is-exact` |
| `EarlyWaitGate` | Says the wait is one millisecond too short | `P4:ready-in-is-exact` |

## Edge semantics

- `readyInMs` answers for the gate's own bucket, so two correct gates may give different waits for the same history
  (a token bucket refills one token at the next refill instant; a sliding log waits for its oldest admission to age
  out). P4 holds each gate to its own answer, never to another's. `now` is clamped like an offered line's time.
- Times are trusted to be non-decreasing per gate; a line with an earlier `at` than the last is treated as arriving at
  the last time (no bucket credit from a clock that ran backwards).
- A rejected invocation still spends nothing from the bucket except `RATE_LIMITED`, which spends nothing by definition.
- The snapshot includes the invoker's own earlier lines.
- Opting out drops the principal's lines already held; opting back in does not restore them.

## Using it

Put every chat line through one `Gate` on the server thread, before the line is broadcast. `Passed` lines broadcast as
usual and nothing else happens. A `Committed` or `Rejected` line is a request to the assistant: do not broadcast it
(the question is the asker's), deliver the invocation, and tell the asker about a rejection. Nothing else in the mod
may read chat.

## Versioning

`readyInMs` was added to `@1` on 2026-09-29, from Recon's need to tell a rate-limited asker when to ask again
(before, it could only say "one every 10s", which is wrong for a bucket half-way to its next refill). It is a
`default` method (`CONTRACT-PACKAGE.md` rule 12): every existing implementer still compiles, every existing label and
outcome is unchanged, so it stays `@1`. The default returns `Gate.UNKNOWN`, not 0: 0 would claim "ask now", which is
a wrong answer, and the honest default is "cannot say". A gate that leaves it unimplemented keeps L1 to P3 and fails
P4; a caller treats a negative answer as "no figure" and falls back to wording that gives none. Making the method
required would have been `@2`.

Additive changes (a new `Reason`, a new trigger boundary character) stay in `@1` only if every existing label holds.
Renaming a label, changing the addressing rule or the snapshot's order is `@2`.

## Not claimed

That a loader's chat event reaches the gate before the line is broadcast, or at all, on any target. That is the
provider's evidence. Nothing here says who may ask what. Authorisation is the assistant's business, after delivery.
