package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Maintainer-only corpus experiments. Third-party classes are read and mutated, never executed. */
public final class BehaviorLab {
    static final String PREFIX = "mycelium$lab$";
    static final String BOUNDARY = "mycelium/lab/Boundary";
    static final String SCOPE = "static bytecode experiment; no native game or third-party runtime validation";
    record Slot(String entry, String owner, String name, String descriptor, int instruction) {}
    record Mutation(Slot slot, Path control, Path mutant) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--worker")) {
            var result = CallbackInitialization.inspect(List.of(path(args[1])), Integer.parseInt(args[2]), progress -> {
                System.out.println("analysis progress: " + progress);
                System.out.flush();
            });
            JsonObject report = new GsonBuilder().create().toJsonTree(result).getAsJsonObject();
            report.addProperty("scope", SCOPE);
            report.addProperty("nativeEventOrderProven", false);
            report.addProperty("complete", result.analysisErrors().isEmpty() && result.analyzedBodies() > 0 && !result.roots().isEmpty());
            write(path(args[3]), report);
            return;
        }
        if (args.length == 1 && args[0].equals("--self-test")) {
            BehaviorLabSelfTest.run();
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("BehaviorLab <corpus plan.json> <output directory>");
        scan(Path.of(args[0]).toAbsolutePath(), Path.of(args[1]).toAbsolutePath());
    }

    static Path path(String value) { return Path.of(URI.create(value)); }
    static void write(Path file, JsonObject value) throws Exception {
        file = file.toAbsolutePath();
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            try (var output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value, output);
                output.write("\n");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
    static String digest(Path file, String algorithm) throws Exception {
        var digest = MessageDigest.getInstance(algorithm);
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int size; (size = input.read(buffer)) >= 0; ) digest.update(buffer, 0, size);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void scan(Path plan, Path output) throws Exception {
        JsonObject input = JsonParser.parseString(Files.readString(plan)).getAsJsonObject();
        if (input.get("schema").getAsInt() != 1 || input.getAsJsonArray("artifacts").isEmpty())
            throw new IllegalArgumentException("Expected a nonempty schema-1 corpus plan");
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("report.json"));
        Path launcher = launcher(output.resolve("worker-launcher.jar"));
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("plannedArtifacts", input.getAsJsonArray("artifacts").size());
        report.addProperty("scope", SCOPE);
        report.addProperty("planSha256", digest(plan, "SHA-256"));
        report.addProperty("analyzerSha256", analyzerFingerprint(Path.of(CallbackInitialization.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        JsonObject environment = new JsonObject();
        environment.addProperty("javaRuntime", System.getProperty("java.runtime.version"));
        environment.addProperty("vendor", System.getProperty("java.vendor"));
        Path image = Path.of(System.getProperty("java.home"), "lib", "modules");
        if (Files.isRegularFile(image)) environment.addProperty("platformRuntimeSha256", digest(image, "SHA-256"));
        JsonObject libraries = new JsonObject();
        for (Class<?> library : List.of(ClassReader.class, ClassNode.class, Analyzer.class)) {
            Path location = Path.of(library.getProtectionDomain().getCodeSource().getLocation().toURI());
            libraries.addProperty(library.getName(), Fingerprint.ofDirectory(location));
        }
        environment.add("libraries", libraries);
        report.add("analysisEnvironment", environment);
        JsonArray artifacts = new JsonArray();
        int failures = 0, index = 0;
        report.add("artifacts", artifacts);
        report.addProperty("finishedArtifacts", 0);
        report.addProperty("complete", false);
        report.addProperty("failures", 0);
        write(output.resolve("report.json"), report);
        for (var element : input.getAsJsonArray("artifacts")) {
            JsonObject row = element.getAsJsonObject().deepCopy();
            JsonObject result = new JsonObject();
            result.add("artifact", row);
            try {
                Path jar = Path.of(row.get("path").getAsString()).toAbsolutePath();
                String original = digest(jar, "SHA-512");
                result.addProperty("originalSha512", original);
                if (!original.equals(row.get("sha512").getAsString()) || Files.size(jar) != row.get("size").getAsLong())
                    throw new IllegalArgumentException("Corpus artifact no longer matches its lock");
                int target = row.get("target_java").getAsInt();
                Path directory = output.resolve(String.format(Locale.ROOT, "%03d", index++));
                Files.createDirectories(directory);
                JsonObject baseline = worker(launcher, jar, target, directory.resolve("baseline.json"));
                result.add("baseline", baseline);
                boolean complete = baseline.has("complete") && baseline.get("complete").getAsBoolean();
                if (!complete) {
                    failures++;
                    result.addProperty("mutation", "not run: baseline analysis incomplete");
                    result.addProperty("mutationDetected", false);
                } else {
                    Mutation mutation = mutate(jar, target, 20261018L, directory);
                    if (mutation == null) {
                        result.addProperty("mutation", "unavailable: no branch-free initializer with a single definite reference binding");
                    } else {
                        result.add("slot", new GsonBuilder().create().toJsonTree(mutation.slot));
                        JsonObject control = worker(launcher, mutation.control, target, directory.resolve("control.json"));
                        JsonObject broken = worker(launcher, mutation.mutant, target, directory.resolve("mutant.json"));
                        int controlCandidates = candidates(control, mutation.slot), brokenCandidates = candidates(broken, mutation.slot);
                        boolean detected = controlCandidates == 0 && brokenCandidates > 0
                                && control.has("complete") && control.get("complete").getAsBoolean()
                                && broken.has("complete") && broken.get("complete").getAsBoolean();
                        result.addProperty("controlCandidates", controlCandidates);
                        result.addProperty("mutantCandidates", brokenCandidates);
                        result.addProperty("mutationDetected", detected);
                        result.addProperty("controlSha512", digest(mutation.control, "SHA-512"));
                        result.addProperty("mutantSha512", digest(mutation.mutant, "SHA-512"));
                        if (!detected) failures++;
                    }
                }
                boolean unchanged = original.equals(digest(jar, "SHA-512"));
                result.addProperty("originalUnchanged", unchanged);
                if (!unchanged) failures++;
            } catch (Exception failure) {
                result.addProperty("experimentError", failure.toString());
                failures++;
            }
            artifacts.add(result);
            report.add("artifacts", artifacts);
            report.addProperty("failures", failures);
            report.addProperty("finishedArtifacts", artifacts.size());
            report.addProperty("complete", artifacts.size() == input.getAsJsonArray("artifacts").size() && failures == 0);
            write(output.resolve("report.json"), report);
            System.out.println("Corpus " + artifacts.size() + "/" + input.getAsJsonArray("artifacts").size()
                    + ": " + row.get("filename").getAsString() + "; cumulative failures=" + failures);
        }
        System.out.println("Behavior corpus: " + artifacts.size() + " artifacts, " + failures + " incomplete or failed experiment(s). " + output.resolve("report.json"));
        if (failures != 0) System.exit(1);
    }

    static int candidates(JsonObject report, Slot slot) {
        if (!report.has("findings")) return -1;
        int count = 0;
        for (var element : report.getAsJsonArray("findings")) {
            var finding = element.getAsJsonObject();
            var root = finding.getAsJsonObject("root");
            var field = finding.getAsJsonObject("dependency");
            if (root.get("owner").getAsString().equals(slot.owner) && root.get("name").getAsString().equals(PREFIX + "entry")
                    && field.get("owner").getAsString().equals(slot.owner) && field.get("name").getAsString().equals(slot.name)) count++;
        }
        return count;
    }

    static String analyzerFingerprint(Path code) throws Exception {
        if (Files.isRegularFile(code)) return digest(code, "SHA-256");
        try (var files = Files.list(code.resolve("dev/woflo/mycelium/gradle"))) {
            var implementation = files.filter(file -> {
                String name = file.getFileName().toString();
                return name.equals("CallbackInitialization.class") || (name.startsWith("CallbackInitialization$") && name.endsWith(".class"));
            }).sorted().toList();
            if (implementation.isEmpty()) throw new IllegalArgumentException("No analyzer classes at " + code);
            return Fingerprint.ofDirectories(implementation);
        }
    }

    static Path launcher(Path file) throws Exception {
        Manifest manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, BehaviorLab.class.getName());
        List<String> classpath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            classpath.add(Path.of(entry).toAbsolutePath().toUri().toASCIIString());
        attributes.put(Attributes.Name.CLASS_PATH, String.join(" ", classpath));
        try (var jar = new JarOutputStream(Files.newOutputStream(file), manifest)) { }
        return file;
    }

    static JsonObject worker(Path launcher, Path jar, int target, Path output) throws Exception {
        Path log = output.resolveSibling(output.getFileName() + ".log");
        Files.deleteIfExists(output); // A stale successful report must never satisfy this launch.
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java" + (File.separatorChar == '\\' ? ".exe" : "")).toString(),
                "-jar", launcher.toString(), "--worker", jar.toUri().toASCIIString(), Integer.toString(target), output.toUri().toASCIIString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Thread shutdownCleanup = new Thread(() -> terminateWorker(process), "mycelium-behavior-worker-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownCleanup);
        try {
            process.waitFor();
        } catch (InterruptedException cancelled) {
            terminateWorker(process);
            Thread.currentThread().interrupt();
            throw cancelled;
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdownCleanup); }
            catch (IllegalStateException shuttingDown) { /* shutdown hook owns child cleanup */ }
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
            JsonObject failure = new JsonObject(); failure.addProperty("complete", false); failure.addProperty("error", "worker exit " + process.exitValue() + "; see " + log.getFileName());
            write(output, failure); return failure;
        }
        return workerSummary(output);
    }

    private static void terminateWorker(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    // Keep aggregate receipts small; full graphs and findings remain in the worker report.
    static JsonObject workerSummary(Path file) throws Exception {
        JsonObject summary = new JsonObject();
        JsonArray witnesses = new JsonArray();
        try (var input = new com.google.gson.stream.JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            input.beginObject();
            while (input.hasNext()) {
                String name = input.nextName();
                if (name.equals("dependencies") || name.equals("roots") || name.equals("findings")) {
                    int count = 0;
                    input.beginArray();
                    while (input.hasNext()) {
                        count++;
                        if (name.equals("findings")) {
                            JsonObject finding = JsonParser.parseReader(input).getAsJsonObject();
                            if (finding.getAsJsonObject("root").get("name").getAsString().equals(PREFIX + "entry")) witnesses.add(finding);
                        } else input.skipValue();
                    }
                    input.endArray();
                    summary.addProperty(name + "Count", count);
                } else summary.add(name, JsonParser.parseReader(input));
            }
            input.endObject();
        }
        summary.add("findings", witnesses);
        summary.addProperty("fullReport", file.getFileName().toString());
        summary.addProperty("fullReportSha256", digest(file, "SHA-256"));
        return summary;
    }

    static Mutation mutate(Path jar, int target, long seed, Path output) throws Exception {
        List<Slot> slots = new ArrayList<>();
        try (var archive = new JarFile(jar.toFile(), false, ZipFile.OPEN_READ, Runtime.Version.parse(Integer.toString(Math.max(8, target))))) {
            for (var entry : archive.versionedStream().sorted(Comparator.comparing(ZipEntry::getName)).toList()) {
                if (!entry.getName().endsWith(".class") || !entry.getRealName().equals(entry.getName()) || entry.getSize() > 2 * 1024 * 1024) continue;
                ClassNode node = new ClassNode();
                try (var input = archive.getInputStream(entry)) { new ClassReader(input).accept(node, 0); }
                if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM | Opcodes.ACC_MODULE)) != 0
                        || (node.access & Opcodes.ACC_PUBLIC) == 0 || node.methods.stream().anyMatch(m -> m.name.startsWith(PREFIX))) continue;
                for (MethodNode method : node.methods) {
                    if (!method.name.equals("<clinit>") || !method.tryCatchBlocks.isEmpty()) continue;
                    if (java.util.Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof JumpInsnNode || i instanceof TableSwitchInsnNode || i instanceof LookupSwitchInsnNode)) continue;
                    var frames = new Analyzer<>(new SourceInterpreter(Opcodes.ASM9) {
                        @Override public SourceValue copyOperation(AbstractInsnNode i, SourceValue v) { return v; }
                    }).analyze(node.name, method);
                    for (int i = 0; i < method.instructions.size(); i++) {
                        if (!(method.instructions.get(i) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                                || !field.owner.equals(node.name) || frames[i] == null || !CallbackInitialization.reference(field.desc)) continue;
                        boolean declared = node.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)
                                && (f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL) && f.value == null);
                        long writes = java.util.Arrays.stream(method.instructions.toArray()).filter(n -> n instanceof FieldInsnNode f
                                && f.getOpcode() == Opcodes.PUTSTATIC && f.owner.equals(field.owner) && f.name.equals(field.name) && f.desc.equals(field.desc)).count();
                        var value = frames[i].getStack(frames[i].getStackSize() - 1);
                        boolean concrete = !value.insns.isEmpty() && value.insns.stream().allMatch(n -> n.getOpcode() == Opcodes.NEW
                                || n.getOpcode() == Opcodes.ANEWARRAY || n.getOpcode() == Opcodes.NEWARRAY || n.getOpcode() == Opcodes.MULTIANEWARRAY
                                || n instanceof LdcInsnNode ldc && (ldc.cst instanceof String || ldc.cst instanceof org.objectweb.asm.Type));
                        if (declared && writes == 1 && concrete) slots.add(new Slot(entry.getName(), node.name, field.name, field.desc, i));
                    }
                }
            }
        }
        if (slots.isEmpty()) return null;
        slots.sort(Comparator.comparing(slot -> Fingerprint.ofString(seed + ":" + slot.entry + ":" + slot.name)));
        Slot slot = slots.get(0);
        Files.createDirectories(output);
        Path control = output.resolve("control.jar"), mutant = output.resolve("mutant.jar");
        rewrite(jar, control, slot, false);
        rewrite(jar, mutant, slot, true);
        return new Mutation(slot, control, mutant);
    }

    static void rewrite(Path source, Path destination, Slot slot, boolean breakBinding) throws Exception {
        try (var archive = new JarFile(source.toFile(), false); var zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (var entry : archive.stream().sorted(Comparator.comparing(ZipEntry::getName)).toList()) {
                String upper = entry.getName().toUpperCase(Locale.ROOT);
                if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) continue;
                var copy = new ZipEntry(entry.getName()); copy.setTime(0); zip.putNextEntry(copy);
                if (entry.getName().equals(slot.entry)) {
                    ClassNode node = new ClassNode();
                    try (var input = archive.getInputStream(entry)) { new ClassReader(input).accept(node, 0); }
                    if (breakBinding) for (MethodNode method : node.methods) if (method.name.equals("<clinit>"))
                        method.instructions.set(method.instructions.get(slot.instruction), new InsnNode(Opcodes.POP));
                    addWitness(node, slot);
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); zip.write(writer.toByteArray());
                } else if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    try (var input = archive.getInputStream(entry)) { zip.write(stripManifestDigests(input)); }
                } else try (var input = archive.getInputStream(entry)) { input.transferTo(zip); }
                zip.closeEntry();
            }
            var helper = new ZipEntry(slot.owner + "$MyceliumLabCallback.class"); helper.setTime(0); zip.putNextEntry(helper);
            zip.write(helper(slot)); zip.closeEntry();
        }
    }

    static byte[] stripManifestDigests(java.io.InputStream input) throws Exception {
        Manifest manifest = new Manifest(input);
        stripDigestAttributes(manifest.getMainAttributes());
        var sections = manifest.getEntries().entrySet().iterator();
        while (sections.hasNext()) {
            var section = sections.next();
            stripDigestAttributes(section.getValue());
            if (section.getValue().isEmpty()) sections.remove();
        }
        var output = new ByteArrayOutputStream();
        manifest.write(output);
        return output.toByteArray();
    }

    private static void stripDigestAttributes(Attributes attributes) {
        attributes.keySet().removeIf(key -> key.toString().toUpperCase(Locale.ROOT).contains("-DIGEST"));
    }

    static void addWitness(ClassNode node, Slot slot) {
        MethodNode read = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, PREFIX + "read", "()V", null, null);
        read.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, slot.owner, slot.name, slot.descriptor));
        read.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false));
        read.instructions.add(new InsnNode(Opcodes.POP)); read.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(read);
        MethodNode entry = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, PREFIX + "entry", "()V", null, null);
        entry.instructions.add(new TypeInsnNode(Opcodes.NEW, slot.owner + "$MyceliumLabCallback")); entry.instructions.add(new InsnNode(Opcodes.DUP));
        entry.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, slot.owner + "$MyceliumLabCallback", "<init>", "()V", false));
        entry.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOUNDARY, "register", "(Ljava/lang/Runnable;)V", false));
        entry.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(entry);
    }

    static byte[] helper(Slot slot) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                slot.owner + "$MyceliumLabCallback", null, "java/lang/Object", new String[]{"java/lang/Runnable"});
        var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        var run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null); run.visitCode();
        run.visitMethodInsn(Opcodes.INVOKESTATIC, slot.owner, PREFIX + "read", "()V", false);
        run.visitInsn(Opcodes.RETURN); run.visitMaxs(0, 0); run.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
}
