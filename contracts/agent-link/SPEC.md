# agent.link@1

A mod answering requests from an out-of-process agent over a connection that drops. The mod is the side that touches
the world: it runs commands, reads state and sends messages when the agent asks. The connection will drop (the agent
restarts, the server restarts, a socket times out), and the agent will retry what it did not hear back about. Without a
law, a retried `give` gives twice, and a request that arrives before the handshake runs anyway. This contract is the
mod's side of the link: who may ask, what gets refused, what runs at most once, and how the mod reconnects.

Written for Recon (`dev.woflo.recon`), whose mod dials its core and serves the core's requests.

## Model

- `Request(id, method, mutating, bytes, params)`: one inbound call. `mutating` is the caller's declaration that running
  it twice would be wrong. `bytes` is the frame's size on the wire.
- `Reply`: `Answered(id, result, replayed)` or `Refused(id, refusal, detail, replayed)`, with `Refusal` one of `UNAUTHENTICATED`,
  `TOO_LARGE`, `UNKNOWN_METHOD`, `INVALID`, `FAILED` (a recoverable handler failure: the detail is its nonblank message,
  or its fully qualified class name when it has none), `BUSY` (a retry arrived while the first is still running or an
  unrecoverable failure left its outcome indeterminate). The three-argument `Refused` constructor means `replayed = false`.
- `Handler`: `knows(method)`, `handle(method, params)`. The world-touching side, supplied by the consumer.
- `Limits(maxFrameBytes, rememberReplies)`.
- `Link`: `hello(token)`, `receive(request)`, `dropped()`, `remembered()`.
- `Ledger`: what is remembered about mutating requests, apart from the session: `enter(id)` (the remembered reply, or `BUSY` if running/indeterminate, or null and now running), `finish(id, reply)` (store before releasing the claim), `abandon(id)` (release only when retry is known safe), `size()`. `MemoryLedger` is the reference.
- `LinkSession`: the reference. `LinkSession(token, handler, limits)` keeps a ledger of its own; `LinkSession(token, handler, limits, ledger)` remembers in the ledger the caller keeps.
- `Backoff(baseMs, capMs)`: `delay(attempt, u)` for reconnect attempt `attempt >= 0` and a caller-supplied uniform
  `u` in `[0, 1)` (the model draws no randomness; the consumer passes its own).

## Laws

| Law | Label | Statement |
|---|---|---|
| L1 | `L1:at-most-once` | A mutating request whose id was already answered is not run again: the remembered reply comes back with `replayed = true` |
| L2 | `L2:dedup-survives-reconnect` | L1 holds across `dropped()` and a new `hello`: remembering is the session's, not the connection's |
| L3 | `L3:no-work-before-hello` | Before a successful `hello`, after a failed one, and after `dropped()`, every request is refused `UNAUTHENTICATED` and nothing runs |
| L4 | `L4:memory-bounded` | At most `rememberReplies` replies are remembered, the oldest forgotten first |
| L5 | `L5:oversize-refused` | A request larger than `maxFrameBytes` is refused `TOO_LARGE` and does not run |
| L6 | `L6:unknown-refused` | A method the handler does not know is refused `UNKNOWN_METHOD` and does not run; a blank id is refused `INVALID` |
| L1 | `L1:no-concurrent-twin` | A mutating request whose id is still running is refused `BUSY`, not run a second time |
| L7 | `L7:failure-is-remembered` | A mutating request whose handler throws a recoverable `Exception` or `Error` is answered `FAILED`, and a retry gets the same refusal without running again (it may have half-happened). A fatal `ThreadDeath` or `VirtualMachineError` other than `StackOverflowError` propagates without releasing the claim; retries are refused `BUSY` and are not run again because a reply may be impossible to construct safely |
| L8 | `L8:dedup-survives-handover` | Two links over one `Ledger` (a consumer replaced while the server runs): a mutating request the first answered is not run by the second (replayed), one the first is still running or left indeterminate by a fatal error is refused `BUSY` by the second and not run beside it, and one whose handler failed recoverably is not run again |
| L9 | `L9:failure-has-diagnostic` | A handler failure preserves its nonblank message; a null, empty or blank message is replaced by the exception's fully qualified class name. A remembered failure retains that detail across reconnects |
| L10 | `L10:failed-refusal-replayed` | The first recoverable `FAILED` refusal has `replayed = false`; when that mutating request is retried from the ledger after reconnect or handover, the refusal has `replayed = true`. A live `BUSY` refusal is never marked replayed |
| B1 | `B1:delay-within-bounds` | Every delay is at least 1 ms and at most `capMs`, for every attempt and every `u` |
| B2 | `B2:envelope-grows-to-cap` | The largest possible delay never shrinks as attempts grow, and reaches `capMs` |
| B3 | `B3:never-gives-up` | A delay exists for every attempt up to `Integer.MAX_VALUE`, without overflow |

## Positive obligations

