package mycelium.agent_link.v1.model;

/** The world-touching side, supplied by the consumer: the methods it serves and how. */
public interface Handler {
    boolean knows(String method);

    /** Runs a known method. A throw becomes {@code Refused(FAILED)} with the message as detail. */
    String handle(String method, String params) throws Exception;
}
