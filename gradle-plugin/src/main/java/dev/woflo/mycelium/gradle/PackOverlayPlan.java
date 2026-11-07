package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans mutually exclusive per-kind pack overlays; it does not translate content or certify game behaviour. */
public final class PackOverlayPlan {
    private PackOverlayPlan() { }

    public static JsonObject compile(Eras eras, JsonObject request) {
        for (String key : request.keySet()) {
            if (!Set.of("schema", "description", "targets").contains(key)) fail("Unknown declaration field: " + key);
        }
        if (!request.has("schema") || !request.get("schema").toString().equals("1")) fail("Declaration schema must be 1");
        if (!request.has("description") || !request.get("description").isJsonPrimitive()
                || !request.get("description").getAsJsonPrimitive().isString()
                || request.get("description").getAsString().isBlank()) fail("A description is required");
        if (!request.has("targets") || !request.get("targets").isJsonArray()
                || request.getAsJsonArray("targets").isEmpty()) fail("At least one exact target is required");
        Set<String> seen = new HashSet<>();
        List<String> versions = new ArrayList<>();
        for (JsonElement value : request.getAsJsonArray("targets")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) fail("Targets must be strings");
            String version = value.getAsString();
            if (!version.matches("[0-9]+(?:\\.[0-9]+)+") || !seen.add(version)) fail("Duplicate or non-release target: " + version);
            versions.add(version);
        }
        versions.sort(Eras::compare);
        Comparator<Eras.Format> ordering = Comparator.comparingInt(Eras.Format::major).thenComparingInt(Eras.Format::minor);
        Map<String, JsonObject> groups = new LinkedHashMap<>();
        JsonArray targets = new JsonArray();
        Eras.Format minimum = null, maximum = null;
        boolean legacy = false;
        for (String version : versions) {
            Eras.PackFormat row = eras.packFormatFor(version);
            if (row.resource().major() < Eras.PackFormat.SUPPORTED_FORMATS_FROM || row.data() == null) {
                fail("Pack overlays unavailable on " + version);
            }
            JsonObject target = new JsonObject();
            target.addProperty("minecraft", version);
            for (String kind : List.of("data", "resource")) {
                Eras.Format format = kind.equals("data") ? row.data() : row.resource();
                if (format.major() < 0 || format.minor() < 0) fail("Invalid pack format on " + version);
                minimum = minimum == null || ordering.compare(format, minimum) < 0 ? format : minimum;
                maximum = maximum == null || ordering.compare(format, maximum) > 0 ? format : maximum;
                legacy |= format.major() < (kind.equals("data") ? Eras.PackFormat.DATA_RANGE_FORMATS_FROM : Eras.PackFormat.RANGE_FORMATS_FROM);
                String directory = kind + "_" + format.major() + "_" + format.minor();
                JsonObject group = groups.get(directory);
                if (group == null) {
                    group = new JsonObject();
                    group.addProperty("kind", kind);
                    group.addProperty("directory", directory);
                    group.add("format", pair(format));
                    group.add("targets", new JsonArray());
                    groups.put(directory, group);
                }
                group.getAsJsonArray("targets").add(version);
                target.add(kind, pair(format));
            }
            targets.add(target);
        }
        int sharedMinimum = Eras.PackFormat.sharedMinimum(minimum.major());
        boolean bridge = sharedMinimum != minimum.major();
        if (bridge) { minimum = new Eras.Format(sharedMinimum, 0); legacy = true; }
        JsonObject pack = new JsonObject();
        pack.addProperty("description", request.get("description").getAsString());
        pack.add("min_format", pair(minimum));
        pack.add("max_format", pair(maximum));
        if (legacy) {
            pack.addProperty("pack_format", minimum.major());
            JsonArray supported = new JsonArray(); supported.add(minimum.major()); supported.add(maximum.major());
            pack.add("supported_formats", supported);
        }
        JsonArray entries = new JsonArray(), overlayPlan = new JsonArray();
        JsonArray emptyOverlays = new JsonArray();
        if (bridge) {
            // The overlay parser also takes a GLOBAL minimum across both number spaces.
            // This resource-free entry makes both typed readers accept legacy formats fields.
            String directory = "metadata_compat_" + sharedMinimum;
            JsonObject entry = new JsonObject(); entry.addProperty("directory", directory);
            entry.add("min_format", pair(minimum)); entry.add("max_format", pair(minimum));
            entry.addProperty("formats", sharedMinimum); entries.add(entry); emptyOverlays.add(directory);
        }
        for (JsonObject group : groups.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("directory", group.get("directory").getAsString());
            entry.add("min_format", group.get("format").deepCopy());
            entry.add("max_format", group.get("format").deepCopy());
            if (legacy) entry.addProperty("formats", group.getAsJsonArray("format").get(0).getAsInt());
            entries.add(entry); overlayPlan.add(group);
        }
        JsonObject overlays = new JsonObject(); overlays.add("entries", entries);
        JsonObject metadata = new JsonObject(); metadata.add("pack", pack); metadata.add("overlays", overlays);
        JsonObject result = new JsonObject(); result.addProperty("schema", 1);
        result.addProperty("scope", "Exact requested targets; equal pack formats share an overlay and must have identical effective content. No translation, migration or game certification.");
        result.add("targets", targets); result.add("overlays", overlayPlan); result.add("empty_overlays", emptyOverlays); result.add("pack_mcmeta", metadata);
        return result;
    }

    private static JsonArray pair(Eras.Format format) {
        JsonArray pair = new JsonArray(); pair.add(format.major()); pair.add(format.minor()); return pair;
    }
    private static void fail(String message) { throw new IllegalArgumentException("Mycelium pack overlays: " + message); }

    /** Standalone build-time entrance for consumers without Gradle; args: compiler root, declaration, output. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) fail("Expected compiler root, declaration JSON, output JSON");
        JsonObject request = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        JsonObject result = compile(Eras.load(Path.of(args[0]).toFile()), request);
        Path output = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result) + "\n");
    }
}
