# random.keyed@1

Keyed draws and one-way derivations, each in its own domain. `random.seeded@1` gives a stream fixed by its seed, which is
right when the seed may be known; this contract covers the two things it cannot: a value derived from public inputs that
must not run backwards to them, and a draw that whoever knows every public input still cannot forecast. A model contract,
the shape of `contracts/random-seeded/SPEC.md`.


## The problem

- **Derivation with domain separation.** A pure layer derives many values from a few public inputs: a salt from a seed, a
  year's eclipses from the salt and the year, a deal's seed from the salt and a reroll count. Each kind of derivation must
  be its own domain, so no two kinds share a value and adding one never moves another. The encoding of the inputs must be
  injective, so `("ab", "c")` and `("a", "bc")` never collide. And it must be one-way where it is published, so a salt
  sent to every client does not hand them the seed: a SplitMix-style 64-bit mix is invertible.
- **A keyed draw.** Some chance must be unforecastable by whoever knows every public input: a reversal, a nonce. That is
  a MAC over the inputs under a secret the platform makes once, with a secure generator the pure layer may not call, and
  passes in.

Both must mean the same on every Java an era runs on and after every refactor, so the algorithms are owned: SHA-256
(FIPS 180-4) and HMAC (RFC 2104) written out, checked against their published vectors and against the JDK's own
implementation in the harness.

## Model

All in `model/`, plain Java, no Minecraft, no JCA lookup.

- `Sha256.digest(byte[]) -> byte[32]`.
- `HmacSha256.mac(byte[] key, byte[] message) -> byte[32]`, a key of any length (one longer than the 64-byte block is
  hashed first, as RFC 2104 says).
