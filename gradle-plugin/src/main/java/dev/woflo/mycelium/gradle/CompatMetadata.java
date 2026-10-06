package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a built artifact says which Minecraft versions it accepts, read as {@code eras/loaders.json} describes the
 * loader ({@link Loaders.Compat}): the file in the artifact's resources, in its format, at its path. Reading is only as
 * much of each format as the descriptors use (a dotted JSON path with {@code [key=value]} selecting an array element, a
 * TOML table by name with its keys matched, a top-level YAML key); anything else is refused by name rather than guessed,
 * because a misread declaration would be a gate that passes.
 */
public final class CompatMetadata {
    private CompatMetadata() {
    }

    /**
     * What was found: {@code file} is the first of the descriptor's files that exists (null: none does), {@code value}
     * what it declares, in the loader's syntax (null: the file says nothing about Minecraft).
     */
    public record Found(String file, String value) {
    }

    /** Reads {@code compat} from the artifact's resources in {@code resources}. */
    public static Found read(File resources, Loaders.Compat compat) throws IOException {
        for (String name : compat.files()) {
            File file = new File(resources, name);
            if (file.isFile()) {
                String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                return new Found(name, switch (compat.format()) {
                    case "json" -> json(text, compat.path(), name);
                    case "toml" -> toml(text, compat, name);
                    default -> yaml(text, compat.key());
                });
            }
        }
        return new Found(null, null);
    }

    // --- json ------------------------------------------------------------------------------------------------------

    static String json(String text, String path, String file) {
        JsonElement at = new Gson().fromJson(text, JsonElement.class);
        for (String segment : path.split("\\.")) {
            Matcher select = Pattern.compile("([^\\[]+)\\[([^=\\]]+)=([^\\]]+)]").matcher(segment);
            String key = select.matches() ? select.group(1) : segment;
            if (at == null || !at.isJsonObject() || !at.getAsJsonObject().has(key)) {
                return null;
            }
            at = at.getAsJsonObject().get(key);
            if (select.matches()) {
                JsonElement chosen = null;
                if (at.isJsonArray()) {
                    for (JsonElement e : at.getAsJsonArray()) {
                        if (e.isJsonPrimitive() && e.getAsString().equals(select.group(3))) {
                            return "*"; // named, with no version: any
                        }
                        if (e.isJsonObject() && e.getAsJsonObject().has(select.group(2))
                                && e.getAsJsonObject().get(select.group(2)).getAsString().equals(select.group(3))) {
                            chosen = e;
                        }
                    }
                }
                if (chosen == null) {
                    return null;
                }
                at = chosen;
            }
        }
        if (at == null || at.isJsonNull()) {
            return null;
        }
        if (at.isJsonPrimitive()) {
            return at.getAsString();
        }
        if (at.isJsonArray()) {
            JsonArray a = at.getAsJsonArray();
            List<String> alternatives = new ArrayList<>();
            for (JsonElement e : a) {
                if (!e.isJsonPrimitive()) {
                    throw new IllegalStateException(file + ": '" + path + "' is a list that holds more than versions");
                }
                alternatives.add(e.getAsString());
            }
            return String.join(" || ", alternatives);
        }
        throw new IllegalStateException(file + ": '" + path + "' is neither a version nor a list of them");
    }

    // --- toml ------------------------------------------------------------------------------------------------------

    private static final Pattern TABLE = Pattern.compile("^\\s*\\[\\[?\\s*([^\\]]+?)\\s*]]?\\s*(#.*)?$");
    private static final Pattern PAIR = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*=\\s*(.*?)\\s*$");

    static String toml(String text, Loaders.Compat compat, String file) {
        Pattern wanted = Pattern.compile(compat.table().replace(".", "\\.").replace("*", "[^.]+"));
        String table = null;
        Map<String, String> values = new java.util.LinkedHashMap<>();
        List<Map<String, String>> matching = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher t = TABLE.matcher(line);
            if (t.matches()) {
                finish(table, values, wanted, matching);
                table = t.group(1);
                values = new java.util.LinkedHashMap<>();
                continue;
            }
            Matcher p = PAIR.matcher(line);
            if (p.matches() && table != null) {
                values.put(p.group(1), scalar(p.group(2)));
            }
        }
        finish(table, values, wanted, matching);
        for (Map<String, String> one : matching) {
            boolean all = true;
            for (Map.Entry<String, String> w : compat.where().entrySet()) {
                all &= w.getValue().equals(one.get(w.getKey()));
            }
            if (all) {
                return one.get(compat.key());
            }
        }
        return null;
    }

    private static void finish(String table, Map<String, String> values, Pattern wanted, List<Map<String, String>> out) {
        if (table != null && wanted.matcher(table).matches()) {
            out.add(values);
        }
    }

    /** A TOML scalar as text: a basic or literal string without its quotes (and without a trailing comment), else as written. */
    private static String scalar(String raw) {
        if (raw.startsWith("\"")) {
            int end = raw.indexOf('"', 1);
            return end > 0 ? raw.substring(1, end) : raw.substring(1);
        }
        if (raw.startsWith("'")) {
            int end = raw.indexOf('\'', 1);
            return end > 0 ? raw.substring(1, end) : raw.substring(1);
        }
        int hash = raw.indexOf('#');
        return (hash >= 0 ? raw.substring(0, hash) : raw).strip();
    }

    // --- yaml ------------------------------------------------------------------------------------------------------

    static String yaml(String text, String key) {
        Pattern p = Pattern.compile("^" + Pattern.quote(key) + "\\s*:\\s*(.*?)\\s*$");
        for (String line : text.split("\\R")) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                String v = m.group(1);
                if (v.isEmpty() || v.startsWith("#")) {
                    return null;
                }
                return scalar(v);
            }
        }
        return null;
    }
}
