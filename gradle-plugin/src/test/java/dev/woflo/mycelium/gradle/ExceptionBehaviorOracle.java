package dev.woflo.mycelium.gradle;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Executes each entry with fresh static state, independently of the analyzer's transfer functions. */
final class ExceptionBehaviorOracle {
    static void run() throws Exception {
        relations();
        domainLaws();
        Path scratch = Files.createTempDirectory("mycelium-exception-oracle-");
        try {
            Path api = scratch.resolve("src/external/Api.java"), source = scratch.resolve("src/outcomes/Entry.java");
            Files.createDirectories(api.getParent()); Files.createDirectories(source.getParent());
            Files.writeString(api, "package external; public class Api { public static int calls; public static void register(Runnable r) { calls++; r.run(); } }");
            Files.writeString(source, """
                    package outcomes;
                    import external.Api;
                    public class Entry {
                        static Runnable service;
                        static final Runnable READY = () -> {};
                        static void bind() { service = READY; }
                        static void read() { service.run(); }
                        static void clearThrow() { Failure e = new Failure(); service = null; throw e; }
                        static void repairedThrow() { Failure e = new Failure(); service = null; bind(); throw e; }
                        static void normalOnlyClear(boolean fail) { Failure e = new Failure(); if (fail) throw e; service = null; }
                        static void relay() { clearThrow(); }
                        static void rethrow() { try { clearThrow(); } catch (Failure e) { throw e; } }
                        static void recursive(int n) { if (n == 0) clearThrow(); else recursive(n - 1); }
                        static void callback(Runnable r) { r.run(); }
                        static void kinds(boolean clear) {
                            Failure first = new Failure(); Alternative second = new Alternative();
                            if (clear) { service = null; throw first; } else throw second;
                        }
                        static void rethrowKinds(boolean clear) { try { kinds(clear); } catch (RuntimeException e) { throw e; } }
                        static void unionRethrow(boolean clear) { try { kinds(clear); } catch (Failure | Alternative e) { throw e; } }
                        static void finallyReturns() { try { clearThrow(); } finally { bind(); return; } }
                        static void implicit(int divisor) { service = null; int value = 1 / divisor; }
                        static void argumentThrow(Runnable value) { Failure e = new Failure(); service = value; throw e; }
                        static void argumentRelay(Runnable value) { argumentThrow(value); }
                        static Runnable identity(Runnable value) { return value; }
                        static Runnable alias;
                        static void snapshot(Runnable value, boolean old) {
                            Failure e = new Failure(); alias = value; Runnable captured = alias; alias = READY;
                            if (old) service = captured; else service = alias; throw e;
                        }
                        static void exposeArgument(Runnable value) { service = value; Api.register(Entry::read); }
                        static void readArgument(Runnable value) { service = value; service.run(); }
                        static void relayRead(Runnable value) { readArgument(value); }
                        public static void conditionalReadUnsafe() { bind(); Api.register(() -> readArgument(null)); }
                        public static void conditionalReadSafe() { bind(); Api.register(() -> readArgument(READY)); }
                        public static void relayedReadUnsafe() { bind(); Api.register(() -> relayRead(null)); }
                        public static void relayedReadSafe() { bind(); Api.register(() -> relayRead(READY)); }
                        static void forwardExposure(Runnable value, Runnable callback) { service = value; Api.register(callback); }
                        static void normalArgument(Runnable value, boolean direct) { if (direct) service = identity(value); else service = READY; }
                        static void wideArgument(long ignored, Runnable value) { argumentThrow(value); }
                        static void recursiveArgument(Runnable value, int depth) { if (depth == 0) argumentThrow(value); else recursiveArgument(value, depth - 1); }
                        public static void normalArgumentUnsafe() { bind(); normalArgument(null, true); Api.register(Entry::read); }
                        public static void normalArgumentSafe() { bind(); normalArgument(READY, false); Api.register(Entry::read); }
                        public static void wideArgumentUnsafe() { bind(); try { wideArgument(0L, null); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void wideArgumentSafe() { bind(); try { wideArgument(0L, READY); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void recursiveArgumentUnsafe() { bind(); try { recursiveArgument(null, 3); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void recursiveArgumentSafe() { bind(); try { recursiveArgument(READY, 3); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void argumentIdentityUnsafe() { bind(); try { argumentThrow(identity(null)); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void argumentSnapshotUnsafe() { bind(); try { snapshot(null, true); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void argumentSnapshotSafe() { bind(); try { snapshot(READY, false); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void exposedArgumentUnsafe() { bind(); exposeArgument(null); }
                        public static void exposedArgumentSafe() { bind(); exposeArgument(READY); }
                        public static void forwardedArgumentUnsafe() { bind(); forwardExposure(null, Entry::read); }
                        public static void forwardedArgumentSafe() { bind(); forwardExposure(READY, Entry::read); }
                        public static void argumentUnsafe() { bind(); try { argumentThrow(null); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void argumentSafe() { bind(); try { argumentThrow(READY); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void argumentRelayUnsafe() { bind(); try { argumentRelay(null); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void catchUnsafe() { bind(); try { clearThrow(); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void catchSafe() { bind(); try { clearThrow(); } catch (Failure e) { bind(); Api.register(Entry::read); } }
                        public static void finallyUnsafe() { bind(); try { clearThrow(); } finally { Api.register(Entry::read); } }
                        public static void finallySafe() { bind(); try { clearThrow(); } finally { bind(); Api.register(Entry::read); } }
                        public static void relayUnsafe() { bind(); try { relay(); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void rethrowUnsafe() { bind(); try { rethrow(); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void recursiveUnsafe() { bind(); try { recursive(3); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void callbackUnsafe() { bind(); try { callback(Entry::clearThrow); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void repairedSafe() { bind(); try { repairedThrow(); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void normalClearSafe() { bind(); try { normalOnlyClear(true); } catch (Failure e) { Api.register(Entry::read); } }
                        public static void wrongHandlerSafe() { bind(); try { clearThrow(); } catch (IllegalArgumentException e) { Api.register(Entry::read); } catch (Failure e) {} }
                        public static void firstHandlerSafe() { bind(); try { clearThrow(); } catch (Failure e) { bind(); } catch (RuntimeException e) { Api.register(Entry::read); } }
                        public static void firstHandlerUnsafe() { bind(); try { clearThrow(); } catch (Failure e) { Api.register(Entry::read); } catch (RuntimeException e) { bind(); } }
                        public static void implicitUnsafe() { bind(); try { implicit(0); } catch (ArithmeticException e) { Api.register(Entry::read); } }
                        public static void implicitSafe() { bind(); try { implicit(0); } catch (ArithmeticException e) { bind(); Api.register(Entry::read); } }
                        public static void noThrowSafe() { bind(); try { service = null; int n = 1; n++; } catch (RuntimeException e) { Api.register(Entry::read); } }
                        public static void nestedFinallyUnsafe() { bind(); try { try { clearThrow(); } finally { int ignored = 0; } } catch (Failure e) { Api.register(Entry::read); } }
                        public static void nullThrowUnsafe() { bind(); try { service = null; throw (RuntimeException) null; } catch (NullPointerException e) { Api.register(Entry::read); } }
                        public static void correlatedSafe() { bind(); try { rethrowKinds(false); } catch (Alternative e) { Api.register(Entry::read); } catch (Failure e) {} }
                        public static void correlatedUnsafe() { bind(); try { rethrowKinds(true); } catch (Alternative e) {} catch (Failure e) { Api.register(Entry::read); } }
                        public static void unionSafe() { bind(); try { unionRethrow(false); } catch (Alternative e) { Api.register(Entry::read); } catch (Failure e) {} }
                        public static void unionUnsafe() { bind(); try { unionRethrow(true); } catch (Alternative e) {} catch (Failure e) { Api.register(Entry::read); } }
                        public static void finallyReturnSafe() { bind(); finallyReturns(); Api.register(Entry::read); }
                        public static void finallyOverrideSafe() { bind(); try { try { clearThrow(); } finally { bind(); throw new Alternative(); } } catch (Alternative e) { Api.register(Entry::read); } }
                        public static void finallyOverrideUnsafe() { bind(); try { try { clearThrow(); } finally { service = null; throw new Alternative(); } } catch (Alternative e) { Api.register(Entry::read); } }
                        public static void resourceUnsafe() { bind(); try (Closing x = new Closing()) {} catch (Failure e) { Api.register(Entry::read); } }
                        public static void resourceSafe() { bind(); try (Repairing x = new Repairing()) {} catch (Failure e) { Api.register(Entry::read); } }
                    }
                    final class Failure extends RuntimeException {}
                    final class Alternative extends RuntimeException {}
                    final class Closing implements AutoCloseable { public void close() { Entry.clearThrow(); } }
                    final class Repairing implements AutoCloseable { public void close() { Entry.repairedThrow(); } }
                    """);
            List<String> entries = List.of("catchUnsafe", "catchSafe", "finallyUnsafe", "finallySafe", "relayUnsafe",
                    "rethrowUnsafe", "recursiveUnsafe", "callbackUnsafe", "repairedSafe", "normalClearSafe", "wrongHandlerSafe",
                    "firstHandlerSafe", "firstHandlerUnsafe", "implicitUnsafe", "implicitSafe", "noThrowSafe",
                    "nestedFinallyUnsafe", "nullThrowUnsafe", "correlatedSafe", "correlatedUnsafe", "unionSafe", "unionUnsafe",
                    "finallyReturnSafe", "finallyOverrideSafe", "finallyOverrideUnsafe", "resourceUnsafe", "resourceSafe",
                    "argumentUnsafe", "argumentSafe", "argumentRelayUnsafe", "argumentIdentityUnsafe", "argumentSnapshotUnsafe",
                    "argumentSnapshotSafe", "exposedArgumentUnsafe", "exposedArgumentSafe", "forwardedArgumentUnsafe", "forwardedArgumentSafe",
                    "normalArgumentUnsafe", "normalArgumentSafe", "wideArgumentUnsafe", "wideArgumentSafe", "recursiveArgumentUnsafe", "recursiveArgumentSafe",
                    "conditionalReadUnsafe", "conditionalReadSafe", "relayedReadUnsafe", "relayedReadSafe");
            Set<String> expected = entries.stream().filter(name -> name.endsWith("Unsafe")).collect(Collectors.toSet());
            for (int release : List.of(8, 17, 25)) {
                if (release > Runtime.version().feature()) continue;
                Path classes = scratch.resolve("classes-" + release);
                int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", Integer.toString(release),
                        "-Xlint:-options", "-g", "-d", classes.toString(), api.toString(), source.toString());
                SelfTest.that(compiled == 0, "exception oracle: compile Java " + release);
                var result = CallbackInitialization.inspect(List.of(classes.resolve("outcomes")), release);
                Set<String> actual = result.findings().stream().filter(f -> f.root().owner().equals("outcomes/Entry"))
                        .map(f -> f.root().name()).collect(Collectors.toSet());
                SelfTest.that(result.analysisErrors().isEmpty() && actual.equals(expected),
                        "exception oracle: outcome effects and typed handlers, Java " + release + ": " + actual);
                for (String name : entries) {
                    try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                        boolean failed = false;
                        try { loader.loadClass("outcomes.Entry").getMethod(name).invoke(null); }
                        catch (InvocationTargetException thrown) {
                            if (thrown.getCause() instanceof NullPointerException) failed = true;
                            else if (!thrown.getCause().getClass().getName().equals("outcomes.Failure")) throw thrown;
                        }
                        SelfTest.that(failed == expected.contains(name), "exception oracle: fresh runtime " + name + ", Java " + release);
                        int calls = loader.loadClass("external.Api").getField("calls").getInt(null);
                        int expectedCalls = Set.of("wrongHandlerSafe", "firstHandlerSafe", "noThrowSafe").contains(name) ? 0 : 1;
                        SelfTest.that(calls == expectedCalls,
                                "exception oracle: callback reachability " + name + ", Java " + release + ": " + calls);
                    }
                }
                Path entryClass = classes.resolve("outcomes/Entry.class");
                var node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(Files.readAllBytes(entryClass)).accept(node, 0);
                java.util.Collections.reverse(node.methods);
                var writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer);
                Files.write(entryClass, writer.toByteArray());
                var reordered = CallbackInitialization.inspect(List.of(classes.resolve("outcomes")), release);
                SelfTest.that(reordered.analysisErrors().isEmpty() && reordered.findings().equals(result.findings())
                                && reordered.dependencies().equals(result.dependencies()),
                        "exception oracle: method-order invariance, Java " + release);
            }
        } finally { SelfTest.delete(scratch); }
    }

    static void relations() {
        var analyzer = new CallbackInitialization();
        var unknown = new org.objectweb.asm.tree.ClassNode();
        unknown.name = "fixture/Unknown"; unknown.superName = "absent/Parent";
        analyzer.classes.put(unknown.name, unknown);
        SelfTest.that(analyzer.catches(new CallbackInitialization.Thrown("java/lang/IllegalStateException", true),
                        "java/lang/RuntimeException") == CallbackInitialization.Coverage.COVERS,
                "exception relation: an exact subtype is wholly handled");
        SelfTest.that(analyzer.catches(new CallbackInitialization.Thrown("java/lang/Error", false),
                        "java/lang/RuntimeException") == CallbackInitialization.Coverage.DISJOINT,
                "exception relation: disjoint class ancestry stays disjoint");
        SelfTest.that(analyzer.catches(new CallbackInitialization.Thrown("java/lang/RuntimeException", false),
                        "java/lang/IllegalStateException") == CallbackInitialization.Coverage.POSSIBLE,
                "exception relation: a narrow catch covers only part of an abstract superclass");
        SelfTest.that(analyzer.catches(new CallbackInitialization.Thrown(unknown.name, true),
                        "java/lang/RuntimeException") == CallbackInitialization.Coverage.POSSIBLE,
                "exception relation: missing ancestry cannot prove a catch impossible");
    }

    static void domainLaws() {
        var method = new CallbackInitialization.Method("Domain", "step", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        var first = new CallbackInitialization.Parameter(method, 0);
        var second = new CallbackInitialization.Parameter(method, 1);
        var field = new CallbackInitialization.Field("Domain", "value", "Ljava/lang/Object;");
        var marker = new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP);
        var origin = new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.GETSTATIC);
        var mutable = new java.util.HashSet<>(Set.of(first));
        var bits = new java.util.BitSet(); bits.set(1);
        var snapshot = new CallbackInitialization.State(Set.of(field), Set.of(), bits, Set.of(), java.util.Map.of(),
                java.util.Map.of(field, mutable), java.util.Map.of());
        mutable.add(second); bits.clear();
        SelfTest.that(snapshot.conditional().get(field).equals(Set.of(first)) && snapshot.initialized().get(1),
                "exception domain: mutable construction inputs cannot change a published state");
        var states = List.of(snapshot,
                new CallbackInitialization.State(Set.of(field), Set.of(), new java.util.BitSet(), Set.of(), java.util.Map.of(),
                        java.util.Map.of(field, Set.of(second)), java.util.Map.of(origin, new CallbackInitialization.ReferenceDependencies(true, Set.of()))),
                new CallbackInitialization.State(Set.of(), Set.of(), new java.util.BitSet(), Set.of(field)),
                new CallbackInitialization.State(Set.of(field), Set.of(), new java.util.BitSet(), Set.of(),
                        java.util.Map.of(marker, Set.of(new CallbackInitialization.Thrown("java/lang/RuntimeException", false))),
                        java.util.Map.of(), java.util.Map.of(origin, CallbackInitialization.ReferenceDependencies.NONE)));
        boolean commutative = true, associative = true, idempotent = true;
        for (var a : states) {
            idempotent &= CallbackInitialization.joinState(a, a).equals(a);
            for (var b : states) {
                commutative &= CallbackInitialization.joinState(a, b).equals(CallbackInitialization.joinState(b, a));
                for (var c : states) associative &= CallbackInitialization.joinState(CallbackInitialization.joinState(a, b), c)
                        .equals(CallbackInitialization.joinState(a, CallbackInitialization.joinState(b, c)));
            }
        }
        SelfTest.that(commutative && associative && idempotent,
                "exception domain: state joins are associative, commutative and idempotent");
        var references = new java.util.ArrayList<CallbackInitialization.ReferenceDependencies>();
        for (int mask = 0; mask < 8; mask++) {
            var parameters = new java.util.HashSet<CallbackInitialization.Parameter>();
            if ((mask & 2) != 0) parameters.add(first);
            if ((mask & 4) != 0) parameters.add(second);
            references.add(new CallbackInitialization.ReferenceDependencies((mask & 1) != 0, parameters));
        }
        boolean laws = true;
        for (var a : references) for (var b : references) for (var c : references)
            laws &= a.join(a).equals(a) && a.join(b).equals(b.join(a)) && a.join(b).join(c).equals(a.join(b.join(c)))
                    && a.join(CallbackInitialization.ReferenceDependencies.NONE).equals(a);
        SelfTest.that(laws, "exception domain: exhaustive two-parameter reference joins obey semilattice laws");
    }
}
