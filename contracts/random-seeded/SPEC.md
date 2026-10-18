# random.seeded@1

A deterministic random stream whose algorithm belongs to the mod. The same seed reproduces the same draws across Java
versions and refactors, without a runtime provider lookup. Features compose independent streams by choosing their seeds.


## Model

- `RandomSource`: `nextInt()` and `nextLong()` (the bits), and the bounded draws defined once over them:
  `nextInt(bound)`, `nextInt(origin, bound)`, `nextDouble()`, `nextBoolean()`.
- `SeededRandom(seed)`: the reference. SplitMix64, written out.

## Laws

| Law | Statement |
|---|---|
| T1 | A seed draws what `java.util.SplittableRandom` drew from it, draw for draw (golden checksum over 8 seeds × 5,000 mixed draws) |
| T2 | Two streams from one seed draw the same however they interleave with each other and with other streams: the seed is the whole state |
| T3 | Every bounded draw lands in its range, at the edges too |
| T4 | No value is favoured: at a bound of 3·2²⁹ the lower half takes 50% ± 1% of draws (naive `bits % bound` gives 62.5%); digits pass χ² at p = 0.001 |
| T5 | A bound that is not a range (≤ 0, or an empty range) is refused with `IllegalArgumentException` |

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `JdkRandomSource` | `java.util.Random`: seeded, but a different stream; every old seed would mean a new world | T1 |
| `SharedStateSource` | One state for every stream | T2 |
| `ModuloBiasedSource` | `bits % bound` | T4 |
| `LenientSource` | Answers a nonsense bound instead of refusing | T5 |
| `NoOpSource` | Every draw is zero | T1 |

`NoOpSource` is perfectly reproducible and every bounded draw stays in range. It is in the matrix because this is a
model contract, and a type that does nothing must still fail one of its laws (CONTRACT-PACKAGE.md).

Not covered: splitting streams (`split()`), floats, Gaussians. Add them as a minor version when a consumer needs them.

## Why it's a contract and not a utility

`java.util.SplittableRandom` was the only thing in Mycelium's or Somnium's pure layer that failed on Java 8. Its
Java 17 form moved its methods into an interface that has no Java 8 form. Owning the algorithm is the pure-layer law
in `docs/eras.md`. T1 is what makes owning it safe: every seed recorded before still reproduces.
