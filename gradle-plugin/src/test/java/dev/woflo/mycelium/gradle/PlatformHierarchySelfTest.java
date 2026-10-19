package dev.woflo.mycelium.gradle;

/** Target release boundaries and missing-model controls, independent of callback findings. */
final class PlatformHierarchySelfTest {
    public static void main(String[] args) throws Exception {
        try (var platform = new PlatformHierarchy(17)) {
            if (platform.header("java/lang/NullPointerException").known()
                    || !platform.description().status().equals("unavailable"))
                throw new AssertionError("Missing compiler must produce unknown ancestry: " + platform.description());
            System.out.println("Missing compiler: unknown ancestry recorded");
        }
    }

    static void run() throws Exception {
        try (var java8 = new PlatformHierarchy(8); var java17 = new PlatformHierarchy(17)) {
            SelfTest.that(java8.description().status().equals("not-queried"), "platform model initializes lazily");
            SelfTest.that(java8.header("java/lang/NullPointerException").parent().equals("java/lang/RuntimeException"),
                    "Java 8 exception header resolves through release model");
            SelfTest.that(!java8.header("java/lang/Record").known(), "Java 8 cannot borrow the host Record header");
            SelfTest.that(java17.header("java/lang/Record").parent().equals("java/lang/Object"),
                    "Java 17 resolves Record from its own API model");
            SelfTest.that(!java17.header("java/lang/MatchException").known(), "Java 17 cannot borrow newer MatchException");
            SelfTest.that(java8.header("java/lang/Object").known() && java8.header("java/lang/Object").parent() == null,
                    "known hierarchy root differs from an unavailable header");
            SelfTest.that(java8.header("java/util/Map$Entry").known(), "binary nested names resolve without class loading");
            SelfTest.that(!java17.header("dev/woflo/mycelium/gradle/PlatformHierarchySelfTest").known(),
                    "platform compiler cannot see consumer or plugin classes");
            SelfTest.that(!java17.header("__MyceliumPlatformHeaderProbe").known(),
                    "compiler session contributes no synthetic type to the platform model");
            SelfTest.that(java8.description().status().equals("available"), "missing type does not invalidate available API model");
        }
        try (var future = new PlatformHierarchy(Runtime.version().feature() + 1)) {
            SelfTest.that(!future.header("java/lang/NullPointerException").known(), "unsupported release cannot silently use host ancestry");
            SelfTest.that(future.description().status().equals("unavailable"), "unsupported release is reported unavailable");
            SelfTest.that(!future.header("java/lang/RuntimeException").known(), "unavailable model remains unknown on repeated queries");
        }
        if (Runtime.version().feature() >= 21) try (var modern = new PlatformHierarchy(21)) {
            SelfTest.that(modern.header("java/lang/MatchException").parent().equals("java/lang/RuntimeException"),
                    "Java 21 resolves exception absent in Java 17");
        }
        try (var old = new CallbackInitialization(8); var modern = new CallbackInitialization(17)) {
            SelfTest.that(old.exceptionSubtype("java/lang/MatchException", "java/lang/RuntimeException") == CallbackInitialization.Relation.UNKNOWN,
                    "exception routing preserves unknown for a type absent in target API");
            SelfTest.that(modern.exceptionSubtype("java/lang/NullPointerException", "java/lang/Error") == CallbackInitialization.Relation.NO,
                    "known target hierarchy can reject a mismatched handler");
        }
        var closed = new PlatformHierarchy(17);
        closed.close();
        SelfTest.that(!closed.header("java/lang/NullPointerException").known(), "closed model does not reopen compiler resources");
        SelfTest.that(PlatformHierarchy.inputs().stream().anyMatch(p -> p.endsWith("ct.sym")),
                "release symbol archive participates in analysis input identity");
        var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--limit-modules", "java.base", "-cp", System.getProperty("java.class.path"), PlatformHierarchySelfTest.class.getName())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        SelfTest.that(process.waitFor() == 0 && output.contains("Missing compiler: unknown ancestry recorded"),
                "runtime without compiler degrades to unknown rather than failing analysis: " + output);
    }
}
