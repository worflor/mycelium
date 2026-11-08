package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Emits the version facts a datapack generator needs; it does not translate payloads or certify game behaviour. */
public final class DatapackTargetPlan {
    private static final Set<String> DIVIDES = Set.of(
            "text_components", "registry_paths", "recipe_ingredients", "offhand_path", "command_budget",
            "nested_item_count", "function_macros", "recipe_result", "component_removal", "loot_functions",
            "entity_predicates", "predicate_type_key", "item.carrier", "item.presentation");

    private DatapackTargetPlan() { }

    public static JsonObject compile(Eras eras, List<String> releaseVersions, JsonObject request) {
        for (String key : request.keySet()) {
            if (!Set.of("schema", "targets").contains(key)) fail("Unknown declaration field: " + key);
        }
        if (!request.has("schema") || !request.get("schema").isJsonPrimitive()
                || !request.getAsJsonPrimitive("schema").isNumber() || !request.get("schema").toString().equals("1")) {
            fail("Declaration schema must be 1");
        }
        if (!request.has("targets") || !request.get("targets").isJsonArray()
                || request.getAsJsonArray("targets").isEmpty()) fail("At least one exact target is required");

        Set<String> seen = new HashSet<>();
        Set<String> knownVersions = Set.copyOf(releaseVersions);
        List<String> versions = new ArrayList<>();
        for (JsonElement value : request.getAsJsonArray("targets")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) fail("Targets must be strings");
            String version = value.getAsString();
            if (!version.matches("[0-9]+(?:\\.[0-9]+)+") || !seen.add(version)) {
                fail("Duplicate or non-release target: " + version);
            }
            if (!knownVersions.contains(version)) fail("Unknown release target: " + version + " (eras/versions.json)");
            versions.add(version);
        }
        versions.sort(Eras::compare);

        JsonArray targets = new JsonArray();
        for (String version : versions) {
            Eras.Era era;
            Eras.PackFormat format;
            try {
                era = eras.of(version);
                format = eras.packFormatFor(version);
            } catch (IllegalArgumentException e) {
                fail(e.getMessage());
                return new JsonObject(); // unreachable
            }

            JsonObject target = new JsonObject();
            target.addProperty("minecraft", version);
            target.addProperty("java", era.java());
            target.add("data", formatValue(format.data(), Eras.PackFormat.DATA_RANGE_FORMATS_FROM));
            target.add("resource", formatValue(format.resource(), Eras.PackFormat.RANGE_FORMATS_FROM));
            JsonObject realizations = new JsonObject();
            for (String divide : DIVIDES.stream().sorted().toList()) {
                List<Eras.Realization> rows = eras.realizations(divide);
                List<Eras.Realization> matches = rows.stream().filter(row -> row.covers(version)).toList();
                if (matches.size() != 1) {
                    fail("Minecraft " + version + " has " + matches.size() + " rows for realization '" + divide
                            + "' (expected exactly one)");
                }
                Eras.Realization row = matches.get(0);
                String realization = row.realization();
                Set<String> vocabulary = divide.equals("item.carrier")
                        ? Set.of("custom-data", "root-tag", "none")
                        : divide.startsWith("item.") ? Eras.VOCABULARY.get(divide) : Eras.DATAPACK_TARGET_VOCABULARY.get(divide);
                if (vocabulary == null || !vocabulary.contains(realization)) {
                    fail("Minecraft " + version + " has unsupported realization '" + realization + "' for '" + divide + "'");
                }
                if ((divide.equals("command_budget") || divide.equals("offhand_path")) && row.value() == null) {
                    fail("Minecraft " + version + " has no native value for realization '" + divide + "'");
                }
                JsonObject fact = new JsonObject();
                fact.addProperty("realization", realization);
                if (row.value() != null) fact.addProperty("value", row.value());
                realizations.add(divide, fact);
            }
            target.add("realizations", realizations);
            targets.add(target);
        }
        JsonObject plan = new JsonObject();
        plan.addProperty("schema", 1);
        plan.add("targets", targets);
        return plan;
    }

    private static JsonElement formatValue(Eras.Format format, int rangeFormatsFrom) {
        if (format == null) fail("A target has no data-pack format");
        if (format.major() < rangeFormatsFrom) return new com.google.gson.JsonPrimitive(format.major());
        JsonArray pair = new JsonArray();
        pair.add(format.major());
        pair.add(format.minor());
        return pair;
    }

    private static void fail(String message) { throw new IllegalArgumentException("Mycelium datapack targets: " + message); }

    /** Standalone build-time entrance for consumers without Gradle; args: compiler root, declaration, output. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) fail("Expected compiler root, declaration JSON, output JSON");
        JsonObject request = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        JsonObject result = compile(Eras.load(Path.of(args[0]).toFile()), Versions.load(Path.of(args[0]).toFile()), request);
        Path output = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result) + "\n");
    }
}