| Obligation | Label | Statement |
|---|---|---|
| P1 | `P1:executes-and-answers` | After a good `hello`, a known, in-size request runs exactly once and is answered with the handler's result |
| P2 | `P2:reply-correlates` | Every reply carries its request's id |
| P3 | `P3:reads-are-fresh` | A non-mutating request runs every time it is asked, so a read never returns a remembered answer |
| P4 | `P4:first-retry-prompt` | The first retry waits at most `baseMs` |

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `NoOpLink` | Answers "ok" without running anything | `P1:executes-and-answers` |
| `ForgetfulLink` | Remembers nothing: a retried `give` gives twice | `L1:at-most-once` |
| `RacingLink` | Remembers answers but not what is running: two retries in flight both run | `L1:no-concurrent-twin` |
| `AmnesiacLink` | Forgets on `dropped()`: the retry after a reconnect runs again | `L2:dedup-survives-reconnect` |
| `TrustingLink` | Runs requests without a handshake | `L3:no-work-before-hello` |
| `HoardingLink` | Remembers every reply forever | `L4:memory-bounded` |
| `GluttonLink` | No frame limit | `L5:oversize-refused` |
| `GuessingLink` | Passes unknown methods to the handler | `L6:unknown-refused` |
| `RetryingLink` | Forgets checked failures, so a half-done mutation is run again | `L7:failure-is-remembered` |
| `AbandoningErrorLink` | Abandons after a recoverable `Error`, so the half-done mutation runs again | `L7:failure-is-remembered`, `L8:dedup-survives-handover` |
| `MissingRefusalReplayLink` | Remembers a failed mutation and avoids re-execution but returns its cached refusal with `replayed = false` | `L10:failed-refusal-replayed` |
| `OpaqueFailureLink` | Serializes a missing exception message as "null", or returns an empty diagnostic | `L9:failure-has-diagnostic` |
| `ParochialLink` | Keeps its own memory whatever ledger it is given: the request in flight when its consumer is replaced runs twice | `L8:dedup-survives-handover` |
| `MisroutedLink` | Replies under the wrong id | `P2:reply-correlates` |
| `StaleReadLink` | Remembers reads too | `P3:reads-are-fresh` |
| `OverflowBackoff` | `base << attempt` unguarded: negative delays on long outages | `B3:never-gives-up` |
| `GiveUpBackoff` | Stops (a negative delay) after 20 attempts | `B3:never-gives-up` |
| `UncappedBackoff` | No ceiling | `B1:delay-within-bounds` |
| `SlowStartBackoff` | First retry waits the cap | `P4:first-retry-prompt` |

## Edge semantics

- The token comparison is constant-time in the reference (`MessageDigest.isEqual`). The harness cannot observe timing;
  this is stated, not checked.
- `dropped()` ends authentication and nothing else. Remembered replies outlive it by design (L2).
- The caller decides what is mutating. A caller that marks a `give` as a read gets reads' semantics.
- A request with a remembered id but a different method or parameters still gets the remembered reply: an id names one
  intent. Callers must not reuse ids.
- Recoverable `Exception` and `Error` failures without messages still identify their type (L9). A remembered failure
  retains that detail across reconnects (L9). Learned in Recon's Java 8 source-reading qualification on 2026-10-02: a
  timed-out read reported only "null", concealing the failure that needed diagnosis.
- `ThreadDeath` and `VirtualMachineError` other than `StackOverflowError` are allowed to escape because constructing a
  failure reply may itself be unsafe. Their mutating id remains claimed and yields `BUSY` on retry or handover; it is
  intentionally not made available for replay.
- `abandon(id)` is for a run known not to have started its effect. Once the handler has begun, a throw must either be
  remembered as `FAILED` or leave the id claimed when a fatal error prevents a reply.

## Using it

One `LinkSession` per mod lifetime, not per connection. The transport (a socket, framing, JSON) is the consumer's: parse
a frame into a `Request`, `receive` it, and write the `Reply`. Call `dropped()` whenever the socket closes. Reconnect
with `Backoff`, resetting the attempt count after a successful `hello`. Run world-touching handlers on the server thread
from inside `handle`; `receive` may be called from the connection's thread.

A mod whose own code is replaced while the server runs (a hot-swapped payload) has more than one session in a lifetime,
and a session per replacement forgets exactly what L1 and L2 promise to remember: the agent resends the request that was
in flight across the swap, and the new session runs it again. Keep one `Ledger` outside what is replaced, made of
nothing the replacement redefines (JDK types only, when the replaced code is loaded by a class loader of its own), and
build each new session over it (L8). A consumer that never replaces itself needs no ledger of its own.

Ids must be unique across the caller's restarts, not only within one of its processes. The session remembers answers
for the mod's lifetime (L2), so an agent whose id counter starts over when it restarts gets its new requests answered
with old replies, and they don't run. Give ids a per-boot prefix (a random boot id, or the start time). Learned in
Recon's first live deployment (2026-09-26), where the session replayed exactly as L1 says it must.

## Versioning

New `Refusal` values and new methods on `Link` with defaults stay in `@1`. Changing what is remembered, or when, is `@2`.

## Not claimed

Anything about the transport, the wire format, or whether a handler's effect happened in the world. The reply says what
the handler returned.