- `Message`: what a derivation or a draw is taken over. Immutable: `Message.of(domain)`, then `.text(String)`,
  `.u64(long)`, `.uuid(UUID)`, `.bytes(byte[])`, each returning a new message, so a prefix can be shared and extended per
  draw. `encoded()` is the injective encoding: the domain's UTF-8 bytes, one `0x00`, then each field as a one-byte tag
  (text `0x01`, u64 `0x02`, uuid `0x03`, bytes `0x04`), a four-byte big-endian length and its bytes: text as UTF-8, a
  `long` as 8 bytes big-endian (an `int` widens; a negative value is its two's complement), a UUID as its most then least
  significant halves, raw bytes as given (copied when the field is added). A message is judged when it is encoded, and
  every derivation and draw encodes first, so an ambiguous message is refused before it draws: an empty domain, a domain
  containing U+0000, and a domain or text that is not well-formed UTF-16. A null anywhere is refused when it is given.
- `Derive.u64(Message) -> long`: the first 8 bytes of `Sha256.digest(message.encoded())`, big-endian. Public and one-way.
  `Derive.unit53(message)` is its unit.
- `KeyedDraw.u64(byte[] key, Message) -> long`: the first 8 bytes of `HmacSha256.mac(key, message.encoded())`,
  big-endian. `KeyedDraw.unit53(key, message)` is its unit. A key is `KeyedDraw.KEY_BYTES` (32) bytes; any other length is
  refused. The key is read, never kept or changed.
- `Unit`: `first64(byte[])` (the first 8 bytes, big-endian), `of(long bits)` (`bits >>> 11`, the high 53 bits, in
  `[0, 2^53)`), `ONE` (`2^53`), and `below(long unit53, int permille)`: exactly `unit53 * 1000 < permille * 2^53`, in
  `long` arithmetic, where both sides fit. A unit outside `[0, 2^53)` or odds outside `[0, 1000]` are refused.
- `KeyedRandom`: the contract as one type (`sha256`, `hmacSha256`, `encode`, `derive`, `draw`, `unit53`, `below`), so the
  harness can hold an implementation to the laws. `ReferenceKeyedRandom` is it over the static faces above, which are the
  definitions and what a consumer calls.

## Choices the model made

- **The algorithms are owned; the JDK is a witness.** `MessageDigest` and `Mac` are provider lookups: the environment
  configures them (a restricted or FIPS-configured runtime may lack or replace one) and they throw checked exceptions. A
  pure layer reads no environment, and a derivation recorded once (a salt, a seed) must reproduce wherever it is computed.
  SHA-256 is about seventy lines; K1 pins it to the published vectors and K2 to the JDK's output, so owning it costs
  nothing in trust.
- **The encoding judges, not the builder.** Every refusal is in `encoded()`, the one place a message becomes bytes, so a
  variant encoding can be held to the same refusals (K3) and no draw ever happens on an ambiguous message.
- **Text is strict UTF-8.** Java's own `getBytes(UTF_8)` writes an unpaired surrogate as `?`, so `"\uD800"` and `"?"`
  would encode alike and the encoding would not be injective over Java strings. Learned while finalising this contract
  for Cartomancy: the draft stated injectivity and an encoding that did not have it. The model refuses ill-formed text.
- **Fixed-size fields carry a length too.** One grammar for every field costs four bytes and lets a reader skip a field
  it does not know.
- **A unit is the high 53 bits,** as `random.seeded@1`'s `nextDouble` takes them, so `unit53 / 2^53` prints the same
  decimal a double would (a diagnostic log showing `draw 0.13 < 0.25`) while the decision itself never uses one.
- **No requirement on `random.seeded@1`.** The two compose in the mod: a seeded stream takes its seed from `Derive.u64`.

## Laws

| Law | Statement | Labels |
|---|---|---|
| K1 | **Owned algorithms.** SHA-256 reproduces the FIPS 180-4 examples (`abc`, the 448-bit and 896-bit messages) with the empty message and the million `a`s NIST publishes beside them, and HMAC-SHA-256 RFC 4231's test cases 1 to 7 (case 5 to its 128 published bits), byte for byte. | `K1:sha256-vectors`, `K1:hmac-rfc4231` |
| K2 | **Agrees with the JDK.** On seeded inputs of every length from 0 to 300 bytes, under keys from 1 to 200 bytes (across the block size), `sha256` equals `MessageDigest("SHA-256")` and `hmacSha256` equals `Mac("HmacSHA256")`. `derive` is the first 8 bytes of the JDK's SHA-256 of the encoding and `draw` of its HMAC under the key. The harness reads the JDK; the model never does. | `K2:matches-jdk`, `K2:derive-is-sha256`, `K2:draw-is-hmac` |
| K3 | **Domains and fields are separate.** Two messages that differ in domain, field count, field kind or field boundary encode to different bytes (exhaustively over every list of up to three fields from a small alphabet in three domains, and on seeded messages); the same fields under two domains differ in encoding, derivation and draw; the encoding is exactly the bytes stated above (golden messages, ASCII and not); an empty domain, a domain with U+0000 and ill-formed text are refused by the encoding, the derivation and the draw. | `K3:injective`, `K3:domains-differ`, `K3:encoding-exact`, `K3:refuses-ambiguous` |
| K4 | **The key matters.** Flipping any one of a key's 256 bits changes the draw (seeded keys and messages); one message under many keys draws as many values, none of them its public derivation; one draw and the public inputs forecast no other draw under the same key (`draw(k, a) ^ draw(k, b)` is never `derive(a) ^ derive(b)`); a key of any length but 32 is refused. | `K4:key-sensitive`, `K4:not-unkeyed`, `K4:one-draw-forecasts-no-other`, `K4:key-length` |
| K5 | **Exact units.** A unit is in `[0, 2^53)` for every 64 bits, edges included; it is the high 53 bits (`bits >>> 11`), and a draw's unit is the high 53 of its MAC's first 8 bytes; at every per mille from 0 to 1000, the units on either side of `permille * 2^53 / 1000` are decided as exact arithmetic decides them, with no float, `below(u, 0)` is never true and `below(u, 1000)` always is; a unit or odds out of range are refused. | `K5:range`, `K5:high-bits`, `K5:edges-exact`, `K5:refuses` |
| K6 | **Uniform.** Over 200,000 keyed draws the top six bits of the unit pass χ² over 64 buckets at p = 0.001, and `below(u, p)` lands within ±1% of `p / 1000` for p in {150, 500, 600}. | `K6:uniform` |
| K7 | **Determinism.** The same inputs give the same outputs however other calls interleave; drawing changes neither the key nor the message; extending a shared prefix never changes it, and changing an array after adding it changes nothing. | `K7:deterministic` |

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `UnkeyedDraw` | Hashes the message without the key: anyone can forecast it | `K4:not-unkeyed` |
| `ShortKeyDraw` | Keys the MAC with only the key's first 16 bytes | `K4:key-sensitive` |
| `KeyXorDraw` | XORs a fold of the key into the public hash: every key bit matters, and one draw gives away every other (a key that leaks) | `K4:one-draw-forecasts-no-other` |
| `LenientKeyDraw` | Draws under a key of any length, an empty one included | `K4:key-length` |
| `ConcatenatingMessage` | Joins fields with no tag or length | `K3:injective` |
| `UntaggedMessage` | Lengths but no tags: the text `a` and the byte `0x61` collide | `K3:injective` |
| `DomainBlindMessage` | Leaves the domain out: the same fields draw the same in every domain | `K3:domains-differ` |
| `LittleEndianMessage` | Lengths little-endian: injective, and every value derived before means something else | `K3:encoding-exact` |
| `LossyTextMessage` | Encodes text and domain with the JDK's lenient UTF-8: an unpaired surrogate becomes `?` | `K3:refuses-ambiguous` |
| `FloatUnit` | Compares `u / 2^53 < p / 1000.0` in doubles | `K5:edges-exact` |
| `LowBitsUnit` | Takes the low 53 bits instead of the high | `K5:high-bits` |
| `SignedShiftUnit` | Shifts with sign (`>>`): half of all units are negative | `K5:range` |
| `LenientUnit` | Clamps odds and units instead of refusing them | `K5:refuses` |
| `StatefulDraw` | Mixes a call counter into the draw: a retried attempt rerolls | `K7:deterministic` |
| `NoOpKeyedRandom` | Every digest, MAC and encoding is zeros, every draw zero | `K1:sha256-vectors` |

`NoOpKeyedRandom` is deterministic and its units are in range; it is in the matrix because a model contract's type that
does nothing must fail one of its laws (`CONTRACT-PACKAGE.md`), and K1 says what a hash must compute.

## Using it


## Not covered

- Protecting a world from its owner: whoever can read the save can read the key.
- Key storage, rotation and generation: the platform's.
- Constant-time comparison: nothing here compares a secret with an attacker's guess.
- More than 64 bits of output, or a stream: a seed for `random.seeded@1` is the way to many values.

## Running the harness

From the Mycelium root: `bash run-contracts.sh random-keyed` (or `.\run-contracts.ps1 random-keyed`), or
`./gradlew myceliumVerify -Pmycelium.contracts=random.keyed@1`. Expected: the reference clean, every matrix entry
`CAUGHT`, exit code 0.
