package mycelium.chat_invocation.v1.model;

/** One chat line: who said it, what they said, and when (milliseconds, supplied by the caller; the model has no clock). */
public record Line(String principal, String text, long at) {
}
