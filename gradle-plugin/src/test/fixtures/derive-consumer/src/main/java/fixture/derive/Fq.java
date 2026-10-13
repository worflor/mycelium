package fixture.derive;

/** A fully qualified reference and nothing else: no import names the contract (random.seeded@1). */
public class Fq {
    public fixture.derive.mycelium.random_seeded.v1.model.SeededRandom random;
    // fixture.derive.mycelium.data_catalog.v1.model.X is only in a comment: a comment is not a use
}
