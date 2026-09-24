package mycelium.data_catalog.v1.model;

/** A source that did not load, and why. */
public record Rejection(String id, String reason) {
}
