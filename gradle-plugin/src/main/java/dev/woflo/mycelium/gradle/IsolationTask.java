package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional integrations, isolated (docs/integrations.md). An artifact that works with another mod when it is installed, and
 * without it when it is not, keeps that promise only if the other mod's classes are never loaded when it is absent.
 * That is a property of the source's shape, so it is checked, not hoped:
 * <ul>
 *   <li><b>I1 confined</b> (fail): only sources inside an integration's home package mention the other mod's API
 *   packages. This is the NoClassDefFoundError law.</li>
 *   <li><b>I2 unreferenced</b> (warn): nothing outside the home package mentions it, except the integration's gates (the
 *   places that check the mod is loaded, then call in). Gates are <em>derived</em> unless the mod declares them: every
 *   source outside the home that references the home is a gate, reported in {@code isolation.json}; declare gates (the
 *   fourth argument of {@code integration}) to close the list, and I2 then reports a reference from anywhere else.</li>
 * </ul>
 * Runs in {@code check}, never before {@code compileJava} (docs/decisions/0017).
 * Comments are ignored (a javadoc may name anything). Found in Somnium (2026-09-24), where one source tree integrates
 * Simple Voice Chat on four targets and Cobblemon on two.
 *
 * <pre>
 * mycelium {
 *     integration("voicechat", "com.example.mymod.voice", "de.maxhenkel.voicechat")
 *     integration("cobblemon", "com.example.mymod.compat.cobblemon", "com.cobblemon", "woflo/oneiric/compat/Integrations.java")
 * }
 * </pre>
 */
public abstract class IsolationTask extends DefaultTask {
    /** Encoded integrations: {@code name|homePackage|api1,api2|gate1,gate2}. */
    @Input
    public abstract ListProperty<String> getIntegrations();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /** The mod's check levels and allowances ({@link Checks#decode}). */
    @Input
    public abstract ListProperty<String> getChecks();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    /**
     * One declared integration. Validated where it is declared ({@link MyceliumExtension#integration}) and again where it
     * is read, so a malformed one (no API packages, a blank home, a stray separator) is an error, never an integration
     * that constrains nothing and passes.
     */
    record Integration(String name, String home, List<String> apis, List<String> gates) {
        static final Pattern JAVA_PACKAGE = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

        Integration {
            String what = "Mycelium integration '" + name + "'";
            if (name == null || name.isBlank() || name.contains("|")) {
                throw new IllegalArgumentException("Mycelium integration: a name is required (and may not contain '|')");
            }
            if (home == null || !JAVA_PACKAGE.matcher(home).matches()) {
                throw new IllegalArgumentException(what + ": home package '" + home + "' is not a Java package");
            }
            if (apis.isEmpty()) {
                throw new IllegalArgumentException(what + ": names no API packages, so it would keep nothing out;"
                        + " name the other mod's packages (comma-separated)");
            }
            for (String api : apis) {
                if (!JAVA_PACKAGE.matcher(api).matches()) {
                    throw new IllegalArgumentException(what + ": '" + api + "' is not a Java package");
                }
            }
            for (String gate : gates) {
                if (gate.contains("|")) {
                    throw new IllegalArgumentException(what + ": gate '" + gate + "' may not contain '|'");
                }
            }
            apis = List.copyOf(apis);
            gates = List.copyOf(gates);
        }

        static Integration decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 3 || p.length > 4) {
                throw new IllegalArgumentException("Mycelium integration: malformed declaration '" + s
                        + "' (name|homePackage|apis|gates)");
            }
            return new Integration(p[0], p[1], split(p[2]), p.length > 3 ? split(p[3]) : List.of());
        }

        String encode() {
            return name + "|" + home + "|" + String.join(",", apis) + "|" + String.join(",", gates);
        }

        static List<String> split(String s) {
            List<String> out = new ArrayList<>();
            for (String x : s.split(",")) {
                if (!x.isBlank()) {
                    out.add(x.trim());
                }
            }
            return out;
        }
    }

    record Source(String path, String pkg, String code) {
    }

    static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /** The code with comments removed (block, line), so a doc comment naming a package is not a reference. */
    static String code(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        boolean inString = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    out.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
            } else if (c == '"') {
                inString = true;
                out.append(c);
                i++;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
                out.append(' ');
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static boolean mentions(String code, String pkg) {
        return Pattern.compile("(?<![\\w.])" + Pattern.quote(pkg) + "\\.").matcher(code).find();
    }

    static boolean inside(String pkg, String home) {
        return pkg.equals(home) || pkg.startsWith(home + ".");
    }

    @TaskAction
    public void run() throws IOException {
        List<Source> sources = new ArrayList<>();
        for (File root : getSources().getFiles()) {
            if (!root.exists()) {
                continue;
            }
            Path base = root.toPath();
            try (var walk = Files.walk(base)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(p) && p.toString().endsWith(".java")) {
                        String text = Files.readString(p, StandardCharsets.UTF_8);
                        Matcher m = PACKAGE.matcher(text);
                        String rel = base.relativize(p).toString().replace('\\', '/');
                        sources.add(new Source(rel, m.find() ? m.group(1) : "", code(text)));
                    }
                }
            }
        }
        JsonArray results = new JsonArray();
        Checks.Findings found = new Checks.Findings(Checks.decode(getChecks().get()));
        for (String encoded : getIntegrations().get()) {
            Integration in = Integration.decode(encoded);
            int homeFiles = 0;
            boolean derive = in.gates().isEmpty();
            List<String> derived = new ArrayList<>();
            for (Source s : sources) {
                boolean home = inside(s.pkg(), in.home());
                homeFiles += home ? 1 : 0;
                for (String api : in.apis()) {
                    if (!home && mentions(s.code(), api)) {
                        found.add("I1", s.path(), in.name() + ": " + s.path() + " mentions " + api + " outside " + in.home()
                                + " (it would load the other mod's class without it installed)");
                    }
                }
                if (!home && mentions(s.code(), in.home())) {
                    if (derive) {
                        derived.add(s.path());
                    } else if (!in.gates().contains(s.path())) {
                        found.add("I2", s.path(), in.name() + ": " + s.path() + " reaches into " + in.home() + " (not a declared gate)");
                    }
                }
            }
            JsonObject r = new JsonObject();
            r.addProperty("integration", in.name());
            r.addProperty("home", in.home());
            r.addProperty("homeFiles", homeFiles);
            r.addProperty("gates", derive ? "derived" : "declared");
            JsonArray g = new JsonArray();
            (derive ? derived : in.gates()).forEach(g::add);
            r.add("gateFiles", g);
            results.add(r);
        }
        JsonObject report = new JsonObject();
        report.addProperty("schema", 2);
        report.addProperty("sourcesScanned", sources.size());
        report.add("integrations", results);
        report.add("findings", found.toJson());
        Path out = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        Files.writeString(out.resolve("isolation.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        found.conclude(getLogger(), "optional integrations are not isolated (the artifact would load another mod's classes without it installed)");
        getLogger().lifecycle("Mycelium: {} integration(s) checked ({} source files scanned, {} finding(s))", getIntegrations().get().size(), sources.size(), found.all().size());
    }
}
