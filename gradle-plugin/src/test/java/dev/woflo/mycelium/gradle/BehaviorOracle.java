package dev.woflo.mycelium.gradle;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/** Compares diagnostics with execution, rather than deriving expected answers from analyzer rules. */
final class BehaviorOracle {
    static void run() throws Exception {
        Path scratch = Files.createTempDirectory("mycelium-behavior-oracle-");
        Path api = scratch.resolve("src/external/Api.java"), source = scratch.resolve("src/oracle/Programs.java");
        Files.createDirectories(api.getParent());
        Files.createDirectories(source.getParent());
        Files.writeString(api, "package external; public class Api { public static void register(Runnable r) { r.run(); } }");
        List<String> operations = List.of("bind();", "service = null;", "now(Programs::bind);",
                "now(() -> service = null);", "relay(Programs::bind);", "relay(() -> service = null);",
                "if (choice) bind();", "if (choice) service = null;", "service = maybe(choice);",
                "if (choice) bind(); else bind();");
        List<String> programs = new ArrayList<>();
        for (String first : operations) for (String second : operations) programs.add(first + second);
        Collections.shuffle(programs, new Random(20261018L));
        StringBuilder code = new StringBuilder("""
                package oracle;
                import external.Api;
                public class Programs {
                    static Runnable service;
                    static void bind() { service = () -> {}; }
                    static void read() { service.run(); }
                    static void now(Runnable r) { r.run(); }
                    static void relay(Runnable r) { now(r); }
                    static Runnable maybe(boolean b) { return b ? null : () -> {}; }
                    public static void reset() { service = null; }
                    public static Runnable handler(boolean safe) { return safe ? new SafeHandler() : new HiddenHandler(); }
                """);
        for (int i = 0; i < programs.size(); i++) code.append("public static void case").append(i)
                .append("(boolean choice) {").append(programs.get(i)).append("Api.register(Programs::read); }\n");
        Files.writeString(source, code.append("""
                }
                class HiddenHandler implements Runnable {
                    static Runnable service;
                    static void read() { service.run(); }
                    public void run() { Api.register(HiddenHandler::read); }
                }
                class SafeHandler implements Runnable {
                    static final Runnable service = () -> {};
                    static void read() { service.run(); }
                    public void run() { Api.register(SafeHandler::read); }
                }
                """).toString());
        for (int release : List.of(8, 17, 25)) {
            if (release > Runtime.version().feature()) continue;
            Path classes = scratch.resolve("classes-" + release);
            int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", Integer.toString(release),
                    "-Xlint:-options", "-g", "-d", classes.toString(), api.toString(), source.toString());
            SelfTest.that(exit == 0, "oracle: generated programs compile for Java " + release);
            var result = CallbackInitialization.inspect(List.of(classes.resolve("oracle")), release);
            SelfTest.that(result.analysisErrors().isEmpty(), "oracle: no analysis gaps for Java " + release + ": " + result.analysisErrors());
            SelfTest.that(result.findings().stream().anyMatch(f -> f.root().owner().equals("oracle/HiddenHandler"))
                            && result.findings().stream().noneMatch(f -> f.root().owner().equals("oracle/SafeHandler")),
                    "oracle: externally callable handlers retain coverage on package-private implementations");
            Set<String> diagnostics = result.findings().stream().map(f -> f.root().name()).collect(Collectors.toSet());
            int unsafe = 0, safe = 0, falsePositives = 0;
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Class<?> type = loader.loadClass("oracle.Programs");
                boolean hiddenFailed = false;
                try { ((Runnable) type.getMethod("handler", boolean.class).invoke(null, false)).run(); }
                catch (NullPointerException expected) { hiddenFailed = true; }
                ((Runnable) type.getMethod("handler", boolean.class).invoke(null, true)).run();
                SelfTest.that(hiddenFailed, "oracle: public-interface dispatch reaches the hidden unsafe handler; safe control succeeds");
                for (int i = 0; i < programs.size(); i++) {
                    boolean failed = false;
                    for (boolean choice : List.of(false, true)) {
                        type.getMethod("reset").invoke(null);
                        try { type.getMethod("case" + i, boolean.class).invoke(null, choice); }
                        catch (InvocationTargetException exception) {
                            if (!(exception.getCause() instanceof NullPointerException)) throw exception;
                            failed = true;
                        }
                    }
                    if (failed) unsafe++; else safe++;
                    if (!failed && diagnostics.contains("case" + i)) falsePositives++;
                    // The must-binding lattice loses correlations between a factory return and a later branch.
                    boolean correlated = programs.get(i).equals("service = maybe(choice);if (choice) bind();");
                    SelfTest.that(diagnostics.contains("case" + i) == failed || (correlated && !failed && diagnostics.contains("case" + i)),
                            "oracle: Java " + release + " case" + i + " matches the execution oracle or the documented correlation limit (NPE=" + failed + "): " + programs.get(i));
                }
            }
            System.out.println("Behavior oracle Java " + release + ": " + unsafe + " failing, " + safe
                    + " safe; " + falsePositives + " documented branch-correlation false positive(s).");
            SelfTest.that(unsafe > 0 && safe > 0, "oracle: Java " + release + " covers both failing and safe executions (" + unsafe + "/" + safe + ")");
        }
    }
}
