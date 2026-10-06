package dev.woflo.mycelium.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Minecraft versions a loader's metadata accepts, as the loader writes them ({@code eras/loaders.json}'s
 * {@code syntax}), read as a predicate over versions so a declared range can be held to what the loader will enforce.
 * The other direction, {@link #render}, writes a range in a loader's syntax, so a build puts into the metadata exactly
 * the range it declared and nothing else states it.
 *
 * <ul>
 *   <li>{@code semver} (Fabric, Quilt): comparators joined by spaces mean "and", {@code ||} means "or" (a JSON array of
 *       ranges is read as one); a comparator is {@code >=}, {@code >}, {@code <=}, {@code <}, {@code =} or nothing
 *       (exactly) before a version, {@code ~1.21} ({@code >=1.21 <1.22}), {@code ^1.21} ({@code >=1.21 <2}),
 *       {@code 1.21.x}, {@code 1.x} or {@code *}. A pre-release suffix ({@code -alpha.0}) is ignored.</li>
 *   <li>{@code maven} (Forge, NeoForge): intervals {@code [a, b]}, {@code [a, b)}, {@code (a,]}, {@code [a,)}, {@code (,b]}
 *       and {@code [a]} (exactly), joined by commas. A bare version is read as exactly it, not as Maven's soft preference
 *       (so a range wider than it is found, never hidden).</li>
 *   <li>{@code api-version} (Bukkit): {@code 1.20} means every version from 1.20 on, which is what a server enforces
 *       (it refuses a plugin built for a newer API than its own, and accepts older ones), and every version before 1.13,
 *       which reads no such key. No value at all is a legacy plugin, which every server accepts, and which a server from
 *       1.13 on runs with legacy material support; the caller says so.</li>
 * </ul>
 * Versions order as {@link Eras#compare} orders them, so {@code 1.21} and {@code 1.21.0} are one version.
 */
public final class CompatExpr {
    /** The first version whose server reads a plugin's {@code api-version}; an older one ignores the key. */
    private static final String FLATTENING = "1.13";

    private CompatExpr() {
    }

    /** The versions {@code text} accepts, in {@code syntax}. A text that is not in the syntax is refused, by name. */
    public static Predicate<String> parse(String syntax, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("an empty " + syntax + " range accepts nothing a build could mean");
        }
        return switch (syntax) {
            case "semver" -> semver(text.strip());
            case "maven" -> maven(text.strip());
            case "api-version" -> {
                String floor = text.strip();
                version(floor, syntax);
                yield v -> Eras.compare(v, FLATTENING) < 0 || Eras.compare(v, floor) >= 0;
            }
            default -> throw new IllegalArgumentException("unknown range syntax '" + syntax + "'");
        };
    }

    // --- semver --------------------------------------------------------------------------------------------------

    private static Predicate<String> semver(String text) {
        List<Predicate<String>> alternatives = new ArrayList<>();
        for (String alternative : text.split("\\|\\|")) {
            List<Predicate<String>> terms = new ArrayList<>();
            for (String term : alternative.strip().split("\\s+")) {
                if (!term.isEmpty()) {
                    terms.add(semverTerm(term));
                }
            }
            if (terms.isEmpty()) {
                throw new IllegalArgumentException("an empty alternative in the semver range '" + text + "'");
            }
            alternatives.add(v -> terms.stream().allMatch(t -> t.test(v)));
        }
        return v -> alternatives.stream().anyMatch(a -> a.test(v));
    }

    private static Predicate<String> semverTerm(String term) {
        if (term.equals("*")) {
            return v -> true;
        }
        for (String op : new String[]{">=", "<=", ">", "<", "="}) {
            if (term.startsWith(op)) {
                String bound = version(term.substring(op.length()), "semver");
                return switch (op) {
                    case ">=" -> v -> Eras.compare(v, bound) >= 0;
                    case ">" -> v -> Eras.compare(v, bound) > 0;
                    case "<=" -> v -> Eras.compare(v, bound) <= 0;
                    case "<" -> v -> Eras.compare(v, bound) < 0;
                    default -> v -> Eras.compare(v, bound) == 0;
                };
            }
        }
        if (term.startsWith("~") || term.startsWith("^")) {
            String bound = version(term.substring(1), "semver");
            String upper = term.startsWith("~") ? bumpSecond(bound) : bumpFirst(bound);
            return v -> Eras.compare(v, bound) >= 0 && Eras.compare(v, upper) < 0;
        }
        if (term.endsWith(".x") || term.endsWith(".X")) {
            String line = version(term.substring(0, term.length() - 2), "semver");
            String upper = bumpLast(line);
            return v -> Eras.compare(v, line) >= 0 && Eras.compare(v, upper) < 0;
        }
        String exact = version(term, "semver");
        return v -> Eras.compare(v, exact) == 0;
    }

    // --- maven ---------------------------------------------------------------------------------------------------

    private static Predicate<String> maven(String text) {
        if (!text.startsWith("[") && !text.startsWith("(")) {
            String exact = version(text, "maven");
            return v -> Eras.compare(v, exact) == 0;
        }
        List<Predicate<String>> intervals = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char open = text.charAt(i);
            if (open != '[' && open != '(') {
                throw new IllegalArgumentException("the maven range '" + text + "' has '" + open + "' where an interval begins");
            }
            int close = -1;
            for (int j = i + 1; j < text.length(); j++) {
                if (text.charAt(j) == ']' || text.charAt(j) == ')') {
                    close = j;
                    break;
                }
            }
            if (close < 0) {
                throw new IllegalArgumentException("the maven range '" + text + "' has an interval that never closes");
            }
            intervals.add(interval(open == '[', text.substring(i + 1, close), text.charAt(close) == ']', text));
            i = close + 1;
            while (i < text.length() && (text.charAt(i) == ',' || Character.isWhitespace(text.charAt(i)))) {
                i++;
            }
        }
        return v -> intervals.stream().anyMatch(a -> a.test(v));
    }

    private static Predicate<String> interval(boolean lowClosed, String body, boolean highClosed, String whole) {
        String[] parts = body.split(",", -1);
        if (parts.length == 1) {
            if (!lowClosed || !highClosed) {
                throw new IllegalArgumentException("the maven interval in '" + whole + "' names one version but is not [v]");
            }
            String exact = version(parts[0].strip(), "maven");
            return v -> Eras.compare(v, exact) == 0;
        }
        if (parts.length != 2) {
            throw new IllegalArgumentException("the maven interval in '" + whole + "' has more than two bounds");
        }
        String low = parts[0].isBlank() ? null : version(parts[0].strip(), "maven");
        String high = parts[1].isBlank() ? null : version(parts[1].strip(), "maven");
        if (low == null && high == null) {
            throw new IllegalArgumentException("the maven interval in '" + whole + "' has no bound at all");
        }
        return v -> (low == null || (lowClosed ? Eras.compare(v, low) >= 0 : Eras.compare(v, low) > 0))
                && (high == null || (highClosed ? Eras.compare(v, high) <= 0 : Eras.compare(v, high) < 0));
    }

    // --- shared --------------------------------------------------------------------------------------------------

    /** A version as these syntaxes write it, without a pre-release suffix; refused when it is not one. */
    private static String version(String text, String syntax) {
        String v = text.strip();
        int dash = v.indexOf('-');
        if (dash >= 0) {
            v = v.substring(0, dash);
        }
        if (!v.matches("\\d+(\\.\\d+)*")) {
            throw new IllegalArgumentException("'" + text + "' is not a version in a " + syntax + " range");
        }
        return v;
    }

    /** {@code 1.21.3} as {@code 1.22}: the first version a tilde range excludes. */
    private static String bumpSecond(String v) {
        String[] p = v.split("\\.");
        return p.length == 1 ? (Integer.parseInt(p[0]) + 1) + "" : p[0] + "." + (Integer.parseInt(p[1]) + 1);
    }

    /** {@code 1.21.3} as {@code 2}: the first version a caret range excludes. */
    private static String bumpFirst(String v) {
        return (Integer.parseInt(v.split("\\.")[0]) + 1) + "";
    }

    /** {@code 1.21} as {@code 1.22}, {@code 1} as {@code 2}: the first version a wildcard line excludes. */
    private static String bumpLast(String v) {
        String[] p = v.split("\\.");
        p[p.length - 1] = (Integer.parseInt(p[p.length - 1]) + 1) + "";
        return String.join(".", p);
    }

    // --- the other direction -------------------------------------------------------------------------------------

    /**
     * {@code from} to {@code to} written in {@code syntax}, or {@code null} when the syntax has nothing to say (an
     * {@code api-version} for a range that ends before 1.13: the plugin is a legacy one and says no version). A range that
     * reaches 1.13 or later is written as the first version a server reads the key on at the earliest, {@code 1.13}, which
     * a server before it ignores. A {@code to} that is a line ({@code 26.3.x}) ends before the next line.
     */
    public static String render(String syntax, String from, String to) {
        boolean line = Eras.isLine(to);
        String top = Eras.lineStart(to);
        return switch (syntax) {
            case "semver" -> {
                if (!line && from.equals(to)) {
                    yield from;
                }
                if (line && from.equals(top)) {
                    yield "~" + top;
                }
                yield ">=" + from + " " + (line ? "<" + bumpLast(top) : "<=" + to);
            }
            case "maven" -> {
                if (!line && from.equals(to)) {
                    yield "[" + from + "]";
                }
                yield "[" + from + ", " + (line ? bumpLast(top) + ")" : to + "]");
            }
            case "api-version" -> {
                if (Eras.compare(top, FLATTENING) < 0) {
                    yield null;
                }
                String[] p = (Eras.compare(from, FLATTENING) < 0 ? FLATTENING : from).split("\\.");
                yield p.length < 2 ? null : p[0] + "." + p[1];
            }
            default -> throw new IllegalArgumentException("unknown range syntax '" + syntax + "'");
        };
    }
}
