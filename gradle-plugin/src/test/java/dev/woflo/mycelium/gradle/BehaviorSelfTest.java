package dev.woflo.mycelium.gradle;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Independent programs that expose or guard callback state, compiled as Java 8 bytecode. */
final class BehaviorSelfTest {
    public static void main(String[] args) {
        try {
            run();
            SelfTest.finish("Mycelium behavioral analysis self-test");
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(2);
        }
    }
    static void run() throws Exception {
        Path scratch = Files.createTempDirectory("mycelium-callback-flow-");
        Path api = scratch.resolve("external/Api.java");
        Path source = scratch.resolve("fixture/Cases.java");
        Files.createDirectories(api.getParent());
        Files.createDirectories(source.getParent());
        Files.writeString(api, """
                package external;
                public final class Api {
                    public static void register(Runnable callback) { callback.run(); }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(source, """
                package fixture;
                import external.Api;
                public final class Cases {
                    private Cases() {}
                    static Runnable service;
                    public static void reset() { service = null; }
                    static void bind() { service = () -> {}; }
                    static void read() { service.run(); }
                    static void guarded() { if (service != null) service.run(); }
                    static Runnable factory() { return Cases::read; }
                    public static void early() { Api.register(Cases::read); bind(); }
                    public static void safe() { bind(); Api.register(Cases::read); }
                    public static void delayedWriter() { Api.register(Cases::bind); Api.register(Cases::read); }
                    public static void nullGuard() { Api.register(Cases::guarded); }
                    public static void partial(boolean yes) { if (yes) bind(); Api.register(Cases::read); }
                    public static void bothBranches(boolean yes) { if (yes) bind(); else bind(); Api.register(Cases::read); }
                    public static void factoryEarly() { Api.register(factory()); }
                    public static void factorySafe() { bind(); Api.register(factory()); }
                    static void staged() { Runnable callback = factory(); bind(); Api.register(callback); }
                    public static void stagedSafe() { Api.register(Cases::staged); }
                    static void helper() { Api.register(Cases::read); }
                    public static void helperEarly() { helper(); }
                    public static void helperSafe() { bind(); helper(); }
                    public static void anonymousEarly() { Api.register(new Runnable() { public void run() { read(); } }); }
                    public static void anonymousSafe() { bind(); Api.register(new Runnable() { public void run() { read(); } }); }
                    public static void cleared() { bind(); service = null; Api.register(Cases::read); }
                    public static void exceptional() { try { bind(); } catch (RuntimeException ignored) {} Api.register(Cases::read); }
                    static void invoke(Runnable callback) { callback.run(); }
                    static void invokeAfterBinding(Runnable callback) { bind(); callback.run(); }
                    static void invokeBeforeBinding(Runnable callback) { callback.run(); bind(); }
                    static void relayInvoke(Runnable callback) { invoke(callback); }
                    static void describe(Runnable callback) { callback.toString(); }
                    public static void synchronousUnsafe() { invoke(Cases::read); }
                    public static void synchronousSafe() { bind(); invoke(Cases::read); }
                    public static void schedulerBindsSafe() { invokeAfterBinding(Cases::read); }
                    public static void schedulerBindsLateUnsafe() { invokeBeforeBinding(Cases::read); }
                    public static void relayedInvocationUnsafe() { relayInvoke(Cases::read); }
                    public static void notAnInvocationSafe() { describe(Cases::read); }
                    private static void unusedCaller() { externallyCalledUnsafe(); }
                    public static void externallyCalledUnsafe() { Api.register(Cases::read); }
                    public static void callbackBindsSafe() { invoke(Cases::bind); Api.register(Cases::read); }
                    public static void callbackClearsUnsafe() { bind(); invoke(() -> service = null); Api.register(Cases::read); }
                    public static void callbackRebindsSafe() { invoke(() -> service = null); invoke(Cases::bind); Api.register(Cases::read); }
                    public static void callbackClearsLateUnsafe() { invoke(Cases::bind); invoke(() -> service = null); Api.register(Cases::read); }
                    public static void relayedBindingSafe() { relayInvoke(Cases::bind); Api.register(Cases::read); }
                    static Runnable maybe(boolean clear) { return clear ? null : () -> {}; }
                    public static void nullableFactoryUnsafe() { service = maybe(true); Api.register(Cases::read); }
                    static void recurse(Runnable callback, int depth) {
                        if (depth == 0) callback.run(); else recurse(callback, depth - 1);
                    }
                    public static void recursiveBindingSafe() { recurse(Cases::bind, 3); Api.register(Cases::read); }
                    public static void recursiveClearUnsafe() { bind(); recurse(() -> service = null, 3); Api.register(Cases::read); }
                    static void fail() { throw new IllegalStateException(); }
                    public static void unreachableExposure() { fail(); Api.register(Cases::read); }
                    static Runnable nullableAlias = maybe(true);
                    public static void nullableAliasUnsafe() { service = nullableAlias; Api.register(Cases::read); }
                }
                """, StandardCharsets.UTF_8);
        Path classes = scratch.resolve("classes");
        var compiler = ToolProvider.getSystemJavaCompiler();
        SelfTest.that(compiler != null, "behavior: a compiler is available for the independent programs");
        int exit = compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-g:source,lines",
                "-d", classes.toString(), api.toString(), source.toString());
        SelfTest.that(exit == 0, "behavior: the independent callback programs compile for Java 8");
        var result = CallbackInitialization.inspect(List.of(classes.resolve("fixture")));
        boolean cancelled = false;
        try {
            CallbackInitialization.inspect(List.of(classes.resolve("fixture")), 8, progress -> Thread.currentThread().interrupt());
        } catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
        finally { Thread.interrupted(); }
        SelfTest.that(cancelled, "behavior: cancellation interrupts analysis instead of manufacturing a completed report");
        Set<String> roots = result.findings().stream().map(f -> f.root().name()).collect(Collectors.toSet());
        SelfTest.that(result.analysisErrors().isEmpty(), "behavior: all fixture bodies have valid control flow: " + result.analysisErrors());
        SelfTest.that(roots.equals(Set.of("early", "delayedWriter", "partial", "factoryEarly", "helperEarly", "anonymousEarly", "cleared", "exceptional",
                        "synchronousUnsafe", "schedulerBindsLateUnsafe", "relayedInvocationUnsafe", "externallyCalledUnsafe",
                        "callbackClearsUnsafe", "callbackClearsLateUnsafe", "nullableFactoryUnsafe", "nullableAliasUnsafe", "recursiveClearUnsafe")),
                "behavior: risky paths are found while binding, guards, both branches and staged factories remain quiet: " + roots);
        SelfTest.that(result.findings().stream().allMatch(f -> f.dependency().name().equals("service")
                        && f.read().contains("Cases.java:") && f.registration().contains("Cases.java:")),
                "behavior: each candidate identifies the static dependency and both source locations");
        SelfTest.that(result.findings().stream().anyMatch(f -> f.root().name().equals("helperEarly")
                        && !f.registration().contains("#helperEarly")),
                "behavior: helper escapes retain the actual registration site instead of blaming its caller");
        SelfTest.that(result.roots().stream().noneMatch(m -> m.owner().equals("external/Api")),
                "behavior: the external scheduler is outside the analyzed consumer, not a built-in loader model");

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> cases = loader.loadClass("fixture.Cases");
            cases.getMethod("reset").invoke(null);
            boolean failed = false;
            try { cases.getMethod("early").invoke(null); }
            catch (InvocationTargetException exception) { failed = exception.getCause() instanceof NullPointerException; }
            SelfTest.that(failed, "behavior: the planted early callback actually dereferences null under an eager scheduler");
            cases.getMethod("reset").invoke(null);
            cases.getMethod("safe").invoke(null);
            for (String unsafe : List.of("synchronousUnsafe", "schedulerBindsLateUnsafe", "relayedInvocationUnsafe",
                    "externallyCalledUnsafe", "callbackClearsUnsafe", "callbackClearsLateUnsafe", "nullableFactoryUnsafe", "nullableAliasUnsafe", "recursiveClearUnsafe")) {
                cases.getMethod("reset").invoke(null);
                boolean nullDereference = false;
                try { cases.getMethod(unsafe).invoke(null); }
                catch (InvocationTargetException exception) { nullDereference = exception.getCause() instanceof NullPointerException; }
                SelfTest.that(nullDereference, "behavior: synchronous witness actually fails: " + unsafe);
            }
            for (String safe : List.of("synchronousSafe", "schedulerBindsSafe", "notAnInvocationSafe",
                    "callbackBindsSafe", "callbackRebindsSafe", "relayedBindingSafe", "recursiveBindingSafe")) {
                cases.getMethod("reset").invoke(null);
                cases.getMethod(safe).invoke(null);
                SelfTest.that(true, "behavior: synchronous control executes safely: " + safe);
            }
            boolean stopped = false;
            try { cases.getMethod("unreachableExposure").invoke(null); }
            catch (InvocationTargetException exception) { stopped = exception.getCause() instanceof IllegalStateException; }
            SelfTest.that(stopped, "behavior: a non-returning helper prevents the later exposure from executing");
            cases.getMethod("reset").invoke(null);
            cases.getMethod("nullGuard").invoke(null);
            SelfTest.that(true, "behavior: initialized and null-guarded programs actually run safely");
        }

        SelfTest.that(result.callbackContexts() > 0 && result.dependencies().stream().anyMatch(edge -> edge.factType().equals("callback-context")),
                "behavior: callback specializations are explicit dependency-worklist nodes");
        Path permuted = scratch.resolve("permuted-fixture");
        try (var files = Files.walk(classes.resolve("fixture"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                var node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(Files.readAllBytes(file)).accept(node, 0);
                java.util.Collections.reverse(node.methods);
                var output = new org.objectweb.asm.ClassWriter(0); node.accept(output);
                Path destination = permuted.resolve(classes.relativize(file));
                Files.createDirectories(destination.getParent()); Files.write(destination, output.toByteArray());
            }
        }
        var permutedResult = CallbackInitialization.inspect(List.of(permuted.resolve("fixture")), 8);
        SelfTest.that(permutedResult.analysisErrors().isEmpty() && permutedResult.findings().equals(result.findings()),
                "behavior: callback specialization findings survive method-order permutation");

        Path advanced = scratch.resolve("advanced/Advanced.java");
        Path seed = scratch.resolve("advanced/UnrelatedSeed.java");
        Files.createDirectories(advanced.getParent());
        Files.writeString(advanced, """
                package advanced;
                import external.Api;
                public final class Advanced {
                    static Runnable service, other, alias;
                    static long[] values;
                    static boolean choice;
                    static final Runnable CALLBACK = Advanced::read;
                    static void bind() { service = () -> {}; }
                    static void read() { service.run(); }
                    static Runnable nil() { return null; }
                    static void set(Runnable value) { service = value; }
                    static void forward(Runnable callback) { Api.register(callback); }
                    static void relay(Runnable callback) { forward(callback); }
                    public static void parameterUnsafe() { relay(Advanced::read); }
                    public static void parameterSafe() { bind(); relay(Advanced::read); }
                    public static void callbackFieldUnsafe() { Api.register(CALLBACK); }
                    public static void returnedNullUnsafe() { service = nil(); Api.register(Advanced::read); }
                    public static void parameterNullUnsafe() { bind(); set(null); Api.register(Advanced::read); }
                    public static void defaultAliasUnsafe() { service = alias; Api.register(Advanced::read); }
                    public static void mergedGuardUnsafe() {
                        Api.register(() -> { Runnable picked = choice ? service : other; if (picked != null) service.run(); });
                    }
                    public static void mergedGuardSafe() {
                        Api.register(() -> { Runnable picked = choice ? service : other; if (picked != null) picked.run(); });
                    }
                    public static void staleGuardUnsafe() {
                        Api.register(() -> { Runnable old = service; service = null; if (old != null) service.run(); });
                    }
                    public static void guardedAliasSafe() {
                        Api.register(() -> { Runnable old = service; service = null; if (old != null) old.run(); });
                    }
                    public static void clearsInsideUnsafe() { bind(); Api.register(() -> { service = null; read(); }); }
                    public static void clearsAfterSafe() { bind(); Api.register(() -> { read(); service = null; }); }
                    interface Listener { void onEvent(); }
                    static class ListenerBase implements Listener { public void onEvent() { read(); } }
                    static class Inherited extends ListenerBase {}
                    static void register(Listener listener) { external.OtherApi.register(listener); }
                    public static void inheritedUnsafe() { register(new Inherited()); }
                    public static void inheritedSafe() { bind(); register(new Inherited()); }
                    static class Base { public void fire() {} }
                    static class Child extends Base { public void fire() { read(); } }
                    public static void dispatchUnsafe() { Base receiver = new Child(); Api.register(receiver::fire); }
                    public static void dispatchSafe() { bind(); Base receiver = new Child(); Api.register(receiver::fire); }
                    public static void arrayReadUnsafe() { Api.register(() -> { long value = values[0]; }); }
                    public static void arrayWriteUnsafe() { Api.register(() -> { values[0] = 2L; }); }
                    public static void arrayGuardSafe() { Api.register(() -> { if (values != null) values[0] = 2L; }); }
                    public static void arrayBoundSafe() { values = new long[1]; Api.register(() -> { values[0] = 2L; }); }
                    static class Ready {
                        static Runnable value = () -> {};
                        static void read() { value.run(); }
                    }
                    public static void lazyInitSafe() { Api.register(Ready::read); }
                    public static void resetInitializedUnsafe() { Ready.value = null; Api.register(Ready::read); }
                    static void clearReadyInHelper() { Ready.value = null; }
                    static void bindReadyInHelper() { Ready.value = () -> {}; }
                    public static void transitiveInitResetUnsafe() { clearReadyInHelper(); Api.register(Ready::read); }
                    public static void transitiveInitBindingSafe() { bindReadyInHelper(); Api.register(Ready::read); }
                    static class Singleton {
                        static final Singleton INSTANCE = new Singleton();
                        void use() {}
                        static void read() { INSTANCE.use(); }
                    }
                    public static void singletonSafe() { Api.register(Singleton::read); }
                    static class NoisyInitializer {
                        static final Object INSTANCE = new Object();
                        static { Api.register(Advanced::read); }
                        static void use() { INSTANCE.toString(); }
                    }
                    public static void noisySingletonSafe() { Api.register(NoisyInitializer::use); }
                    static class UnsafeInitializer {
                        static final Runnable value;
                        static void read() { value.run(); }
                        static { Api.register(UnsafeInitializer::read); value = () -> {}; }
                    }
                    static class SafeInitializer {
                        static Runnable value;
                        static { value = () -> {}; Api.register(() -> value.run()); }
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(seed, "package advanced; public class UnrelatedSeed { static { Advanced.service = () -> {}; } }",
                StandardCharsets.UTF_8);
        Path otherApi = scratch.resolve("external/OtherApi.java");
        Files.writeString(otherApi, "package external; public class OtherApi { public static void register(Object callback) {} }",
                StandardCharsets.UTF_8);
        Path attacks = scratch.resolve("attacks");
        exit = compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-g:source,lines",
                "-d", attacks.toString(), api.toString(), otherApi.toString(), advanced.toString(), seed.toString());
        SelfTest.that(exit == 0, "behavior: adversarial guard, initialization and dispatch programs compile independently");
        var adversarial = CallbackInitialization.inspect(List.of(attacks.resolve("advanced")));
        Set<String> advancedRoots = adversarial.findings().stream().filter(f -> f.root().owner().equals("advanced/Advanced"))
                .map(f -> f.root().name()).collect(Collectors.toSet());
        SelfTest.that(adversarial.analysisErrors().isEmpty(), "behavior: adversarial bodies have valid frames: " + adversarial.analysisErrors());
        SelfTest.that(advancedRoots.equals(Set.of("parameterUnsafe", "callbackFieldUnsafe", "returnedNullUnsafe",
                        "parameterNullUnsafe", "defaultAliasUnsafe", "mergedGuardUnsafe", "staleGuardUnsafe",
                        "clearsInsideUnsafe", "inheritedUnsafe", "dispatchUnsafe", "resetInitializedUnsafe", "transitiveInitResetUnsafe",
                        "arrayReadUnsafe", "arrayWriteUnsafe")),
                "behavior: red-team regressions are detected while guarded values, prior bindings and lazy class initialization stay quiet: " + advancedRoots);
        SelfTest.that(adversarial.findings().stream().anyMatch(f -> f.root().owner().endsWith("$UnsafeInitializer")
                        && f.root().name().equals("<clinit>"))
                        && adversarial.findings().stream().noneMatch(f -> f.root().owner().endsWith("$SafeInitializer")),
                "behavior: a class initializer cannot borrow its own future assignment");

        SelfTest.that(adversarial.initializationPropagations() > 0
                        && adversarial.dependencies().stream().anyMatch(edge -> edge.factType().equals("initialization-reachability")),
                "behavior: transitive initialization has its own graph closure and dependency edges");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{attacks.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = loader.loadClass("advanced.Advanced");
            boolean failed = false;
            try { type.getMethod("transitiveInitResetUnsafe").invoke(null); }
            catch (InvocationTargetException exception) { failed = exception.getCause() instanceof NullPointerException; }
            type.getMethod("transitiveInitBindingSafe").invoke(null);
            SelfTest.that(failed, "behavior: transitive initializer activation cannot hide a helper's reset; bound control succeeds");
        }

        Path stripped = scratch.resolve("stripped");
        exit = compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-g:none",
                "-d", stripped.toString(), api.toString(), source.toString());
        var noDebug = CallbackInitialization.inspect(List.of(stripped.resolve("fixture")));
        SelfTest.that(exit == 0 && noDebug.findings().size() == result.findings().size()
                        && noDebug.findings().stream().noneMatch(f -> f.registration().contains(".java:")),
                "behavior: absent debug metadata preserves findings with method-level locations");
        for (int release : List.of(8, 11, 16, 17, 21, 25)) {
            if (release > Runtime.version().feature()) continue;
            Path output = scratch.resolve("release-" + release);
            int compiled = compiler.run(null, null, null, "--release", Integer.toString(release), "-Xlint:-options",
                    "-g:source,lines", "-d", output.toString(), api.toString(), source.toString());
            SelfTest.that(compiled == 0, "behavior: independent programs compile for Java " + release);
            var version = CallbackInitialization.inspect(List.of(output.resolve("fixture")), release);
            SelfTest.that(version.analysisErrors().isEmpty()
                            && version.findings().stream().map(f -> f.root().name()).collect(Collectors.toSet()).equals(roots),
                    "behavior: callback relationships survive Java " + release + " compilation");
        }

        Path chainSource = scratch.resolve("chain/Chain.java");
        Files.createDirectories(chainSource.getParent());
        StringBuilder chain = new StringBuilder("package chain; import external.Api; public class Chain { static Runnable service;");
        for (int i = 0; i < 640; i++) chain.append("static void step").append(i).append("() { step").append(i + 1).append("(); }");
        chain.append("static void step640() { service.run(); } public static void entry() { Api.register(Chain::step0); } }");
        Files.writeString(chainSource, chain, StandardCharsets.UTF_8);
        Path chainClasses = scratch.resolve("chain-classes");
        int chainExit = compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-d", chainClasses.toString(),
                api.toString(), chainSource.toString());
        var deep = CallbackInitialization.inspect(List.of(chainClasses.resolve("chain")), 8);
        SelfTest.that(chainExit == 0 && deep.analysisErrors().isEmpty() && deep.findings().size() == 1
                        && deep.findings().get(0).root().name().equals("entry"),
                "behavior: a reverse-ordered 640-call dependency chain reaches a fixed point");
        SelfTest.that(deep.dependencyEdges() >= 640 && deep.summaryEvaluations() < 10 * deep.methods(),
                "behavior: changed summaries revisit their dependents instead of rescanning the entire program");
        SelfTest.that(deep.dependencies().stream().anyMatch(edge -> edge.factType().equals("method-summary")
                        && edge.fact().equals("chain/Chain#step1()V") && edge.dependent().name().equals("step0")),
                "behavior: the dependency graph retains the prerequisite direction of an actual call");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{chainClasses.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            boolean failed = false;
            try { loader.loadClass("chain.Chain").getMethod("entry").invoke(null); }
            catch (InvocationTargetException exception) { failed = exception.getCause() instanceof NullPointerException; }
            SelfTest.that(failed, "behavior: the long dependency chain actually reaches the null dereference");
        }
        var chainNode = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(Files.readAllBytes(chainClasses.resolve("chain/Chain.class")))
                .accept(chainNode, 0);
        java.util.Collections.reverse(chainNode.methods);
        var writer = new org.objectweb.asm.ClassWriter(0);
        chainNode.accept(writer);
        Path reordered = scratch.resolve("reordered/Chain.class");
        Files.createDirectories(reordered.getParent());
        Files.write(reordered, writer.toByteArray());
        var reversed = CallbackInitialization.inspect(List.of(reordered.getParent()), 8);
        SelfTest.that(reversed.analysisErrors().isEmpty() && reversed.findings().equals(deep.findings())
                        && reversed.dependencies().equals(deep.dependencies()),
                "behavior: permuting class-file method order preserves findings and the dependency graph");

        Path legacySource = scratch.resolve("legacy/Legacy.java");
        Files.createDirectories(legacySource.getParent());
        Files.writeString(legacySource, """
                package legacy;
                public class Legacy {
                    static Runnable service;
                    static void invoke(Runnable callback) { callback.run(); }
                    public static void reset() { service = null; }
                    public static void unsafe() { invoke(new Runnable() { public void run() { service.run(); } }); }
                    public static void safe() {
                        service = new Runnable() { public void run() {} };
                        invoke(new Runnable() { public void run() { service.run(); } });
                    }
                }
                """, StandardCharsets.UTF_8);
        for (int release : List.of(7, 8, 11, 16, 17, 21, 25)) {
            if (release > Runtime.version().feature() || (release == 7 && Runtime.version().feature() != 17)) continue;
            Path output = scratch.resolve("legacy-" + release);
            int compiled = compiler.run(null, null, null, "--release", Integer.toString(release), "-Xlint:-options",
                    "-g:source,lines", "-d", output.toString(), legacySource.toString());
            var version = CallbackInitialization.inspect(List.of(output), release);
            SelfTest.that(compiled == 0 && version.analysisErrors().isEmpty()
                            && version.findings().stream().map(f -> f.root().name()).collect(Collectors.toSet()).equals(Set.of("unsafe")),
                    "behavior: anonymous callback relationships survive Java " + release + " compilation");
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Class<?> legacy = loader.loadClass("legacy.Legacy");
                boolean failed = false;
                try { legacy.getMethod("unsafe").invoke(null); }
                catch (InvocationTargetException exception) { failed = exception.getCause() instanceof NullPointerException; }
                legacy.getMethod("reset").invoke(null);
                legacy.getMethod("safe").invoke(null);
                SelfTest.that(failed, "behavior: Java " + release + " anonymous unsafe and safe controls agree with execution");
            }
        }
        Path releaseSource = scratch.resolve("releases/multi/ReleaseCase.java");
        Files.createDirectories(releaseSource.getParent());
        String releaseProgram = "package multi; import external.Api; public class ReleaseCase { "
                + "static Runnable service; static void read() { service.run(); } "
                + "public static void entry() { %s } }";
        Path baseClasses = scratch.resolve("base8"), versionClasses = scratch.resolve("variant17");
        Files.writeString(releaseSource, releaseProgram.formatted("service = () -> {}; Api.register(ReleaseCase::read);"),
                StandardCharsets.UTF_8);
        int baseExit = compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-d", baseClasses.toString(),
                api.toString(), releaseSource.toString());
        Files.writeString(releaseSource, releaseProgram.formatted("Api.register(ReleaseCase::read); service = () -> {};"),
                StandardCharsets.UTF_8);
        int variantExit = compiler.run(null, null, null, "--release", "17", "-d", versionClasses.toString(),
                api.toString(), releaseSource.toString());
        SelfTest.that(baseExit == 0 && variantExit == 0, "behavior: independent multi-release variants compile");
        Path multiRelease = scratch.resolve("multi-release.jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(multiRelease), manifest)) {
            for (var variant : java.util.Map.of("multi/ReleaseCase.class", baseClasses,
                    "META-INF/versions/17/multi/ReleaseCase.class", versionClasses).entrySet()) {
                jar.putNextEntry(new java.util.jar.JarEntry(variant.getKey()));
                jar.write(Files.readAllBytes(variant.getValue().resolve("multi/ReleaseCase.class")));
                jar.closeEntry();
            }
        }
        var baseView = CallbackInitialization.inspect(List.of(multiRelease), 8);
        var versionView = CallbackInitialization.inspect(List.of(multiRelease), 17);
        SelfTest.that(baseView.analysisErrors().isEmpty() && versionView.analysisErrors().isEmpty()
                        && baseView.classes() == 1 && versionView.classes() == 1
                        && baseView.findings().isEmpty() && versionView.findings().size() == 1,
                "behavior: multi-release jars select the target runtime view instead of merging duplicate definitions");

        Path broken = scratch.resolve("broken/Invalid.class");
        Files.createDirectories(broken.getParent());
        Files.write(broken, new byte[]{0, 1, 2});
        var malformed = CallbackInitialization.inspect(List.of(broken.getParent()));
        SelfTest.that(malformed.classes() == 0 && malformed.analyzedBodies() == 0
                        && malformed.analysisErrors().stream().anyMatch(error -> error.contains("Invalid.class: unreadable class")),
                "behavior: malformed input records an incomplete analysis instead of a clean report");
        var duplicates = CallbackInitialization.inspect(List.of(classes.resolve("fixture"), stripped.resolve("fixture")));
        SelfTest.that(duplicates.analysisErrors().stream().anyMatch(error -> error.contains("duplicate class fixture/Cases")),
                "behavior: ambiguous class definitions record an incomplete analysis");
        BehaviorOracle.run();
        NativeBehaviorOracle.run();
        ClassInitializationOracle.run();
        BehaviorLabSelfTest.run();
        var empty = CallbackInitialization.inspect(List.of(scratch.resolve("absent")));
        SelfTest.that(empty.analyzedBodies() == 0 && empty.classes() == 0,
                "behavior: absent compiled input is not reported as inspected code");
    }
}
