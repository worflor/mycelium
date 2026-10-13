package dev.woflo.mycelium.gradle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What Mycelium's own build proved, so a mod need not prove it again (docs/decisions/0017, item 7). Mycelium's root
 * {@code check} runs every contract's harness and every harness on every era's Java; when that passes it records, in
 * {@code contracts/proofs.json}, a fingerprint of the sources each harness ran over and the Javas it ran on. A mod's
 * {@code myceliumVerify} recomputes the fingerprint of the sources it imports: equal, and it takes the proof as given;
 * different, or {@code -Pmycelium.verify=always}, and it runs the harness itself.
 *
 * <p>The fingerprint covers exactly what {@link VerifyContractsTask} compiles for a contract: the shared harness, the models
 * the contract requires, and the contract's own package, with line endings normalized (a Windows checkout and a Linux one
 * agree) and the release the harness is compiled for.
 */
final class Proofs {
    private Proofs() {
    }

    static final int SCHEMA = 1;

    /** The recorded proofs: the Javas the era gates ran on, and a fingerprint per contract id. */
    record Recorded(List<Integer> eras, Map<String, String> contracts) {
        static final Recorded NONE = new Recorded(List.of(), Map.of());
    }

    static Path file(Path root) {
        return root.resolve("contracts").resolve("proofs.json");
    }

    static String fingerprint(Path root, ContractRegistry registry, ContractRegistry.Entry entry) throws IOException {
        Path pkgSrc = root.resolve("contracts").resolve(entry.path()).resolve("src");
        List<Path> files = VerifyContractsTask.sources(root, pkgSrc, registry.closure(entry));
        TreeMap<String, Path> byName = new TreeMap<>();
        Path base = root.toAbsolutePath().normalize();
        for (Path p : files) {
            byName.put(base.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/'), p);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(("release " + VerifyContractsTask.RELEASE + "\n").getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, Path> e : byName.entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                String text = Files.readString(e.getValue(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                md.update(text.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException x) {
            throw new IllegalStateException(x);
        }
    }

    static Recorded load(Path root) throws IOException {
        Path file = file(root);
        if (!Files.isRegularFile(file)) {
            return Recorded.NONE;
        }
        JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!o.has("schema") || o.get("schema").getAsInt() != SCHEMA) {
            return Recorded.NONE; // an unreadable record proves nothing: the harness runs
        }
        List<Integer> eras = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("eras")) {
            eras.add(e.getAsInt());
        }
        Map<String, String> contracts = new LinkedHashMap<>();
        o.getAsJsonObject("contracts").entrySet().forEach(e -> contracts.put(e.getKey(), e.getValue().getAsString()));
        return new Recorded(eras, contracts);
    }

    /** Whether Mycelium's own gate proved {@code entry} as it is now, on every Java in {@code neededEras}. */
    static boolean provenUpstream(Path root, ContractRegistry registry, ContractRegistry.Entry entry, Recorded recorded,
                                  List<Integer> neededEras) throws IOException {
        String proven = recorded.contracts().get(entry.id());
        return proven != null && recorded.eras().containsAll(neededEras) && proven.equals(fingerprint(root, registry, entry));
    }

    /** Writes the record, one contract per line so two branches that prove different contracts merge. */
    static void write(Path root, Recorded recorded) throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"schema\": ").append(SCHEMA).append(",\n");
        sb.append("  \"note\": \"What Mycelium's own check proved: a fingerprint of the sources each contract's harness ran over"
                + " (the shared harness, the models it requires, its own package; docs/decisions/0017) and the Javas the era gates ran on."
                + " Written by ./gradlew myceliumRecordProofs (part of check), read by a mod's myceliumVerify. Do not edit.\",\n");
        sb.append("  \"eras\": [");
        for (int i = 0; i < recorded.eras().size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(recorded.eras().get(i));
        }
        sb.append("],\n  \"contracts\": {\n");
        List<Map.Entry<String, String>> rows = new ArrayList<>(new TreeMap<>(recorded.contracts()).entrySet());
        for (int i = 0; i < rows.size(); i++) {
            sb.append("    \"").append(rows.get(i).getKey()).append("\": \"").append(rows.get(i).getValue()).append('"')
                    .append(i + 1 < rows.size() ? ",\n" : "\n");
        }
        sb.append("  }\n}\n");
        Files.createDirectories(file(root).getParent());
        String text = sb.toString();
        if (!Files.isRegularFile(file(root)) || !Files.readString(file(root), StandardCharsets.UTF_8).equals(text)) {
            Files.writeString(file(root), text, StandardCharsets.UTF_8);
        }
    }
}
