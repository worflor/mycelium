package dev.woflo.mycelium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Copies each selected contract's {@code model} package into the consumer's generated source dir,
 * rewriting {@code mycelium.<pkg>.v1} to {@code <namespace>.<pkg>.v1}. Fixtures and harness are never
 * imported: they are design-time only. A model that imports {@code mycelium.harness} is rejected.
 */
public abstract class ImportContractsTask extends DefaultTask {
    private static final Pattern PACKAGE_REF = Pattern.compile("\\bmycelium\\.([a-z_]+)\\.v(\\d+)\\b");

    @Internal
    public abstract DirectoryProperty getRoot();

    /** Fingerprint of the registry plus every imported model source, so the task re-runs on change. */
    @Input
    public abstract Property<String> getSourceFingerprint();

    @Input
    public abstract ListProperty<String> getContracts();

    @Input
    public abstract Property<String> getNamespace();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void run() throws IOException {
        Path root = getRoot().get().getAsFile().toPath();
        Path out = getOutputDir().get().getAsFile().toPath();
        deleteRecursively(out);
        Files.createDirectories(out);

        ContractRegistry registry = ContractRegistry.load(root.toFile());
        String namespace = getNamespace().get();
        List<String> ids = getContracts().get();
        if (ids.isEmpty()) {
            getLogger().lifecycle("Mycelium: no contracts selected; nothing imported");
            return;
        }
        int files = 0;
        for (String id : ids) {
            ContractRegistry.Entry entry = registry.require(id);
            Path modelDir = root.resolve("contracts").resolve(entry.path()).resolve("src")
                    .resolve(entry.pkg().replace('.', '/')).resolve("model");
            if (!Files.isDirectory(modelDir)) {
                throw new GradleException("Mycelium contract " + id + " has no model dir at " + modelDir);
            }
            Path target = out.resolve(namespace.replace('.', '/'))
                    .resolve(entry.relativePackage().replace('.', '/')).resolve("model");
            Files.createDirectories(target);
            try (Stream<Path> walk = Files.walk(modelDir)) {
                for (Path source : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(source) || !source.toString().endsWith(".java")) {
                        continue;
                    }
                    String text = Files.readString(source, StandardCharsets.UTF_8);
                    if (text.contains("mycelium.harness")) {
                        throw new GradleException("Mycelium model must not depend on the harness: " + source);
                    }
                    String rewritten = rewrite(text, namespace);
                    Path dest = target.resolve(modelDir.relativize(source).toString());
                    Files.createDirectories(dest.getParent());
                    Files.writeString(dest, rewritten, StandardCharsets.UTF_8);
                    files++;
                }
            }
        }
        getLogger().lifecycle("Mycelium: imported {} model file(s) for {} contract(s) under {}", files, ids.size(), namespace);
    }

    static String rewrite(String text, String namespace) {
        Matcher m = PACKAGE_REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(namespace + "." + m.group(1) + ".v" + m.group(2)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : (Iterable<Path>) walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
