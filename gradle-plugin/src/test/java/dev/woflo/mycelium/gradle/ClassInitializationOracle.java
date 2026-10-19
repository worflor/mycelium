package dev.woflo.mycelium.gradle;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Uses fresh class loaders so one entry's initialization cannot repair another entry. */
final class ClassInitializationOracle {
    static void run() throws Exception {
        resolutionControls();
        Path scratch = Files.createTempDirectory("mycelium-initialization-oracle-");
        Path api = scratch.resolve("src/external/Api.java");
        Path source = scratch.resolve("src/initialization/Entry.java");
        Files.createDirectories(api.getParent());
        Files.createDirectories(source.getParent());
        Files.writeString(api, "package external; public class Api { public static void register(Runnable r) { r.run(); } }");
        Files.writeString(source, """
                package initialization;
                import external.Api;
                public class Entry {
                    public static void plainUnsafe() { Api.register(Parent::read); }
                    public static void inheritedMethodUnsafe() { Child.noop(); Api.register(Parent::read); }
                    public static void inheritedPrimitiveUnsafe() { int ignored = Child.NUMBER; Api.register(Parent::read); }
                    public static void inheritedReferenceUnsafe() { Object ignored = Child.VALUE; Api.register(Parent::read); }
                    public static void inheritedFactoryUnsafe() { Api.register(Child.callback()); }
                    public static void childMethodSafe() { Child.touch(); Api.register(Parent::read); }
                    public static void childFieldSafe() { int ignored = Child.marker; Api.register(Parent::read); }
                }
                class Parent {
                    static Runnable service;
                    static int NUMBER;
                    static Object VALUE = new Object();
                    static void noop() {}
                    static void read() { service.run(); }
                    static Runnable callback() { return Parent::read; }
                }
                class Child extends Parent {
                    static { service = () -> {}; }
                    static int marker = 1;
                    static void touch() {}
                }
                """);
        Set<String> expected = Set.of("plainUnsafe", "inheritedMethodUnsafe", "inheritedPrimitiveUnsafe",
                "inheritedReferenceUnsafe", "inheritedFactoryUnsafe");
        for (int release : List.of(8, 17, 25)) {
            if (release > Runtime.version().feature()) continue;
            Path classes = scratch.resolve("classes-" + release);
            int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", Integer.toString(release),
                    "-Xlint:-options", "-g", "-d", classes.toString(), api.toString(), source.toString());
            SelfTest.that(exit == 0, "initialization oracle: compile for Java " + release);
            var result = CallbackInitialization.inspect(List.of(classes.resolve("initialization")), release);
            Set<String> diagnostics = result.findings().stream().filter(f -> f.root().owner().equals("initialization/Entry"))
                    .map(f -> f.root().name()).collect(Collectors.toSet());
            SelfTest.that(result.analysisErrors().isEmpty() && diagnostics.equals(expected),
                    "initialization oracle: declaring owners and inherited factory returns, Java " + release + ": " + diagnostics);
            for (String entry : List.of("plainUnsafe", "inheritedMethodUnsafe", "inheritedPrimitiveUnsafe",
                    "inheritedReferenceUnsafe", "inheritedFactoryUnsafe", "childMethodSafe", "childFieldSafe")) {
                try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
                    boolean failed = false;
                    try { loader.loadClass("initialization.Entry").getMethod(entry).invoke(null); }
                    catch (InvocationTargetException exception) {
                        if (!(exception.getCause() instanceof NullPointerException)) throw exception;
                        failed = true;
                    }
                    SelfTest.that(failed == expected.contains(entry),
                            "initialization oracle: fresh JVM state agrees for " + entry + ", Java " + release);
                }
            }
        }
    }

    static void resolutionControls() {
        var analyzer = new CallbackInitialization();
        var parent = node("Parent", null, List.of(), false);
        var marker = node("Marker", "java/lang/Object", List.of(), true);
        var empty = node("Empty", "java/lang/Object", List.of(), true);
        var child = node("Child", "Parent", List.of("Empty", "Marker"), false);
        var unknown = node("Unknown", "Parent", List.of("Missing"), false);
        for (var type : List.of(parent, marker, empty, child, unknown)) analyzer.classes.put(type.name, type);
        var parentField = new CallbackInitialization.Field("Parent", "VALUE", "I");
        var interfaceField = new CallbackInitialization.Field("Marker", "VALUE", "I");
        analyzer.declaredFields.addAll(List.of(parentField, interfaceField));
        SelfTest.that(interfaceField.equals(analyzer.resolveField(new CallbackInitialization.Field("Child", "VALUE", "I"))),
                "initialization resolution: superinterface field precedes superclass, and interfaces have no Object field-search branch");
        SelfTest.that(analyzer.resolveField(new CallbackInitialization.Field("Unknown", "VALUE", "I")) == null,
                "initialization resolution: an unavailable earlier interface prevents a fabricated declaring owner");
        var ownField = new CallbackInitialization.Field("Child", "OWN", "I");
        analyzer.declaredFields.add(ownField);
        SelfTest.that(ownField.equals(analyzer.resolveField(ownField)), "initialization resolution: own primitive field resolves first");
        var nativeMethod = new CallbackInitialization.Method("Parent", "nativeStep", "()V");
        analyzer.methods.put(nativeMethod, new MethodNode(
                Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE, "nativeStep", "()V", null, null));
        SelfTest.that(nativeMethod.equals(analyzer.staticMethod(new CallbackInitialization.Method("Child", "nativeStep", "()V"))),
                "initialization resolution: native declarations retain their actual owner");
        var interfaceMethod = new CallbackInitialization.Method("Marker", "step", "()V");
        analyzer.methods.put(interfaceMethod, new MethodNode(
                Opcodes.ACC_STATIC, "step", "()V", null, null));
        SelfTest.that(analyzer.staticMethod(new CallbackInitialization.Method("Child", "step", "()V")) == null,
                "initialization resolution: interface static methods are not inherited by implementing classes");
        String previous = "Parent";
        for (int index = 0; index < 256; index++) {
            String name = "Level" + index;
            analyzer.classes.put(name, node(name, previous, List.of(), false));
            previous = name;
        }
        SelfTest.that(parentField.equals(analyzer.resolveField(new CallbackInitialization.Field(previous, "VALUE", "I")))
                        && nativeMethod.equals(analyzer.staticMethod(new CallbackInitialization.Method(previous, "nativeStep", "()V"))),
                "initialization resolution: deep inheritance is iterative and has no depth cutoff");
    }

    static ClassNode node(String name, String parent, List<String> interfaces, boolean isInterface) {
        var node = new ClassNode();
        node.name = name;
        node.superName = parent;
        node.interfaces.addAll(interfaces);
        if (isInterface) node.access = Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        return node;
    }
}
