package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;

/**
 * {@code myceliumRefreshVersions}: rewrites {@code eras/versions.json} from Mojang's launcher manifest (releases, betas,
 * alphas; snapshots left out). Run by hand when a version ships; never part of a build, which stays offline.
 */
@UntrackedTask(because = "it reads the network and rewrites a tracked file on purpose")
public abstract class RefreshVersionsTask extends DefaultTask {
    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final Set<String> TYPES = Set.of("release", "old_beta", "old_alpha");

    @Internal
    public abstract DirectoryProperty getRoot();

    @TaskAction
    public void run() throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
                .send(HttpRequest.newBuilder(URI.create(MANIFEST)).timeout(Duration.ofSeconds(60)).build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new GradleException("Mycelium: " + MANIFEST + " answered " + response.statusCode());
        }
        JsonObject manifest = new Gson().fromJson(response.body(), JsonObject.class);
        Path file = getRoot().get().getAsFile().toPath().resolve("eras/versions.json");
        JsonObject out = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        JsonArray versions = new JsonArray();
        for (JsonElement e : manifest.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            if (TYPES.contains(v.get("type").getAsString())) {
                JsonObject o = new JsonObject();
                o.addProperty("id", v.get("id").getAsString());
                o.addProperty("type", v.get("type").getAsString());
                o.addProperty("released", v.get("releaseTime").getAsString().substring(0, 10));
                versions.add(o);
            }
        }
        int before = out.getAsJsonArray("versions").size();
        out.addProperty("read", LocalDate.now().toString());
        out.add("versions", versions);
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out) + "\n", StandardCharsets.UTF_8);
        getLogger().lifecycle("Mycelium: eras/versions.json now lists {} versions (was {}); run ./gradlew check to prove the"
                + " era table against it", versions.size(), before);
    }
}
