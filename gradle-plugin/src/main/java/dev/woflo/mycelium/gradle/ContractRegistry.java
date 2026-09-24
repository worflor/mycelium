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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads {@code contracts/registry.json} from a Mycelium checkout. */
public final class ContractRegistry {
    public record Entry(String id, String path, String pkg, String status) {
        public String slug() {
            return path;
        }

        /** {@code mycelium.world_copy.v1} → {@code world_copy.v1}. */
        public String relativePackage() {
            String prefix = "mycelium.";
            if (!pkg.startsWith(prefix)) {
                throw new IllegalStateException("contract package must start with 'mycelium.': " + pkg);
            }
            return pkg.substring(prefix.length());
        }

        public String runMain() {
            return pkg + ".harness.Run";
        }
    }

    private final Map<String, Entry> byId = new LinkedHashMap<>();

    public static ContractRegistry load(File myceliumRoot) {
        File file = new File(myceliumRoot, "contracts/registry.json");
        if (!file.isFile()) {
            throw new IllegalStateException("Mycelium registry not found: " + file);
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonObject root = new Gson().fromJson(text, JsonObject.class);
            ContractRegistry registry = new ContractRegistry();
            JsonArray contracts = root.getAsJsonArray("contracts");
            for (JsonElement element : contracts) {
                JsonObject o = element.getAsJsonObject();
                Entry entry = new Entry(
                        o.get("id").getAsString(),
                        o.get("path").getAsString(),
                        o.get("package").getAsString(),
                        o.has("status") ? o.get("status").getAsString() : "unknown");
                registry.byId.put(entry.id(), entry);
            }
            return registry;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    public Entry require(String id) {
        Entry entry = byId.get(id);
        if (entry == null) {
            throw new IllegalStateException("unknown Mycelium contract '" + id + "'. Known: " + byId.keySet());
        }
        return entry;
    }

    public List<Entry> all() {
        return new ArrayList<>(byId.values());
    }

    public List<Entry> resolve(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return all();
        }
        List<Entry> out = new ArrayList<>();
        for (String id : ids) {
            out.add(require(id));
        }
        return out;
    }
}
