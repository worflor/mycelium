package mycelium.state_lifecycle.v1.fixtures;

/** Stands in for the platform's runtime object (a player, an entity): identity by reference, a name for reports. */
public final class FakeHost {
    private final String name;

    public FakeHost(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
