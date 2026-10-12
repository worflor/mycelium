package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The consumer's committed record of which legacy code each selection of each item has (law S3). Codes are assigned
 * once and kept forever: a new selection takes the next code above every code the item ever had, a selection a program
 * no longer produces keeps its entry (its code then draws the base model), and nothing is renumbered or reused. The
 * lock is data in the consumer's sources; the build reads it and never writes it.
 */
public final class Lock {
    /** The largest code: floats count whole numbers exactly up to here. */
    public static final int MAX_CODE = 1 << 24;

    /** A selection's code. {@code key} is the model reference the selection draws. */
    public record Entry(int code, String key) {
        public Entry {
            if (code < 1 || code > MAX_CODE) {
                throw new IllegalArgumentException("a code is a whole number from 1 to " + MAX_CODE + ", not " + code);
            }
            if (!Ids.resource(key)) {
                throw new IllegalArgumentException("'" + key + "' is not a model reference");
            }
        }
    }

    private static final Lock EMPTY = new Lock(new TreeMap<String, List<Entry>>());

    private final Map<String, List<Entry>> items;

    private Lock(Map<String, List<Entry>> items) {
        this.items = items;
    }

    public static Lock empty() {
        return EMPTY;
    }

    /** The item's entries, ascending by code. */
    public List<Entry> entries(String item) {
        List<Entry> e = items.get(item);
        return e == null ? Collections.<Entry>emptyList() : e;
    }

    public List<String> items() {
        return Collections.unmodifiableList(new ArrayList<>(items.keySet()));
    }

    /** The code the item's selection has, or {@code null}. */
    public Integer codeOf(String item, String key) {
        for (Entry e : entries(item)) {
            if (e.key().equals(key)) {
                return Integer.valueOf(e.code());
            }
        }
        return null;
    }

    /** The code a new selection of the item would take: one above every code it ever had. */
    public int next(String item) {
        List<Entry> e = entries(item);
        return e.isEmpty() ? 1 : e.get(e.size() - 1).code() + 1;
    }

    /**
     * The entries that would give each of {@code keys} (in the order given, none of them in the lock yet) the next
     * codes. A lock out of codes is an {@link IllegalStateException}.
     */
    public List<Entry> allocate(String item, List<String> keys) {
        List<Entry> out = new ArrayList<>();
        int code = next(item);
        for (String key : keys) {
            if (codeOf(item, key) != null) {
                throw new IllegalArgumentException("the lock already has a code for " + key);
            }
            if (code > MAX_CODE) {
                throw new IllegalStateException("item " + item + " has used every code up to " + MAX_CODE);
            }
            out.add(new Entry(code++, key));
        }
        return out;
    }

    /** This lock with the entries added to the item. Codes and keys stay unique; the item's entries stay ascending. */
    public Lock plus(String item, List<Entry> added) {
        Map<String, List<Entry>> copy = new TreeMap<>();
        for (Map.Entry<String, List<Entry>> e : items.entrySet()) {
            copy.put(e.getKey(), e.getValue());
        }
        List<Entry> list = new ArrayList<>(entries(item));
        for (Entry e : added) {
            for (Entry have : list) {
                if (have.code() == e.code()) {
                    throw new IllegalArgumentException("code " + e.code() + " of " + item + " is already " + have.key());
                }
                if (have.key().equals(e.key())) {
                    throw new IllegalArgumentException(e.key() + " of " + item + " already has code " + have.code());
                }
            }
            list.add(e);
        }
        list.sort((a, b) -> Integer.compare(a.code(), b.code()));
        if (!list.isEmpty()) {
            copy.put(item, Collections.unmodifiableList(list));
        }
        return new Lock(copy);
    }

    /** This lock with lines (as {@link #line} writes them, or as a diagnostic lists them) added to the item. */
    public Lock apply(String item, List<String> lines) {
        List<Entry> added = new ArrayList<>();
        for (String text : lines) {
            String t = text.trim();
            if (t.endsWith(",")) {
                t = t.substring(0, t.length() - 1);
            }
            Object tree = Json.parse(t);
            Entry e = entry(tree);
            if (e == null) {
                throw new IllegalArgumentException("not a lock line: " + text);
            }
            added.add(e);
        }
        return plus(item, added);
    }

    /** One entry as a lock file lists it. */
    public static String line(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", Long.valueOf(e.code()));
        m.put("key", e.key());
        return Json.writeLine(m);
    }

    private static Entry entry(Object tree) {
        if (tree instanceof Map<?, ?> m && m.size() == 2 && m.get("code") instanceof Long && m.get("key") instanceof String) {
            long code = ((Long) m.get("code")).longValue();
            if (code < 1 || code > MAX_CODE) {
                return null;
            }
            try {
                return new Entry((int) code, (String) m.get("key"));
            } catch (IllegalArgumentException bad) {
                return null;
            }
        }
        return null;
    }

    /** The lock as its file: one entry per line, so that adding a selection adds a line. */
    public String toJson() {
        StringBuilder b = new StringBuilder("{\n");
        if (items.isEmpty()) {
            b.append("  \"items\": {},\n");
        } else {
            b.append("  \"items\": {\n");
            int n = 0;
            for (Map.Entry<String, List<Entry>> e : items.entrySet()) {
                b.append("    ");
                Json.quote(b, e.getKey());
                b.append(": [\n");
                List<Entry> list = e.getValue();
                for (int k = 0; k < list.size(); k++) {
                    b.append("      ").append(line(list.get(k))).append(k + 1 < list.size() ? ",\n" : "\n");
                }
                b.append(++n < items.size() ? "    ],\n" : "    ]\n");
            }
            b.append("  },\n");
        }
        return b.append("  \"schema\": 1\n}\n").toString();
    }

    public static Result<Lock> parse(String text) {
        Object tree;
        try {
            tree = Json.parse(text);
        } catch (Json.SyntaxException e) {
            return fail(Diagnostic.Kind.SYNTAX, e.getMessage());
        }
        if (!(tree instanceof Map<?, ?> root) || root.size() != 2 || !Long.valueOf(1).equals(root.get("schema"))
                || !(root.get("items") instanceof Map<?, ?> items)) {
            return fail(Diagnostic.Kind.SCHEMA, "the lock is {\"schema\": 1, \"items\": {\"<item>\": [{\"code\": 1, \"key\": \"<model>\"}]}}");
        }
        Lock lock = empty();
        for (Map.Entry<?, ?> e : items.entrySet()) {
            String item = (String) e.getKey();
            if (!Ids.resource(item) || !(e.getValue() instanceof List<?> list)) {
                return fail(Diagnostic.Kind.SCHEMA, "items." + item + " is a list of {\"code\", \"key\"} entries under an item id");
            }
            List<Entry> entries = new ArrayList<>();
            for (int k = 0; k < list.size(); k++) {
                Entry entry = entry(list.get(k));
                if (entry == null) {
                    return fail(Diagnostic.Kind.SCHEMA, "items." + item + "[" + k + "] is {\"code\": 1 to " + MAX_CODE + ", \"key\": \"<model>\"}");
                }
                entries.add(entry);
            }
            try {
                lock = lock.plus(item, entries);
            } catch (IllegalArgumentException bad) {
                return fail(Diagnostic.Kind.LOCK_INVALID, "items." + item + ": " + bad.getMessage());
            }
        }
        return new Result.Ok<>(lock);
    }

    private static Result<Lock> fail(Diagnostic.Kind kind, String message) {
        return new Result.Failed<>(Collections.singletonList(new Diagnostic(kind, "lock", message)));
    }
}
