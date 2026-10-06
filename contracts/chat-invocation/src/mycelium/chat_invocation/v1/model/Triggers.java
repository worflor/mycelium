package mycelium.chat_invocation.v1.model;

/** The addressing rule (SPEC.md): a trigger at the start of the line, then the end, whitespace, ',' or ':'. */
public final class Triggers {
    private Triggers() {
    }

    /** An addressed line: the trigger it began with, spelled as the rules spell it, and the query after it. */
    public record Match(String trigger, String query) {
    }

    /** The query an addressed line carries ({@code ""} for a bare trigger), or {@code null} when it is not addressed. */
    public static String query(Rules rules, String text) {
        Match match = match(rules, text);
        return match == null ? null : match.query();
    }

    /**
     * The first trigger, in the rules' order, that addresses the line, with the query after it; {@code null} when no
     * trigger does. The trigger is the rules' own spelling, not the case the line was typed in.
     */
    public static Match match(Rules rules, String text) {
        if (text == null) {
            return null;
        }
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        for (String prefix : rules.prefixes()) {
            int end = start + prefix.length();
            if (end > text.length() || !text.regionMatches(true, start, prefix, 0, prefix.length())) {
                continue;
            }
            if (end < text.length()) {
                char next = text.charAt(end);
                if (!Character.isWhitespace(next) && next != ',' && next != ':') {
                    continue;
                }
                end++;
            }
            return new Match(prefix, text.substring(end).trim());
        }
        return null;
    }
}
