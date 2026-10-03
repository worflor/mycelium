package dev.woflo.mycelium.gradle;

import org.gradle.api.GradleException;
import org.gradle.jvm.toolchain.JavaLauncher;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Resolves the ordered effective bootstrap archives reported by a selected Java launcher. */
public final class RuntimeBootClasspath {
    private static final Duration LAUNCHER_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_LAUNCHER_OUTPUT = 1024 * 1024;

    private RuntimeBootClasspath() { }

    /** Resolve Java 8's boot archive property, recording the selected launcher's ignored standard template entries. */
    public static Resolution resolve(JavaLauncher launcher, int expectedJavaFeature) {
        checkFeature(launcher.getMetadata().getLanguageVersion().asInt(), expectedJavaFeature);
        if (expectedJavaFeature != 8) {
            throw new GradleException("Mycelium: runtime boot classpath currently supports Java 8 launchers only");
        }
        Path executable = launcher.getExecutablePath().getAsFile().toPath();
        Path installation = launcher.getMetadata().getInstallationPath().getAsFile().toPath();
        Process process;
        try {
            process = new ProcessBuilder(executable.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new GradleException("Mycelium: could not inspect selected Java launcher: " + executable, e);
        }
        byte[] output = capture(process, LAUNCHER_TIMEOUT, MAX_LAUNCHER_OUTPUT);
        if (process.exitValue() != 0) throw new GradleException("Mycelium: selected Java launcher could not report boot archives: " + executable);
        return parse(new String(output, StandardCharsets.UTF_8), File.pathSeparatorChar, installation);
    }

    static byte[] capture(Process process, Duration timeout, int maximumBytes) {
        ExecutorService reader = null;
        Future<byte[]> output = null;
        RuntimeException primaryFailure = null;
        try {
            if (timeout.isNegative() || timeout.isZero() || maximumBytes <= 0) {
                throw new IllegalArgumentException("launcher capture requires a positive timeout and output bound");
            }
            reader = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mycelium-launcher-output");
                thread.setDaemon(true);
                return thread;
            });
            output = reader.submit(() -> readBounded(process, maximumBytes));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (process.isAlive()) {
                if (output.isDone()) output.get();
                if (!process.isAlive()) break;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new GradleException("Mycelium: selected Java launcher exceeded " + timeout.toMillis() + " ms");
                process.waitFor(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 50), TimeUnit.MILLISECONDS);
            }
            long remaining = Math.max(0, deadline - System.nanoTime());
            try {
                return output.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw new GradleException("Mycelium: selected Java launcher output did not close within " + timeout.toMillis() + " ms", e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            primaryFailure = new GradleException("Mycelium: interrupted while inspecting selected Java launcher", e);
            throw primaryFailure;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            primaryFailure = cause instanceof OutputLimitException
                    ? new GradleException(cause.getMessage(), cause)
                    : new GradleException("Mycelium: could not read selected Java launcher output", cause);
            throw primaryFailure;
        } catch (RuntimeException e) {
            primaryFailure = e;
            throw e;
        } finally {
            try {
                closeAndStop(process);
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure == null) throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
            } finally {
                if (output != null) output.cancel(true);
                if (reader != null) reader.shutdownNow();
            }
        }
    }

    private static byte[] readBounded(Process process, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] chunk = new byte[8192];
        try (var input = process.getInputStream()) {
            int read;
            while ((read = input.read(chunk)) >= 0) {
                if (output.size() + read > maximumBytes) throw new OutputLimitException(maximumBytes);
                output.write(chunk, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static void closeAndStop(Process process) {
        boolean wasInterrupted = Thread.interrupted();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean(wasInterrupted);
        RuntimeException failure = null;
        try {
            if (process.isAlive()) {
                process.destroy();
                if (!waitForUninterrupted(process, Duration.ofMillis(200), interrupted)) {
                    process.destroyForcibly();
                    if (!waitForUninterrupted(process, Duration.ofSeconds(2), interrupted)) {
                        throw new GradleException("Mycelium: failed to terminate selected Java launcher after forced cleanup");
                    }
                }
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        try { process.getInputStream().close(); }
        catch (IOException e) {
            if (failure == null) failure = new GradleException("Mycelium: failed to close selected Java launcher output", e);
            else failure.addSuppressed(e);
        } finally {
            if (interrupted.get()) Thread.currentThread().interrupt();
        }
        if (failure != null) throw failure;
    }

    private static boolean waitForUninterrupted(Process process, Duration timeout,
            java.util.concurrent.atomic.AtomicBoolean interrupted) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return true;
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
        return true;
    }

    private static final class OutputLimitException extends IOException {
        OutputLimitException(int maximumBytes) { super("Mycelium: selected Java launcher output exceeded " + maximumBytes + " bytes"); }
    }

    static Resolution parse(String output, char separator, Path installation) {
        List<String> entries = parseEntries(output, separator);
        Path selectedHome = installation.toAbsolutePath().normalize();
        Path expectedClasses = selectedHome.resolve("jre").resolve("classes");
        Path expectedRsaPlaceholder = selectedHome.resolve("jre").resolve("lib").resolve("sunrsasign.jar");
        Path expectedRt = selectedHome.resolve("jre").resolve("lib").resolve("rt.jar");
        List<File> archives = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        for (String value : entries) {
            Path root = Path.of(value).toAbsolutePath().normalize();
            if (root.equals(expectedClasses) || root.equals(expectedRsaPlaceholder)) {
                if (!Files.exists(root)) {
                    omitted.add(root + " (absent; ignored by the selected Java 8 launcher)");
                    continue;
                }
                if (root.equals(expectedClasses) && Files.isDirectory(root)) {
                    try (var children = Files.list(root)) {
                        if (children.findAny().isEmpty()) {
                            omitted.add(root + " (empty; optional Java 8 classes directory)");
                            continue;
                        }
                    } catch (IOException e) {
                        throw new GradleException("Mycelium: could not inspect Java 8 classes directory: " + root, e);
                    }
                    throw new GradleException("Mycelium: non-empty Java 8 classes directory is unsupported as a capsule runtime root: " + root);
                }
            }
            if (!Files.isRegularFile(root) || !root.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                throw new GradleException("Mycelium: Java 8 boot archive is missing or not a JAR file: " + root);
            }
            archives.add(root.toFile());
        }
        if (archives.isEmpty()) throw new GradleException("Mycelium: Java 8 launcher reported no boot archives");
        if (!archives.contains(expectedRt.toFile())) throw new GradleException("Mycelium: Java 8 boot classpath omitted mandatory rt.jar: " + expectedRt);
        return new Resolution(List.copyOf(archives), List.copyOf(omitted));
    }

    static List<String> parseEntries(String output, char separator) {
        String[] lines = output.split("\\R");
        int property = -1;
        int indentation = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("sun.boot.class.path =")) {
                property = i;
                indentation = lines[i].length() - lines[i].stripLeading().length();
                break;
            }
        }
        if (property < 0) throw new GradleException("Mycelium: selected Java 8 launcher did not report sun.boot.class.path");
        List<String> segments = new ArrayList<>();
        segments.add(lines[property].trim().substring("sun.boot.class.path =".length()).trim());
        for (int i = property + 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int currentIndent = line.length() - line.stripLeading().length();
            if (trimmed.isEmpty() || currentIndent <= indentation || trimmed.contains(" = ")) break;
            segments.add(trimmed);
        }
        List<String> entries = new ArrayList<>();
        for (int segment = 0; segment < segments.size(); segment++) {
            String[] items = segments.get(segment).split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
            for (int item = 0; item < items.length; item++) {
                String value = items[item].trim();
                if (value.isEmpty() && segment + 1 < segments.size() && item == items.length - 1) continue;
                if (value.isEmpty()) throw new GradleException("Mycelium: Java 8 sun.boot.class.path contains an empty root");
                entries.add(value);
            }
        }
        return List.copyOf(entries);
    }

    static void checkFeature(int actual, int expected) {
        if (actual != expected) throw new GradleException("Mycelium: runtime boot classpath expected Java " + expected
                + " but selected launcher is Java " + actual);
    }

    /** Ordered effective boot archives and standard launcher-template roots it explicitly omits. */
    public record Resolution(List<File> archives, List<String> omittedRoots) { }
}
