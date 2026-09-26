package mycelium.agent_link.v1.model;

/** The largest frame accepted, and how many replies to mutating requests are remembered for retries. */
public record Limits(int maxFrameBytes, int rememberReplies) {
    public Limits {
        if (maxFrameBytes < 1 || rememberReplies < 1) {
            throw new IllegalArgumentException("maxFrameBytes >= 1 and rememberReplies >= 1");
        }
    }

    /** 1 MiB frames, 512 remembered replies. */
    public static Limits defaults() {
        return new Limits(1 << 20, 512);
    }
}
