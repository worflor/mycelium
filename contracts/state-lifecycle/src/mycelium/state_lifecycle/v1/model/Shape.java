package mycelium.state_lifecycle.v1.model;

/**
 * What a transition does to its host, which fixes what a slot's policy can mean there. The platform's transitions each
 * have one ({@link Transitions#platform()}); a consumer's transition declares its own.
 */
public enum Shape {
    /** The host's state is written to storage; the object stays. */
    SAVES,
    /** The same object, the same incarnation (death, dimension change, a consumer's drift). */
    STAYS,
    /** A host comes to exist with a new incarnation, from storage (login, load). */
    BEGINS,
    /** The incarnation ends (logout, unload, remove, server stop). */
    ENDS,
    /** A new incarnation of the same host takes over from the old, which ends (respawn). */
    SUCCEEDS,
    /** A second host, with an identity of its own, is created from a first, which lives on (clone, duplication). */
    FORKS;

    /** True for the shapes that create a host: the only ones where COPY, REGENERATE and EXCLUSIVE mean anything. */
    public boolean createsHost() {
        return this == FORKS;
    }

    /** Whether a slot of this durability may declare a policy of this kind on a transition of this shape. */
    public boolean admits(Durability durability, Policy.Kind kind) {
        switch (this) {
            case SAVES:
                return kind == Policy.Kind.KEEP;
            case STAYS:
            case SUCCEEDS:
                return kind == Policy.Kind.KEEP || kind == Policy.Kind.DROP || kind == Policy.Kind.RESET
                        || kind == Policy.Kind.MIGRATE;
            case BEGINS:
                if (durability == Durability.SESSION) {
                    return kind == Policy.Kind.KEEP || kind == Policy.Kind.RESET;
                }
                return kind == Policy.Kind.KEEP || kind == Policy.Kind.DROP || kind == Policy.Kind.RESET
                        || kind == Policy.Kind.MIGRATE;
            case ENDS:
                if (durability == Durability.SESSION) {
                    return kind == Policy.Kind.DROP;
                }
                return kind == Policy.Kind.KEEP || kind == Policy.Kind.DROP;
            case FORKS:
                return kind != Policy.Kind.KEEP;
            default:
                throw new IllegalStateException(this.name());
        }
    }
}
