package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code myceliumBridges}: what can be said about the mod's bridges once its classes are compiled (docs/decisions/0016).
 * A bridge the mod overrides must be a class that exists and implements the bridge's port, which is the only thing the
 * contract can hold an override to (B1); a bridge the game cannot do on the target version was not imported, and the mod
 * hears it (B2). Writes {@code bridges.json}: the import's choice (the variant and overlays, or the override) with what
 * this check found, which the evidence manifest carries.
 */
public abstract class BridgeCheckTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    /** {@code myceliumBridgeImport}'s report. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getImportReport();

    /** The compiled classes of the mod (an override is one of them). */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    @Input
    public abstract Property<String> getNamespace();

    @Input
    public abstract ListProperty<String> getChecks();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    @TaskAction
    public void run() throws IOException {
        JsonObject imported = JsonParser.parseString(Files.readString(getImportReport().get().getAsFile().toPath(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        Checks.Findings found = new Checks.Findings(Checks.decode(getChecks().get()));
        ContractRegistry registry = ContractRegistry.load(getRoot().get().getAsFile());
        Map<String, Hierarchy> classes = null;
        JsonArray out = new JsonArray();
        for (JsonElement e : imported.getAsJsonArray("bridges")) {
            JsonObject bridge = e.getAsJsonObject().deepCopy();
            String slug = bridge.get("slug").getAsString();
            switch (bridge.get("status").getAsString()) {
                case "none" -> found.add("B2", slug, "bridge " + slug + " has no realization on Minecraft " + imported.get("minecraft").getAsString()
                        + " and was not imported: " + bridge.get("why").getAsString()
                        + " (the mod writes that half natively there, or does not claim the version)");
                case "override" -> {
                    if (classes == null) {
                        classes = hierarchy(getClasses().getFiles());
                    }
                    ContractRegistry.Entry contract = registry.require(bridge.get("contract").getAsString());
                    String port = (getNamespace().get() + "." + contract.relativePackage() + ".model." + bridge.get("port").getAsString())
                            .replace('.', '/');
                    String name = bridge.get("class").getAsString().replace('.', '/');
                    switch (implementsPort(classes, name, port)) {
                        case MISSING -> found.add("B1", slug, "bridge " + slug + " is overridden by " + bridge.get("class").getAsString()
                                + ", which is not a class this project compiles (a typo, or a class in another project)");
                        case NO -> found.add("B1", slug, "bridge " + slug + " is overridden by " + bridge.get("class").getAsString()
                                + ", which does not implement the port " + port.replace('/', '.')
                                + ": the contract's laws are held to the port, so an override must implement it");
                        case UNSEEN -> bridge.addProperty("unverified", "the class extends something outside the project's own classes, so the port"
                                + " could not be seen in its hierarchy");
                        default -> bridge.addProperty("verified", "implements " + port.replace('/', '.'));
                    }
                }
                default -> {
                }
            }
            out.add(bridge);
        }
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        if (imported.has("minecraft")) {
            report.addProperty("minecraft", imported.get("minecraft").getAsString());
        }
        report.add("bridges", out);
        report.add("findings", found.toJson());
        Path dir = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bridges.json"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report) + "\n",
                StandardCharsets.UTF_8);
        found.conclude(getLogger(), "bridges");
    }

    // ---------------------------------------------------------------------------------------------------- the port

    /** A class's direct supertypes, as the class file says. */
    record Hierarchy(String superName, List<String> interfaces) {
    }

    enum Verdict { YES, NO, MISSING, UNSEEN }

    /** Every class under the given class directories (or jars are not read: a project's own classes are directories). */
    static Map<String, Hierarchy> hierarchy(Set<File> roots) throws IOException {
        Map<String, Hierarchy> out = new HashMap<>();
        for (File root : roots) {
            if (!root.isDirectory()) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root.toPath())) {
                for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".class"))::iterator) {
                    ClassReader reader = new ClassReader(Files.readAllBytes(p));
                    String[] interfaces = reader.getInterfaces();
                    out.put(reader.getClassName(), new Hierarchy(reader.getSuperName(), List.of(interfaces)));
                }
            }
        }
        return out;
    }

    /**
     * Whether {@code name} (an internal name) implements {@code port}, through the classes in {@code classes}: {@code YES},
     * {@code NO} when its whole hierarchy is in the project (or the Java platform) and the port is not in it, {@code UNSEEN}
     * when a supertype outside the project could still carry it, {@code MISSING} when there is no such class.
     */
    static Verdict implementsPort(Map<String, Hierarchy> classes, String name, String port) {
        if (!classes.containsKey(name)) {
            return Verdict.MISSING;
        }
        boolean unseen = false;
        List<String> todo = new ArrayList<>(List.of(name));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String at = todo.remove(todo.size() - 1);
            if (!seen.add(at)) {
                continue;
            }
            if (at.equals(port)) {
                return Verdict.YES;
            }
            Hierarchy h = classes.get(at);
            if (h == null) {
                unseen |= !at.startsWith("java/");
                continue;
            }
            if (h.superName() != null) {
                todo.add(h.superName());
            }
            todo.addAll(h.interfaces());
        }
        return unseen ? Verdict.UNSEEN : Verdict.NO;
    }
}
