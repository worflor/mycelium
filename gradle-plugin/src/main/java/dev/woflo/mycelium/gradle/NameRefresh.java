package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What {@code myceliumRefreshNames} does, apart from the task: finding a version's inputs where a namespace's
 * {@code resolve} says they are, pinning them by SHA-256, and writing {@code eras/names.json} in the one form it is kept in.
 *
 * <p>A {@code resolve} is an object whose {@code kind} says how a version's files are found: {@code url} (one file at a
 * template with {@code {version}}, a {@code role} and, in an archive, an {@code entry}), {@code mojang} (the launcher
 * manifest's download for a version: {@code game} names the download that is the {@code game} input and {@code mappings}
 * the one that is the {@code mappings} input), {@code maven-snapshot} (the newest timestamped jar of a Maven snapshot
 * directory: {@code base} with {@code {version}}, {@code artifact}) or {@code none} (pinned by hand).
 */
final class NameRefresh {
    private NameRefresh() {
    }

    /** Reads one url; a version nothing is published for throws {@link NotPublished}. */
    interface Fetch {
        byte[] get(String url) throws IOException;
    }

    /** The data for a version does not exist where the namespace looks: the version gets no entry. */
    static final class NotPublished extends IOException {
        NotPublished(String message) {
            super(message);
        }
    }

    private static final Gson PLAIN = new GsonBuilder().disableHtmlEscaping().create();

