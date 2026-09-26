package mycelium.agent_link.v1.model;

/**
 * What agent.link@1 governs (SPEC.md): the mod's side of a link to an agent, for the lifetime of the mod, across
 * however many connections come and go.
 */
public interface Link {
    /** The handshake on a new connection; true when the token is the expected one. */
    boolean hello(String token);

    /** One request on the current connection. Never throws; never returns null. */
    Reply receive(Request request);

    /** The connection closed. Authentication ends; remembered replies do not. */
    void dropped();

    /** How many replies are remembered right now. */
    int remembered();
}
