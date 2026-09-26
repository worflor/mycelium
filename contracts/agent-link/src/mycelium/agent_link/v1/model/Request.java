package mycelium.agent_link.v1.model;

/**
 * One inbound call (SPEC.md). {@code mutating} is the caller's declaration that running it twice would be wrong;
 * {@code bytes} is the frame's size on the wire; {@code params} is opaque to the link.
 */
public record Request(String id, String method, boolean mutating, int bytes, String params) {
}
