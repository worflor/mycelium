package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The small JSON this contract reads and writes. Reading accepts objects, arrays, strings, booleans and integers (no
 * null, no fraction, no exponent: nothing here is ever a float), refuses duplicate keys and trailing content, and names
 * the line and column of a fault. Writing is canonical (law S6): keys sorted, two-space indentation, one element per
 * line, line feeds for line ends, and every character outside printable ASCII escaped, so the same value is the same
 * bytes on every platform, locale and default charset.
 *
 * <p>Values are {@code Map<String,Object>} (in source order when read), {@code List<Object>}, {@code String},
 * {@code Long} and {@code Boolean}.
 */
public final class Json {
    /** A fault in JSON text, with where it is. */
    public static final class SyntaxException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SyntaxException(String message) {
            super(message);
        }
    }

    private static final int MAX_DEPTH = 64;
    private static final String HEX = "0123456789abcdef";

    private Json() {
    }

    // --- reading ---------------------------------------------------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skip();
        Object value = p.value(0);
        p.skip();
        if (p.i != text.length()) {
            throw p.error("unexpected content after the value");
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        SyntaxException error(String what) {
            int line = 1;
            int col = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new SyntaxException("line " + line + ", column " + col + ": " + what);
        }

        void skip() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested deeper than " + MAX_DEPTH);
            }
            if (i >= s.length()) {
                throw error("the text ends where a value should be");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object(depth);
            }
            if (c == '[') {
                return array(depth);
            }
            if (c == '"') {
                return string();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return number();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                throw error("null is not used here; leave the field out");
            }
            throw error("unexpected character '" + c + "'");
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> out = new LinkedHashMap<>();
            i++;
            skip();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return out;
            }
            while (true) {
                skip();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw error("an object key (a string) is expected");
                }
                String key = string();
                if (out.containsKey(key)) {
                    throw error("the key \"" + key + "\" appears twice");
                }
                skip();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw error("':' is expected after the key \"" + key + "\"");
                }
                i++;
                skip();
                out.put(key, value(depth + 1));
                skip();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return out;
                }
                throw error("',' or '}' is expected");
            }
        }

        List<Object> array(int depth) {
            List<Object> out = new ArrayList<>();
            i++;
            skip();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return out;
            }
            while (true) {
                skip();
                out.add(value(depth + 1));
                skip();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                    return out;
                }
                throw error("',' or ']' is expected");
            }
        }

        String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) {
                    throw error("a string is not closed");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c < 0x20) {
                    i--;
                    throw error("a control character inside a string must be escaped");
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw error("a string is not closed");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("a unicode escape needs four hex digits");
                        }
                        int v = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(s.charAt(i + k), 16);
                            if (d < 0) {
                                throw error("a unicode escape needs four hex digits");
                            }
                            v = v * 16 + d;
                        }
                        i += 4;
                        b.append((char) v);
                    }
                    default -> {
                        i--;
                        throw error("'\\" + e + "' is not an escape");
                    }
                }
            }
        }

        Long number() {
            int start = i;
            if (s.charAt(i) == '-') {
                i++;
            }
            int digits = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            if (i == digits) {
                throw error("a number needs digits");
            }
            if (s.charAt(digits) == '0' && i - digits > 1) {
                i = digits;
                throw error("a number has no leading zero");
            }
            if (i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                throw error("only whole numbers are used here");
            }
            try {
                return Long.valueOf(Long.parseLong(s.substring(start, i)));
            } catch (NumberFormatException e) {
                i = start;
                throw error("the number does not fit 64 bits");
            }
        }
    }

    // --- writing ---------------------------------------------------------------------------------------------------

    /** Canonical text: sorted keys, two-space indent, one element per line, a final newline. */
    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        pretty(b, value, 0);
        return b.append('\n').toString();
    }

    /** One line, keys sorted, {@code ": "} and {@code ", "} between: how a lock entry is written. */
    public static String writeLine(Object value) {
        StringBuilder b = new StringBuilder();
        line(b, value);
        return b.toString();
    }

    /** Keys in the order a writer must use: the order of their UTF-16 code units. */
    public static List<String> sortedKeys(Map<?, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String)) {
                throw new IllegalArgumentException("a JSON key is a string, not " + e.getKey());
            }
            sorted.put((String) e.getKey(), e.getValue());
        }
        return new ArrayList<>(sorted.keySet());
    }

    private static void pretty(StringBuilder b, Object v, int depth) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            List<String> keys = sortedKeys(m);
            for (int k = 0; k < keys.size(); k++) {
                indent(b, depth + 1);
                quote(b, keys.get(k));
                b.append(": ");
                pretty(b, m.get(keys.get(k)), depth + 1);
                b.append(k + 1 < keys.size() ? ",\n" : "\n");
            }
            indent(b, depth);
            b.append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append("[\n");
            for (int k = 0; k < list.size(); k++) {
                indent(b, depth + 1);
                pretty(b, list.get(k), depth + 1);
                b.append(k + 1 < list.size() ? ",\n" : "\n");
            }
            indent(b, depth);
            b.append(']');
        } else {
            scalar(b, v);
        }
    }

    private static void line(StringBuilder b, Object v) {
        if (v instanceof Map<?, ?> m) {
            b.append('{');
            List<String> keys = sortedKeys(m);
            for (int k = 0; k < keys.size(); k++) {
                if (k > 0) {
                    b.append(", ");
                }
                quote(b, keys.get(k));
                b.append(": ");
                line(b, m.get(keys.get(k)));
            }
            b.append('}');
        } else if (v instanceof List<?> list) {
            b.append('[');
            for (int k = 0; k < list.size(); k++) {
                if (k > 0) {
                    b.append(", ");
                }
                line(b, list.get(k));
            }
            b.append(']');
        } else {
            scalar(b, v);
        }
    }

    private static void scalar(StringBuilder b, Object v) {
        if (v instanceof String s) {
            quote(b, s);
        } else if (v instanceof Boolean flag) {
            b.append(flag.booleanValue() ? "true" : "false");
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            b.append(((Number) v).longValue());
        } else {
            throw new IllegalArgumentException("not a JSON value this contract writes: " + v);
        }
    }

    private static void indent(StringBuilder b, int depth) {
        for (int k = 0; k < depth; k++) {
            b.append("  ");
        }
    }

    /** A string literal: printable ASCII as is, everything else as a lower-case unicode escape. */
    public static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        b.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            b.append(HEX.charAt((c >> shift) & 0xf));
                        }
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