    static Fetch http() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return url -> {
            try {
                HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "mycelium-refresh-names").build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 404) {
                    throw new NotPublished(url + " answered 404");
                }
                if (r.statusCode() != 200) {
                    throw new IOException(url + " answered " + r.statusCode());
                }
                return r.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted reading " + url, e);
            }
        };
    }

    /** The pinned inputs of {@code version} under the namespace whose resolver is {@code resolver}. */
    static List<NameData.Input> resolve(JsonObject resolver, String version, Fetch fetch) throws IOException {
        String kind = resolver == null || !resolver.has("kind") ? "none" : resolver.get("kind").getAsString();
        switch (kind) {
            case "url": {
                String url = resolver.get("url").getAsString().replace("{version}", version);
                String entry = resolver.has("entry") ? resolver.get("entry").getAsString() : null;
                return List.of(new NameData.Input(resolver.get("role").getAsString(), url, NameCache.sha256(fetch.get(url)), entry));
            }
            case "mojang": {
                JsonObject manifest = json(fetch.get(RefreshVersionsTask.MANIFEST));
                String versionUrl = null;
                for (JsonElement e : manifest.getAsJsonArray("versions")) {
                    if (e.getAsJsonObject().get("id").getAsString().equals(version)) {
                        versionUrl = e.getAsJsonObject().get("url").getAsString();
                    }
                }
                if (versionUrl == null) {
                    throw new NotPublished("Mojang's launcher manifest has no " + version);
                }
                JsonObject downloads = json(fetch.get(versionUrl)).getAsJsonObject("downloads");
                List<NameData.Input> inputs = new ArrayList<>();
                inputs.add(mojang(downloads, resolver.get("game").getAsString(), "game", version, fetch));
                if (resolver.has("mappings")) {
                    inputs.add(mojang(downloads, resolver.get("mappings").getAsString(), "mappings", version, fetch));
                }
                return inputs;
            }
            case "maven-snapshot": {
                String base = resolver.get("base").getAsString().replace("{version}", version);
                String metadata = base + "maven-metadata.xml";
                String value = newestJar(new String(fetch.get(metadata), StandardCharsets.UTF_8), metadata);
                String url = base + resolver.get("artifact").getAsString() + "-" + value + ".jar";
                return List.of(new NameData.Input("game", url, NameCache.sha256(fetch.get(url)), null));
            }
            default:
                throw new IllegalStateException("this namespace's name data is pinned by hand (its resolve kind is '" + kind + "')");
        }
    }

    private static NameData.Input mojang(JsonObject downloads, String key, String role, String version, Fetch fetch) throws IOException {
        if (!downloads.has(key)) {
            throw new NotPublished("Mojang publishes no '" + key + "' download for " + version);
        }
        String url = downloads.getAsJsonObject(key).get("url").getAsString();
        return new NameData.Input(role, url, NameCache.sha256(fetch.get(url)), null);
    }

    /** The value of the newest {@code <snapshotVersion>} for the plain jar in a Maven snapshot's metadata. */
    static String newestJar(String xml, String where) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            NodeList all = doc.getElementsByTagName("snapshotVersion");
            String best = null;
            String bestStamp = "";
            for (int i = 0; i < all.getLength(); i++) {
                Element e = (Element) all.item(i);
                if (child(e, "classifier") == null && "jar".equals(child(e, "extension"))) {
                    String updated = String.valueOf(child(e, "updated"));
                    if (best == null || updated.compareTo(bestStamp) > 0) {
                        best = child(e, "value");
                        bestStamp = updated;
                    }
                }
            }
            if (best == null) {
                throw new NotPublished(where + " lists no jar");
            }
            return best;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(where + " is not Maven metadata: " + e.getMessage(), e);
        }
    }

    private static String child(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }

    private static JsonObject json(byte[] bytes) {
        return new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
    }

    /** Adds {@code version} to {@code namespace} in {@code lock} with its pinned inputs, replacing no entry that is there. */
    static void add(JsonObject lock, String namespace, String version, List<NameData.Input> inputs) {
        JsonObject versions = namespace(lock, namespace).getAsJsonObject("versions");
        if (versions.has(version)) {
            throw new IllegalStateException(namespace + " " + version + " is already pinned");
        }
        JsonArray in = new JsonArray();
        for (NameData.Input i : inputs) {
            JsonObject o = new JsonObject();
            o.addProperty("role", i.role());
            o.addProperty("url", i.url());
            o.addProperty("sha256", i.sha256());
            if (i.entry() != null) {
                o.addProperty("entry", i.entry());
            }
            in.add(o);
        }
        JsonObject entry = new JsonObject();
        entry.add("inputs", in);
        versions.add(version, entry);
    }

    static JsonObject namespace(JsonObject lock, String namespace) {
        JsonObject all = lock.getAsJsonObject("namespaces");
        if (!all.has(namespace)) {
            throw new IllegalStateException("eras/names.json has no namespace '" + namespace + "' (it holds " + all.keySet() + ")");
        }
        return all.getAsJsonObject(namespace);
    }

    /**
     * Every pinned url of {@code namespaces} read again and held to its pin: one line per file whose bytes are no longer the
     * ones pinned, or that is no longer published.
     */
    static List<String> drift(JsonObject lock, Set<String> namespaces, Fetch fetch) {
        List<String> out = new ArrayList<>();
        for (String ns : namespaces) {
            for (Map.Entry<String, JsonElement> v : namespace(lock, ns).getAsJsonObject("versions").entrySet()) {
                for (JsonElement ie : v.getValue().getAsJsonObject().getAsJsonArray("inputs")) {
                    JsonObject i = ie.getAsJsonObject();
                    String url = i.get("url").getAsString();
                    try {
                        String now = NameCache.sha256(fetch.get(url));
                        if (!now.equals(i.get("sha256").getAsString())) {
                            out.add(ns + " " + v.getKey() + " " + i.get("role").getAsString() + ": " + url + " is now " + now
                                    + ", pinned " + i.get("sha256").getAsString());
                        }
                    } catch (IOException e) {
                        out.add(ns + " " + v.getKey() + " " + i.get("role").getAsString() + ": " + url + " cannot be read (" + e.getMessage() + ")");
                    }
                }
            }
        }
        return out;
    }

    /** The lock as it is kept: each namespace's settings on a line of their own, then one line per version, oldest first. */
    static String render(JsonObject lock) {
        StringBuilder out = new StringBuilder("{\n");
        List<String> top = new ArrayList<>();
        for (String key : lock.keySet()) {
            if (!key.equals("namespaces")) {
                top.add("  " + quote(key) + ": " + inline(lock.get(key)));
            }
        }
        List<String> blocks = new ArrayList<>();
        for (Map.Entry<String, JsonElement> n : lock.getAsJsonObject("namespaces").entrySet()) {
            JsonObject ns = n.getValue().getAsJsonObject();
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, JsonElement> p : ns.entrySet()) {
                if (!p.getKey().equals("versions")) {
                    lines.add("      " + quote(p.getKey()) + ": " + inline(p.getValue()));
                }
            }
            TreeMap<String, JsonElement> sorted = new TreeMap<>(Eras::compare);
            ns.getAsJsonObject("versions").entrySet().forEach(v -> sorted.put(v.getKey(), v.getValue()));
            List<String> versions = new ArrayList<>();
            for (Map.Entry<String, JsonElement> v : sorted.entrySet()) {
                versions.add("        " + quote(v.getKey()) + ": " + inline(v.getValue()));
            }
            lines.add("      \"versions\": {\n" + String.join(",\n", versions) + "\n      }");
            blocks.add("    " + quote(n.getKey()) + ": {\n" + String.join(",\n", lines) + "\n    }");
        }
        top.add("  \"namespaces\": {\n" + String.join(",\n", blocks) + "\n  }");
        out.append(String.join(",\n", top)).append("\n}\n");
        return out.toString();
    }

    private static String quote(String s) {
        return PLAIN.toJson(s);
    }

    private static String inline(JsonElement e) {
        if (e.isJsonObject()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, JsonElement> p : e.getAsJsonObject().entrySet()) {
                parts.add(quote(p.getKey()) + ": " + inline(p.getValue()));
            }
            return "{" + String.join(", ", parts) + "}";
        }
        if (e.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            e.getAsJsonArray().forEach(x -> parts.add(inline(x)));
            return "[" + String.join(", ", parts) + "]";
        }
        return PLAIN.toJson(e);
    }
}
