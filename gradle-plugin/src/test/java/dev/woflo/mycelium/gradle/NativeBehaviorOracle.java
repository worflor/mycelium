package dev.woflo.mycelium.gradle;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Independent counterexample for opaque native calls that can return or forward callbacks. */
final class NativeBehaviorOracle {
    private NativeBehaviorOracle() {}

    static void run() throws Exception {
        Path scratch = Files.createTempDirectory("mycelium-native-behavior-");
        Path api = scratch.resolve("external/Api.java");
        Path nativeSource = scratch.resolve("native/fixture/NativeCase.java");
        Path runtimeSource = scratch.resolve("runtime/fixture/NativeCase.java");
        Files.createDirectories(api.getParent());
        Files.createDirectories(nativeSource.getParent());
        Files.createDirectories(runtimeSource.getParent());
        String apiProgram = """
                package external;
                public final class Api {
                    public static void register(Runnable callback) { callback.run(); }
                }
                """;
        String common = """
                package fixture;
                import external.Api;
                public final class NativeCase {
                    private NativeCase() {}
                    static Runnable service;
                    public static void reset() { service = null; }
                    static void read() { service.run(); }
                    static void bind() { service = () -> {}; }
                    %s
                    public static void unsafe() { step(); Api.register(NativeCase::read); }
                    public static void safe() { step(); bind(); Api.register(NativeCase::read); }
                    public static void nativeForwardUnsafe() { forward(NativeCase::read); }
                    public static void nativeForwardSafe() { bind(); forward(NativeCase::read); }
                }
                """;
        Files.writeString(api, apiProgram, StandardCharsets.UTF_8);
        Files.writeString(nativeSource, common.formatted("static native void step(); static native void forward(Runnable callback);"), StandardCharsets.UTF_8);
        Files.writeString(runtimeSource, common.formatted("static void step() {} static void forward(Runnable callback) { callback.run(); }"), StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        SelfTest.that(compiler != null, "native behavior: a compiler is available for independent Java 8 programs");
        Path nativeClasses = scratch.resolve("native-classes");
        int nativeExit = compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-d", nativeClasses.toString(),
                api.toString(), nativeSource.toString());
        SelfTest.that(nativeExit == 0, "native behavior: native declarations compile for Java 8");

        // Analyze consumer classes only. The external eager API is a boundary, not part of the fixture input.
        var result = CallbackInitialization.inspect(List.of(nativeClasses.resolve("fixture")), 8);
        Set<String> roots = result.findings().stream().map(f -> f.root().name()).collect(Collectors.toSet());
        SelfTest.that(result.analysisErrors().isEmpty(), "native behavior: opaque call analysis has no errors: " + result.analysisErrors());
        SelfTest.that(roots.equals(Set.of("unsafe", "nativeForwardUnsafe")),
                "native behavior: opaque native calls may return or forward arguments, with known post-call bindings preserved: " + roots);

        // Supply trusted implementations only for this runtime witness; no native library or third-party code is run.
        Path runtimeClasses = scratch.resolve("runtime-classes");
        int runtimeExit = compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-d", runtimeClasses.toString(),
                api.toString(), runtimeSource.toString());
        SelfTest.that(runtimeExit == 0, "native behavior: trusted runtime witnesses compile for Java 8");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{runtimeClasses.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> nativeCase = loader.loadClass("fixture.NativeCase");
            assertFailsWithNpe(nativeCase, "unsafe");
            nativeCase.getMethod("reset").invoke(null);
            nativeCase.getMethod("safe").invoke(null);
            nativeCase.getMethod("reset").invoke(null);
            assertFailsWithNpe(nativeCase, "nativeForwardUnsafe");
            nativeCase.getMethod("reset").invoke(null);
            nativeCase.getMethod("nativeForwardSafe").invoke(null);
        }
        System.out.println("NativeBehaviorOracle: opaque native return and callback-forwarding cases passed.");
    }

    private static void assertFailsWithNpe(Class<?> owner, String method) throws Exception {
        boolean npe = false;
        try {
            owner.getMethod(method).invoke(null);
        } catch (InvocationTargetException wrapped) {
            npe = wrapped.getCause() instanceof NullPointerException;
        }
        SelfTest.that(npe, "native behavior: trusted eager witness for " + method + " dereferences null");
    }
}
