package dev.woflo.mycelium.gradle;

import com.sun.source.util.JavacTask;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.tools.*;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Release-specific API headers from javac; unavailable headers never fall back to the host runtime. */
final class PlatformHierarchy implements AutoCloseable {
    record Header(boolean known, String parent) { }
    record Description(String model, int targetJava, String status, String reason) { }
    private final int targetJava;
    private Session session;
    private String status = "not-queried";
    private String reason = "No platform ancestry lookup was needed.";

    PlatformHierarchy(int targetJava) { this.targetJava = targetJava; }

    static List<Path> inputs() {
        Path home = Path.of(System.getProperty("java.home"), "lib");
        return List.of(home.resolve("modules"), home.resolve("ct.sym"));
    }

    Description description() { return new Description("javac --release API headers", targetJava, status, reason); }

    Header header(String internalName) {
        if (status.equals("unavailable") || status.equals("closed")) return new Header(false, null);
        try {
            if (session == null) {
                session = new Session(targetJava);
                status = "available";
                reason = "Public API model only; absent or unresolved headers remain unknown.";
            }
            return session.header(internalName);
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            status = "unavailable";
            reason = unavailable.getClass().getSimpleName() + ": " + String.valueOf(unavailable.getMessage());
            if (session != null) {
                try { session.close(); } catch (IOException ignored) { }
                session = null;
            }
            return new Header(false, null);
        }
    }

    @Override public void close() throws IOException {
        if (session != null) { session.close(); session = null; }
        status = "closed";
    }

    // Keep compiler linkage lazy so a runtime without jdk.compiler can still report unknown ancestry.
    private static final class Session implements AutoCloseable {
        private final StandardJavaFileManager files;
        private final Elements elements;

        Session(int release) throws IOException {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IllegalStateException("No system Java compiler is available");
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8);
            try {
                files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
                files.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
                var source = new SimpleJavaFileObject(URI.create("string:///__MyceliumPlatformHeaderProbe.java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return "";
                    }
                };
                var task = (JavacTask) compiler.getTask(new StringWriter(), files, diagnostics,
                        List.of("--release", Integer.toString(release), "-proc:none", "-Xlint:-options"), null, List.of(source));
                task.parse();
                task.analyze();
                if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
                    throw new IllegalStateException("Compiler could not resolve the Java " + release + " API model");
                elements = task.getElements();
            } catch (IOException | RuntimeException | LinkageError failure) {
                try { files.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        Header header(String internalName) {
            String binary = internalName.replace('/', '.');
            TypeElement type = elements.getTypeElement(binary);
            if (type == null && binary.indexOf('$') >= 0) type = elements.getTypeElement(binary.replace('$', '.'));
            if (type == null || !elements.getBinaryName(type).toString().equals(binary)) return new Header(false, null);
            var parent = type.getSuperclass();
            if (parent.getKind() == javax.lang.model.type.TypeKind.NONE) return new Header(true, null);
            if (parent.getKind() != javax.lang.model.type.TypeKind.DECLARED) return new Header(false, null);
            var declaration = ((DeclaredType) parent).asElement();
            return declaration instanceof TypeElement ancestor
                    ? new Header(true, elements.getBinaryName(ancestor).toString().replace('.', '/')) : new Header(false, null);
        }

        @Override public void close() throws IOException { files.close(); }
    }
}
