package mycelium.chat_invocation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The gate's settings (SPEC.md). Triggers are compared ASCII case-insensitively. A token bucket per principal allows
 * {@code burst} invocations at once and one more every {@code refillMs}.
 */
public record Rules(List<String> prefixes, int maxQueryChars, int windowLines, long windowMs, int burst, long refillMs) {
    public Rules {
        if (prefixes == null || prefixes.isEmpty()) {
            throw new IllegalArgumentException("at least one trigger");
        }
        for (String p : prefixes) {
            if (p == null || p.trim().isEmpty() || !p.equals(p.trim()) || p.indexOf(' ') >= 0) {
                throw new IllegalArgumentException("a trigger is one non-empty word: [" + p + "]");
            }
        }
        if (maxQueryChars < 1 || windowLines < 0 || windowMs < 0 || burst < 1 || refillMs < 1) {
            throw new IllegalArgumentException("maxQueryChars>=1, windowLines>=0, windowMs>=0, burst>=1, refillMs>=1");
        }
        prefixes = Collections.unmodifiableList(new ArrayList<>(prefixes));
    }

    /** {@code @mod}, 256 chars, 12 lines, 2 minutes, 3 at once, one more every 10 s. */
    public static Rules defaults() {
        return new Rules(Collections.singletonList("@mod"), 256, 12, 120_000L, 3, 10_000L);
    }

    public Rules withWindow(int lines, long ms) {
        return new Rules(prefixes, maxQueryChars, lines, ms, burst, refillMs);
    }

    public Rules withBucket(int burstSize, long refill) {
        return new Rules(prefixes, maxQueryChars, windowLines, windowMs, burstSize, refill);
    }

    public Rules withMaxQuery(int chars) {
        return new Rules(prefixes, chars, windowLines, windowMs, burst, refillMs);
    }

    public Rules withPrefixes(List<String> triggers) {
        return new Rules(triggers, maxQueryChars, windowLines, windowMs, burst, refillMs);
    }
}
