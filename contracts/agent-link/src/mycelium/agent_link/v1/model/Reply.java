package mycelium.agent_link.v1.model;

/** The link's answer to one request (SPEC.md). Every reply carries its request's id (P2). */
public sealed interface Reply permits Reply.Answered, Reply.Refused {
    /** Why a request did not produce an answer. */
    enum Refusal { UNAUTHENTICATED, TOO_LARGE, UNKNOWN_METHOD, INVALID, FAILED, BUSY }

    String id();

    /** The handler's result; {@code replayed} when it is a remembered answer to a retried mutating request. */
    record Answered(String id, String result, boolean replayed) implements Reply {
        public Answered replay() {
            return new Answered(id, result, true);
        }
    }

    /** Not answered, and why. {@code FAILED} means the handler ran and threw: {@code detail} is its message. */
    record Refused(String id, Refusal refusal, String detail) implements Reply {
    }
}
