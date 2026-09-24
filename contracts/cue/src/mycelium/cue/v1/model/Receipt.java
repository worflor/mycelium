package mycelium.cue.v1.model;

/** Stand-in for any operation's receipt; a cue is scheduled against one. */
public record Receipt(String operationId) {
    public Receipt {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must be non-blank");
        }
    }
}
