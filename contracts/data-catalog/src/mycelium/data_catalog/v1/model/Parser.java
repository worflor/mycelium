package mycelium.data_catalog.v1.model;

/** Turns one source's text into an entry, or throws to reject it (the reason is the exception's message). */
@FunctionalInterface
public interface Parser<T> {
    T parse(String id, String text) throws Exception;
}
