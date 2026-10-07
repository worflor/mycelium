package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code myceliumRefreshNames}: pins the name data of versions in {@code eras/names.json} (docs/decisions/0010), by hand,
 * when a version ships or a namespace gains data. With {@code --versions=1.21.2,1.21.3} it finds each version's files where
 * its namespace's {@code resolve} says, pins them by SHA-256 and adds the entry (an entry that is there is never changed); a
 * version nothing is published for is reported and gets none. With no versions it reads every pinned url again and fails
 * naming each whose bytes are no longer the pinned ones. {@code --namespace=<name>} narrows either to one naming. Never part
 * of a build, which only reads the pins.
 */
@UntrackedTask(because = "it reads the network and rewrites a tracked file on purpose")
public abstract class RefreshNamesTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    @Input
    @Optional
    @Option(option = "namespace", description = "The naming to refresh (srg, intermediary, mojang, unobfuscated, api); all when omitted.")
    public abstract Property<String> getNamespace();

    @Input
    @Optional
    @Option(option = "versions", description = "Comma-separated Minecraft versions to pin; none reads every pinned url again and reports drift.")
    public abstract Property<String> getVersions();

    @TaskAction
    public void run() throws IOException {
        Path file = NameData.lockOf(getRoot().get().getAsFile()).toPath();
        JsonObject lock = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        Set<String> namespaces = new LinkedHashSet<>();
        if (getNamespace().isPresent()) {
            NameRefresh.namespace(lock, getNamespace().get());
            namespaces.add(getNamespace().get());
        } else {
            namespaces.addAll(lock.getAsJsonObject("namespaces").keySet());
        }
        NameRefresh.Fetch fetch = NameRefresh.http();
        if (!getVersions().isPresent() || getVersions().get().isBlank()) {
            List<String> drift = NameRefresh.drift(lock, namespaces, fetch);
            if (!drift.isEmpty()) {
                throw new GradleException("Mycelium: pinned name data no longer matches its pin:\n  " + String.join("\n  ", drift));
            }
            getLogger().lifecycle("Mycelium: every pinned name file of {} is still the one pinned", namespaces);
            return;
        }
        if (namespaces.size() != 1) {
            throw new GradleException("Mycelium: --versions needs --namespace=<name>, since where a version's files are depends on the naming");
        }
        String ns = namespaces.iterator().next();
        JsonObject resolver = NameRefresh.namespace(lock, ns).has("resolve") ? NameRefresh.namespace(lock, ns).getAsJsonObject("resolve") : null;
        List<String> added = new ArrayList<>();
        for (String raw : getVersions().get().split(",")) {
            String version = raw.trim();
            if (version.isEmpty()) {
                continue;
            }
            if (NameRefresh.namespace(lock, ns).getAsJsonObject("versions").has(version)) {
                getLogger().lifecycle("Mycelium: {} {} is already pinned; an entry is never changed here", ns, version);
                continue;
            }
            try {
                NameRefresh.add(lock, ns, version, NameRefresh.resolve(resolver, version, fetch));
                added.add(version);
            } catch (NameRefresh.NotPublished e) {
                getLogger().lifecycle("Mycelium: {} has no name data for {}: {}", ns, version, e.getMessage());
            } catch (IllegalStateException e) {
                throw new GradleException("Mycelium: " + e.getMessage(), e);
            }
        }
        if (!added.isEmpty()) {
            Files.writeString(file, NameRefresh.render(lock), StandardCharsets.UTF_8);
        }
        getLogger().lifecycle("Mycelium: eras/names.json pins {} for {}; run ./gradlew check to prove the lock against the version list"
                + " and the era table", added.isEmpty() ? "nothing new" : added, ns);
    }
}
