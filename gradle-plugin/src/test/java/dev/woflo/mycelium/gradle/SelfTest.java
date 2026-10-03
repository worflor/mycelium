package dev.woflo.mycelium.gradle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The plugin's self-tests, in the repository's own dependency-free style (a main, labelled checks, exit code = failures;
 * see the harness's {@code Suite} and {@code SeededIdentity}). Run by {@code ./gradlew -p gradle-plugin selfTest}, and
 * by the Mycelium root's {@code check}.
 *
 * <p>The repository checks are proven the only honest way: a scratch copy of the repository is broken in one specific
 * way, and the check must name that break (compared with an unbroken copy, so the test holds whatever else the
 * repository states that day). A check never shown to fire is not evidence.
 */
public final class SelfTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "..").toAbsolutePath().normalize();
        eras(root);
        registry();
        integrations();
        evidence();
        loaders(root);
        shipping();
        artifactJars();
        downgrading();
        privateLibraries();
        runtimeBootClasspath();
        runtimeBootCapture();
        capsuleLinkage();
        surface();
        repository(root);
        System.out.println();
        System.out.println("Mycelium plugin self-test: " + (FAILURES.isEmpty() ? "PASS" : "FAIL") + " (" + assertions
                + " assertions, " + FAILURES.size() + " failed)");
        FAILURES.forEach(f -> System.out.println("  - " + f));
        System.exit(FAILURES.size());
    }

    static void that(boolean ok, String label) {
        assertions++;
        if (!ok) {
            FAILURES.add(label);
        }
    }

    static void artifactJars() {
        that(MyceliumPlugin.isArtifactJar(List.of(), "probeJar"), "artifact jars: default preserves existing attachment");
        that(MyceliumPlugin.isArtifactJar(List.of("jar"), "jar"), "artifact jars: selected production jar receives code");
        that(!MyceliumPlugin.isArtifactJar(List.of("jar"), "probeJar"), "artifact jars: independent probe receives no artifact code");
        for (List<String> names : List.of(List.of("jar", "jar"), List.of(""), List.of("missing"))) {
            boolean rejected = false;
            try {
                MyceliumPlugin.validateArtifactJars(names, Set.of("jar", "probeJar"));
            } catch (org.gradle.api.GradleException expected) {
                rejected = expected.getMessage().contains("artifactJars");
            }
            that(rejected, "artifact jars: invalid declaration rejected " + names);
        }
    }

    static void capsuleLinkage() throws Exception {
        Path dir = Files.createTempDirectory("mycelium-capsule-linkage-");
        try {
            Path runtime = dir.resolve("runtime.jar");
            Path archive = dir.resolve("capsule.jar");
            Path nested = dir.resolve("payload.jar");
            writePrivateJar(runtime, java.util.Map.of(
                    "java/lang/Object.class", objectClass(),
                    "java/lang/String.class", emptyClass("java/lang/String", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC),
                    "runtime/Base.class", baseClass(),
                    "runtime/Child.class", childClass(),
                    "runtime/Face.class", interfaceClass(),
                    "runtime/FaceChild.class", interfaceChildClass()));
            writePrivateJar(nested, java.util.Map.of("consumer/Caller.class", inheritedCaller("consumer/Caller", "runtime/Child", "answer", "()I", org.objectweb.asm.Opcodes.INVOKEVIRTUAL),
                    "consumer/FieldCaller.class", fieldCaller("consumer/FieldCaller", "runtime/Child", "answerField", org.objectweb.asm.Opcodes.GETFIELD),
                    "consumer/InterfaceCaller.class", inheritedCaller("consumer/InterfaceCaller", "runtime/FaceChild", "value", "()I", org.objectweb.asm.Opcodes.INVOKEINTERFACE),
                    "consumer/ObjectMethodCaller.class", interfaceObjectMethodCaller(),
                    "consumer/ArrayOwnerCaller.class", arrayOwnerCalls()));
            writePrivateJar(archive, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(nested)));
            Linkage.CapsuleResult valid = Linkage.verifyCapsule(archive, 50, java.util.Map.of("recon-payload/payload.jar", 52),
                    List.of(runtime), null, "", "", List.of());
            that(valid.classFiles() == 6 && valid.memberReferences() >= 6,
                    "capsule: inherited class and interface methods, fields, Object methods, and array-owner methods resolve");
            that(valid.runtimeRootHashes().get(0).contains("="), "capsule: exact runtime root hash is recorded");
            Path conflictingRoot = dir.resolve("conflicting-runtime.jar");
            writePrivateJar(conflictingRoot, java.util.Map.of("runtime/Base.class", baseClassWithStaticAnswer()));
            Linkage.CapsuleResult ordered = Linkage.verifyCapsule(archive, 50, java.util.Map.of("recon-payload/payload.jar", 52),
                    List.of(runtime, conflictingRoot), null, "", "", List.of());
            that(ordered.shadowedRuntimeClasses().stream().anyMatch(line -> line.startsWith("runtime/Base: ")),
                    "capsule: classpath order is honored and shadowed definitions are reported");
            boolean reversedRejected = false;
            try { Linkage.verifyCapsule(archive, 50, java.util.Map.of("recon-payload/payload.jar", 52),
                    List.of(conflictingRoot, runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { reversedRejected = e.getMessage().contains("access for static member"); }
            that(reversedRejected, "capsule: reversing overlapping roots changes the selected runtime definition");

            Path badMember = dir.resolve("bad-member.jar");
            Path badPayload = dir.resolve("bad-payload.jar");
            writePrivateJar(badPayload, java.util.Map.of("consumer/Caller.class", inheritedCaller("consumer/Caller", "runtime/Child", "missing", "()I", org.objectweb.asm.Opcodes.INVOKEVIRTUAL)));
            writePrivateJar(badMember, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(badPayload)));
            that(rejectedCapsule(badMember, runtime, null, "absent runtime method"),
                    "capsule: absent inherited runtime member is rejected");

            Path badField = dir.resolve("bad-field.jar");
            Path badFieldPayload = dir.resolve("bad-field-payload.jar");
            writePrivateJar(badFieldPayload, java.util.Map.of("consumer/FieldCaller.class",
                    fieldCaller("consumer/FieldCaller", "runtime/Child", "missingField", org.objectweb.asm.Opcodes.GETFIELD)));
            writePrivateJar(badField, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(badFieldPayload)));
            that(rejectedCapsule(badField, runtime, null, "absent runtime field"),
                    "capsule: absent inherited runtime field is rejected");

            Path badStatic = dir.resolve("bad-static.jar");
            Path staticPayload = dir.resolve("static-payload.jar");
            writePrivateJar(staticPayload, java.util.Map.of("consumer/Caller.class", inheritedCaller("consumer/Caller", "runtime/Base", "staticValue", "()I", org.objectweb.asm.Opcodes.INVOKEVIRTUAL)));
            writePrivateJar(badStatic, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(staticPayload)));
            that(rejectedCapsule(badStatic, runtime, null, "access for static member"),
                    "capsule: staticness mismatch is rejected");

            Path badOpcode = dir.resolve("bad-opcode.jar");
            Path badOpcodePayload = dir.resolve("bad-opcode-payload.jar");
            writePrivateJar(badOpcodePayload, java.util.Map.of("consumer/Caller.class",
                    inheritedCaller("consumer/Caller", "runtime/Base", "answer", "()I", org.objectweb.asm.Opcodes.INVOKEINTERFACE)));
            writePrivateJar(badOpcode, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(badOpcodePayload)));
            that(rejectedCapsule(badOpcode, runtime, null, "wrong owner kind"),
                    "capsule: invokeinterface cannot target a class owner");

            Path privateRuntime = dir.resolve("private-runtime.jar");
            writePrivateJar(privateRuntime, java.util.Map.of("java/lang/Object.class", objectClass(),
                    "runtime/Base.class", baseClassWithPrivateAnswer()));
            Path privateAccess = dir.resolve("private-access.jar");
            Path privateAccessPayload = dir.resolve("private-access-payload.jar");
            writePrivateJar(privateAccessPayload, java.util.Map.of("consumer/Caller.class",
                    inheritedCaller("consumer/Caller", "runtime/Base", "privateAnswer", "()I", org.objectweb.asm.Opcodes.INVOKEVIRTUAL)));
            writePrivateJar(privateAccess, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(privateAccessPayload)));
            that(rejectedCapsule(privateAccess, privateRuntime, null, "cannot access private member"),
                    "capsule: private runtime members are inaccessible from another caller");
            Path samePackagePrivate = dir.resolve("same-package-private.jar");
            Path samePackagePayload = dir.resolve("same-package-private-payload.jar");
            writePrivateJar(samePackagePayload, java.util.Map.of("runtime/PrivateCaller.class",
                    inheritedCaller("runtime/PrivateCaller", "runtime/Base", "privateAnswer", "()I", org.objectweb.asm.Opcodes.INVOKEVIRTUAL)));
            writePrivateJar(samePackagePrivate, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(samePackagePayload)));
            that(rejectedCapsule(samePackagePrivate, privateRuntime, null, "cannot access private member"),
                    "capsule: package equality does not grant private access");

            Path ownPrivate = dir.resolve("own-private.jar");
            writePrivateJar(ownPrivate, java.util.Map.of("capsule/Entry.class", ownPrivateCaller()));
            boolean ownPrivatePassed = true;
            try { Linkage.verifyCapsule(ownPrivate, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { ownPrivatePassed = false; }
            that(ownPrivatePassed, "capsule: a class may access its own private member");

            Path nestmates = dir.resolve("nestmates.jar");
            writePrivateJar(nestmates, java.util.Map.of("capsule/Host.class", nestHost(true),
                    "capsule/Neighbor.class", nestNeighbor()));
            boolean nestmatePassed = true;
            try { Linkage.verifyCapsule(nestmates, 55, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { nestmatePassed = false; }
            that(nestmatePassed, "capsule: reciprocal Java 11 nest membership grants private access");
            Path unlistedNestmate = dir.resolve("unlisted-nestmate.jar");
            writePrivateJar(unlistedNestmate, java.util.Map.of("capsule/Host.class", nestHost(false),
                    "capsule/Neighbor.class", nestNeighbor()));
            boolean unlistedRejected = false;
            try { Linkage.verifyCapsule(unlistedNestmate, 55, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { unlistedRejected = e.getMessage().contains("cannot access private member"); }
            that(unlistedRejected, "capsule: a NestHost claim without reciprocal NestMembers does not grant private access");

            Path protectedRuntime = dir.resolve("protected-runtime.jar");
            writePrivateJar(protectedRuntime, java.util.Map.of("java/lang/Object.class", objectClass(),
                    "runtime/Base.class", baseClassWithProtectedStatic()));
            Path protectedAccess = dir.resolve("protected-access.jar");
            writePrivateJar(protectedAccess, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "consumer/Child.class", protectedStaticCaller()));
            boolean protectedStaticPassed = true;
            try { Linkage.verifyCapsule(protectedAccess, 50, java.util.Map.of(), List.of(protectedRuntime), null, "", "", List.of()); }
            catch (IllegalStateException e) { protectedStaticPassed = false; }
            that(protectedStaticPassed, "capsule: a subclass may use an inherited protected static method");

            Path protectedInstanceRuntime = dir.resolve("protected-instance-runtime.jar");
            writePrivateJar(protectedInstanceRuntime, java.util.Map.of("java/lang/Object.class", objectClass(),
                    "runtime/Base.class", baseClassWithProtectedInstance()));
            Path protectedInstanceAccess = dir.resolve("protected-instance-access.jar");
            writePrivateJar(protectedInstanceAccess, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "consumer/Child.class", protectedInstanceCaller("consumer/Child", "consumer/Child")));
            boolean protectedInstancePassed = true;
            try { Linkage.verifyCapsule(protectedInstanceAccess, 50, java.util.Map.of(), List.of(protectedInstanceRuntime), null, "", "", List.of()); }
            catch (IllegalStateException e) { protectedInstancePassed = false; }
            that(protectedInstancePassed, "capsule: a subclass receiver may use an inherited protected instance method");
            Path unrelatedProtectedAccess = dir.resolve("unrelated-protected-access.jar");
            writePrivateJar(unrelatedProtectedAccess, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "consumer/Other.class", protectedInstanceCaller("consumer/Other", "runtime/Base")));
            boolean unrelatedProtectedRejected = false;
            try { Linkage.verifyCapsule(unrelatedProtectedAccess, 50, java.util.Map.of(), List.of(protectedInstanceRuntime), null, "", "", List.of()); }
            catch (IllegalStateException e) { unrelatedProtectedRejected = e.getMessage().contains("cannot prove package/protected access"); }
            that(unrelatedProtectedRejected, "capsule: unrelated callers cannot use a protected instance method");

            Path crossLoaderPackage = dir.resolve("cross-loader-package.jar");
            Path crossLoaderPayload = dir.resolve("cross-loader-package-payload.jar");
            writePrivateJar(crossLoaderPayload, java.util.Map.of("capsule/PackageCaller.class", packagePrivateCaller()));
            writePrivateJar(crossLoaderPackage, java.util.Map.of(
                    "capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "capsule/PackageOwner.class", packagePrivateOwner(),
                    "recon-payload/payload.jar", Files.readAllBytes(crossLoaderPayload)));
            boolean crossLoaderRejected = false;
            try { Linkage.verifyCapsule(crossLoaderPackage, 50, java.util.Map.of("recon-payload/payload.jar", 52),
                    List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { crossLoaderRejected = e.getMessage().contains("cannot prove package/protected access"); }
            that(crossLoaderRejected, "capsule: matching package names do not grant access across the nested loader boundary");

            Path badConstructor = dir.resolve("bad-constructor.jar");
            Path constructorPayload = dir.resolve("constructor-payload.jar");
            writePrivateJar(constructorPayload, java.util.Map.of("consumer/Caller.class", constructorCaller()));
            writePrivateJar(badConstructor, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(constructorPayload)));
            that(rejectedCapsule(badConstructor, runtime, null, "absent runtime method"),
                    "capsule: constructor lookup never inherits a superclass constructor");

            Path badArrayMethod = dir.resolve("bad-array-owner-method.jar");
            Path badArrayMethodPayload = dir.resolve("bad-array-owner-method-payload.jar");
            writePrivateJar(badArrayMethodPayload, java.util.Map.of("consumer/Caller.class",
                    arrayOwnerCaller("[I", "missing", "()V", org.objectweb.asm.Opcodes.INVOKEVIRTUAL, false)));
            writePrivateJar(badArrayMethod, java.util.Map.of("capsule/Entry.class",
                    emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(badArrayMethodPayload)));
            that(rejectedCapsule(badArrayMethod, runtime, null, "absent runtime array method"),
                    "capsule: array-owner invokevirtual names must resolve to clone or an Object method");

            Path missingArrayComponent = dir.resolve("missing-array-owner-component.jar");
            Path missingArrayComponentPayload = dir.resolve("missing-array-owner-component-payload.jar");
            writePrivateJar(missingArrayComponentPayload, java.util.Map.of("consumer/Caller.class",
                    arrayOwnerCaller("[Lmissing/ArrayElement;", "clone", "()Ljava/lang/Object;",
                            org.objectweb.asm.Opcodes.INVOKEVIRTUAL, false)));
            writePrivateJar(missingArrayComponent, java.util.Map.of("capsule/Entry.class",
                    emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(missingArrayComponentPayload)));
            that(rejectedCapsule(missingArrayComponent, runtime, null, "absent runtime class missing/ArrayElement"),
                    "capsule: array-owner components still resolve through declared runtime roots");

            Path staticArrayMethod = dir.resolve("static-array-owner-method.jar");
            Path staticArrayMethodPayload = dir.resolve("static-array-owner-method-payload.jar");
            writePrivateJar(staticArrayMethodPayload, java.util.Map.of("consumer/Caller.class",
                    arrayOwnerCaller("[I", "toString", "()Ljava/lang/String;", org.objectweb.asm.Opcodes.INVOKESTATIC, false)));
            writePrivateJar(staticArrayMethod, java.util.Map.of("capsule/Entry.class",
                    emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(staticArrayMethodPayload)));
            that(rejectedCapsule(staticArrayMethod, runtime, null, "invalid member operation for array owner"),
                    "capsule: array-owner virtual methods cannot be invoked statically");

            Path interfaceArrayMethod = dir.resolve("interface-array-owner-method.jar");
            Path interfaceArrayMethodPayload = dir.resolve("interface-array-owner-method-payload.jar");
            writePrivateJar(interfaceArrayMethodPayload, java.util.Map.of("consumer/Caller.class",
                    arrayOwnerCaller("[I", "toString", "()Ljava/lang/String;", org.objectweb.asm.Opcodes.INVOKEVIRTUAL, true)));
            writePrivateJar(interfaceArrayMethod, java.util.Map.of("capsule/Entry.class",
                    emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(interfaceArrayMethodPayload)));
            that(rejectedCapsule(interfaceArrayMethod, runtime, null, "invalid member operation for array owner"),
                    "capsule: array-owner method references cannot use an interface constant");

            Path tooNew = dir.resolve("too-new.jar");
            Path newerPayload = dir.resolve("newer-payload.jar");
            writePrivateJar(newerPayload, java.util.Map.of("consumer/Newer.class", emptyClassMajor("consumer/Newer", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 53)));
            writePrivateJar(tooNew, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(newerPayload)));
            boolean ceilingRejected = false;
            try { Linkage.verifyCapsule(tooNew, 50, java.util.Map.of("recon-payload/payload.jar", 52), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { ceilingRejected = e.getMessage().contains("nested ceiling is 52"); }
            that(ceilingRejected, "capsule: a class above the nested Java ceiling is rejected");

            boolean emptyRuntimeRejected = false;
            try { Linkage.verifyCapsule(archive, 50, java.util.Map.of("recon-payload/payload.jar", 52), List.of(), null, "", "", List.of()); }
            catch (IllegalStateException e) { emptyRuntimeRejected = e.getMessage().contains("no declared runtimeArtifacts"); }
            that(emptyRuntimeRejected, "capsule: an empty runtime profile cannot pass vacuously");

            Path undeclaredNested = dir.resolve("undeclared-nested.jar");
            writePrivateJar(undeclaredNested, java.util.Map.of("capsule/Entry.class",
                    emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "hidden/payload.jar", Files.readAllBytes(nested)));
            boolean undeclaredNestedRejected = false;
            try { Linkage.verifyCapsule(undeclaredNested, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { undeclaredNestedRejected = e.getMessage().contains("undeclared nested archive"); }
            that(undeclaredNestedRejected, "capsule: every nested archive must have a declared Java ceiling");

            Path duplicateClass = dir.resolve("duplicate-class.jar");
            Path duplicatePayload = dir.resolve("duplicate-class-payload.jar");
            writePrivateJar(duplicatePayload, java.util.Map.of("capsule/Duplicate.class", constantClass("capsule/Duplicate", 2)));
            writePrivateJar(duplicateClass, java.util.Map.of(
                    "capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "capsule/Duplicate.class", constantClass("capsule/Duplicate", 1),
                    "recon-payload/payload.jar", Files.readAllBytes(duplicatePayload)));
            boolean duplicateClassRejected = false;
            try { Linkage.verifyCapsule(duplicateClass, 50, java.util.Map.of("recon-payload/payload.jar", 52),
                    List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { duplicateClassRejected = e.getMessage().contains("more than one archive or entry"); }
            that(duplicateClassRejected, "capsule: duplicate class identity across outer and nested archives is rejected");

            Path badEntryName = dir.resolve("bad-entry-name.jar");
            writePrivateJar(badEntryName, java.util.Map.of("capsule/Other.class",
                    emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50)));
            boolean badEntryNameRejected = false;
            try { Linkage.verifyCapsule(badEntryName, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { badEntryNameRejected = e.getMessage().contains("entry name does not match"); }
            that(badEntryNameRejected, "capsule: outer class entries must match their declared class identity");

            Path metadataClass = dir.resolve("metadata-class.jar");
            writePrivateJar(metadataClass, java.util.Map.of("META-INF/Hidden.class",
                    emptyClassMajor("META-INF/Hidden", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 53)));
            boolean metadataClassRejected = false;
            try { Linkage.verifyCapsule(metadataClass, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { metadataClassRejected = e.getMessage().contains("has class major 53"); }
            that(metadataClassRejected, "capsule: metadata-path class files still obey the outer Java ceiling");

            Path collision = dir.resolve("runtime-collision.jar");
            writePrivateJar(collision, java.util.Map.of("capsule/Entry.class",
                    emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "runtime/Base.class", baseClassWithStaticAnswer()));
            boolean collisionRejected = false;
            try { Linkage.verifyCapsule(collision, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { collisionRejected = e.getMessage().contains("collides with a different runtime definition"); }
            that(collisionRejected, "capsule: artifact classes cannot shadow a different runtime definition");

            Path badDescriptor = dir.resolve("bad-descriptor.jar");
            writePrivateJar(badDescriptor, java.util.Map.of("capsule/Entry.class", typedClass("capsule/Entry", "missing/DescriptorType")));
            boolean descriptorRejected = false;
            try { Linkage.verifyCapsule(badDescriptor, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { descriptorRejected = e.getMessage().contains("absent runtime class missing/DescriptorType"); }
            that(descriptorRejected, "capsule: method descriptors are checked against runtime roots");

            Path badHandle = dir.resolve("bad-handle.jar");
            writePrivateJar(badHandle, java.util.Map.of("capsule/Entry.class", handleCaller("runtime/Base", "missingHandle")));
            boolean handleRejected = false;
            try { Linkage.verifyCapsule(badHandle, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { handleRejected = e.getMessage().contains("absent runtime method runtime/Base.missingHandle"); }
            that(handleRejected, "capsule: method handles are checked against runtime members");

            Path badArray = dir.resolve("bad-array.jar");
            writePrivateJar(badArray, java.util.Map.of("capsule/Entry.class", arrayCheckcastCaller("missing/ArrayComponent")));
            boolean arrayRejected = false;
            try { Linkage.verifyCapsule(badArray, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { arrayRejected = e.getMessage().contains("absent runtime class missing/ArrayComponent"); }
            that(arrayRejected, "capsule: array type instructions retain component class references");

            Path mappings = dir.resolve("names.tiny");
            Files.writeString(mappings, "tiny\t2\t0\tmcp\truntime\nc\tnet/minecraft/Foo\truntime/Foo\n", StandardCharsets.UTF_8);
            Path unmapped = dir.resolve("unmapped.jar");
            Path unmappedPayload = dir.resolve("unmapped-payload.jar");
            writePrivateJar(unmappedPayload, java.util.Map.of("consumer/Caller.class", inheritedCaller("consumer/Caller", "net/minecraft/Foo", "answer", "()I", org.objectweb.asm.Opcodes.INVOKEVIRTUAL)));
            writePrivateJar(unmapped, java.util.Map.of("capsule/Entry.class", emptyClassMajor("capsule/Entry", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50),
                    "recon-payload/payload.jar", Files.readAllBytes(unmappedPayload)));
            boolean mappingRejected = false;
            try { Linkage.verifyCapsule(unmapped, 50, java.util.Map.of("recon-payload/payload.jar", 52), List.of(runtime), mappings, "mcp", "runtime", List.of("net/minecraft/")); }
            catch (IllegalStateException e) { mappingRejected = e.getMessage().contains("retains source-namespace class"); }
            that(mappingRejected, "capsule: a mapped source class retained in final bytecode is rejected");

            Path unmappedDefinition = dir.resolve("unmapped-definition.jar");
            writePrivateJar(unmappedDefinition, java.util.Map.of(
                    "net/minecraft/Foo.class", emptyClassMajor("net/minecraft/Foo", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC, 50)));
            boolean definitionMappingRejected = false;
            try { Linkage.verifyCapsule(unmappedDefinition, 50, java.util.Map.of(), List.of(runtime), mappings,
                    "mcp", "runtime", List.of("net/minecraft/")); }
            catch (IllegalStateException e) { definitionMappingRejected = e.getMessage().contains("retains source-namespace class net/minecraft/Foo"); }
            that(definitionMappingRejected, "capsule: a class definition retained in the source namespace is rejected even without external references");

            Path runtimeWithMappedClass = dir.resolve("runtime-with-mapped-class.jar");
            writePrivateJar(runtimeWithMappedClass, java.util.Map.of(
                    "java/lang/Object.class", emptyClass("java/lang/Object", null, org.objectweb.asm.Opcodes.ACC_PUBLIC),
                    "net/minecraft/Foo.class", emptyClass("net/minecraft/Foo", "java/lang/Object", org.objectweb.asm.Opcodes.ACC_PUBLIC)));
            Path mappingWithoutFoo = dir.resolve("mapping-without-foo.tiny");
            Files.writeString(mappingWithoutFoo, "tiny\t2\t0\tmcp\truntime\n", StandardCharsets.UTF_8);
            Path typed = dir.resolve("typed.jar");
            writePrivateJar(typed, java.util.Map.of("capsule/Entry.class", typedClass("capsule/Entry", "net/minecraft/Foo")));
            boolean missingMappingRejected = false;
            try { Linkage.verifyCapsule(typed, 50, java.util.Map.of(), List.of(runtimeWithMappedClass), mappingWithoutFoo,
                    "mcp", "runtime", List.of("net/minecraft/")); }
            catch (IllegalStateException e) { missingMappingRejected = e.getMessage().contains("absent from the declared Tiny mapping"); }
            that(missingMappingRejected, "capsule: a runtime-resolvable source class without a Tiny row is rejected");

            Path validFrame = dir.resolve("valid-frame.jar");
            writePrivateJar(validFrame, java.util.Map.of("capsule/Frame.class", framedClass("capsule/Frame", "java/lang/String")));
            boolean validFramePassed = true;
            try { Linkage.verifyCapsule(validFrame, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { validFramePassed = false; }
            that(validFramePassed, "capsule: a valid StackMapTable frame type resolves against runtime roots");

            Path missingFrame = dir.resolve("missing-frame.jar");
            writePrivateJar(missingFrame, java.util.Map.of("capsule/Frame.class", framedClass("capsule/Frame", "missing/FrameOnly")));
            boolean missingFrameRejected = false;
            try { Linkage.verifyCapsule(missingFrame, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { missingFrameRejected = e.getMessage().contains("absent runtime class missing/FrameOnly"); }
            that(missingFrameRejected, "capsule: a class used only by a StackMapTable frame is required at runtime");

            Path missingFrameArray = dir.resolve("missing-frame-array.jar");
            writePrivateJar(missingFrameArray, java.util.Map.of("capsule/FrameArray.class",
                    framedClass("capsule/FrameArray", "[Lmissing/FrameArrayElement;")));
            boolean missingFrameArrayRejected = false;
            try { Linkage.verifyCapsule(missingFrameArray, 50, java.util.Map.of(), List.of(runtime), null, "", "", List.of()); }
            catch (IllegalStateException e) { missingFrameArrayRejected = e.getMessage().contains("absent runtime class missing/FrameArrayElement"); }
            that(missingFrameArrayRejected, "capsule: array component types used only by frames are required at runtime");
        } finally {
            delete(dir);
        }
    }

    static void runtimeBootClasspath() throws Exception {
        Path dir = Files.createTempDirectory("mycelium-boot-classpath-");
        try {
            String windows = "Property settings:\r\n"
                    + "    sun.boot.class.path = C:\\jdk8\\jre\\lib\\resources.jar\r\n"
                    + "        C:\\jdk8\\jre\\lib\\rt.jar\r\n"
                    + "        C:\\jdk8\\jre\\lib\\jce.jar\r\n"
                    + "        C:\\jdk8\\jre\\classes\r\n"
                    + "    java.class.path = unrelated.jar\r\n";
            that(RuntimeBootClasspath.parseEntries(windows, ';').equals(List.of("C:\\jdk8\\jre\\lib\\resources.jar",
                    "C:\\jdk8\\jre\\lib\\rt.jar", "C:\\jdk8\\jre\\lib\\jce.jar", "C:\\jdk8\\jre\\classes")),
                    "Java 8 boot classpath: exact wrapped Windows property retains archive order");
            String mixed = "sun.boot.class.path = C:\\jdk8\\jre\\lib\\resources.jar;C:\\jdk8\\jre\\lib\\rt.jar;\r\n"
                    + "        C:\\jdk8\\jre\\lib\\jce.jar;C:\\jdk8\\jre\\classes\r\n";
            that(RuntimeBootClasspath.parseEntries(mixed, ';').equals(List.of("C:\\jdk8\\jre\\lib\\resources.jar",
                    "C:\\jdk8\\jre\\lib\\rt.jar", "C:\\jdk8\\jre\\lib\\jce.jar", "C:\\jdk8\\jre\\classes")),
                    "Java 8 boot classpath: mixed single-line and wrapped values retain order");
            String unix = "Property settings:\n    sun.boot.class.path = /opt/jdk8/lib/resources.jar:/opt/jdk8/lib/rt.jar\n"
                    + "    java.class.path = ignored.jar\n";
            that(RuntimeBootClasspath.parseEntries(unix, ':').equals(List.of("/opt/jdk8/lib/resources.jar", "/opt/jdk8/lib/rt.jar")),
                    "Java 8 boot classpath: Unix path separator splits ordered archives");

            Path installation = dir.resolve("jdk8");
            Path classes = installation.resolve("jre/classes");
            Path a = installation.resolve("jre/lib/resources.jar");
            Path b = installation.resolve("jre/lib/rt.jar");
            Path rsaPlaceholder = installation.resolve("jre/lib/sunrsasign.jar");
            Files.createDirectories(a.getParent());
            Files.write(a, new byte[] { 1 });
            Files.write(b, new byte[] { 2 });
            String property = "sun.boot.class.path = " + a + ";" + b + ";" + rsaPlaceholder + ";" + classes;
            RuntimeBootClasspath.Resolution absent = RuntimeBootClasspath.parse(property, ';', installation);
            that(absent.archives().equals(List.of(a.toFile(), b.toFile())) && absent.omittedRoots().size() == 2
                    && absent.omittedRoots().get(0).contains("sunrsasign.jar") && absent.omittedRoots().get(0).contains("ignored")
                    && absent.omittedRoots().get(1).contains("classes"),
                    "Java 8 boot classpath: absent standard template entry and classes directory are explicitly recorded");
            Files.createDirectories(classes);
            RuntimeBootClasspath.Resolution empty = RuntimeBootClasspath.parse(property, ';', installation);
            that(empty.omittedRoots().size() == 2 && empty.omittedRoots().get(1).contains("empty"),
                    "Java 8 boot classpath: empty expected classes directory is explicitly recorded");
            Files.write(classes.resolve("Unexpected.class"), new byte[] { 0 });
            boolean nonemptyRejected = false;
            try { RuntimeBootClasspath.parse(property, ';', installation); }
            catch (org.gradle.api.GradleException e) { nonemptyRejected = e.getMessage().contains("non-empty Java 8 classes directory"); }
            that(nonemptyRejected, "Java 8 boot classpath: non-empty classes directory is not silently omitted");
            boolean missingRejected = false;
            try { RuntimeBootClasspath.parse("sun.boot.class.path = " + a + ";" + installation.resolve("jre/lib/missing.jar"), ';', installation); }
            catch (org.gradle.api.GradleException e) { missingRejected = e.getMessage().contains("missing or not a JAR file"); }
            that(missingRejected, "Java 8 boot classpath: unrelated missing archive fails");
            boolean missingRtRejected = false;
            try { RuntimeBootClasspath.parse("sun.boot.class.path = " + a, ';', installation); }
            catch (org.gradle.api.GradleException e) { missingRtRejected = e.getMessage().contains("omitted mandatory rt.jar"); }
            that(missingRtRejected, "Java 8 boot classpath: rt.jar remains mandatory");
            boolean emptyRootRejected = false;
            try { RuntimeBootClasspath.parseEntries("sun.boot.class.path = one.jar;\n", ';'); }
            catch (org.gradle.api.GradleException e) { emptyRootRejected = e.getMessage().contains("empty root"); }
            that(emptyRootRejected, "Java 8 boot classpath: an unexplained trailing separator is rejected");
            boolean wrongFeatureRejected = false;
            try { RuntimeBootClasspath.checkFeature(21, 8); }
            catch (org.gradle.api.GradleException e) { wrongFeatureRejected = e.getMessage().contains("expected Java 8"); }
            that(wrongFeatureRejected, "Java 8 boot classpath: wrong launcher feature fails");
        } finally {
            delete(dir);
        }
    }

    static void runtimeBootCapture() throws Exception {
        Process invalid = launcherFixture("sleep");
        boolean invalidRejected = false;
        try { RuntimeBootClasspath.capture(invalid, Duration.ZERO, 1024); }
        catch (IllegalArgumentException e) { invalidRejected = e.getMessage().contains("positive timeout"); }
        that(invalidRejected && !invalid.isAlive(), "Java 8 boot classpath: invalid capture request still cleans up owned child");

        Process oversized = launcherFixture("output");
        boolean outputRejected = false;
        try { RuntimeBootClasspath.capture(oversized, Duration.ofSeconds(5), 1024); }
        catch (org.gradle.api.GradleException e) { outputRejected = e.getMessage().contains("output exceeded 1024 bytes"); }
        that(outputRejected && !oversized.isAlive(), "Java 8 boot classpath: oversized launcher output is bounded and child is reaped");

        Process slow = launcherFixture("sleep");
        long start = System.nanoTime();
        boolean timeoutRejected = false;
        try { RuntimeBootClasspath.capture(slow, Duration.ofMillis(250), 4096); }
        catch (org.gradle.api.GradleException e) { timeoutRejected = e.getMessage().contains("exceeded 250 ms"); }
        that(timeoutRejected && !slow.isAlive() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5),
                "Java 8 boot classpath: launcher timeout terminates and reaps child");

        AtomicReference<Process> interruptedProcess = new AtomicReference<>();
        AtomicReference<Throwable> interruptedFailure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            Process process = null;
            try {
                process = launcherFixture("sleep");
                interruptedProcess.set(process);
                started.countDown();
                RuntimeBootClasspath.capture(process, Duration.ofSeconds(20), 4096);
            } catch (Throwable e) {
                interruptedFailure.set(e);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "mycelium-launcher-interrupt-test");
        worker.start();
        that(started.await(5, TimeUnit.SECONDS), "Java 8 boot classpath: interrupt fixture process starts");
        worker.interrupt();
        worker.join(5000);
        Process terminated = interruptedProcess.get();
        that(!worker.isAlive() && interruptedFailure.get() instanceof org.gradle.api.GradleException
                        && interruptedFailure.get().getMessage().contains("interrupted") && interruptRestored.get()
                        && terminated != null && !terminated.isAlive(),
                "Java 8 boot classpath: interruption restores thread state and reaps launcher child");
    }

    private static Process launcherFixture(String mode) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        return new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                LauncherProcessFixture.class.getName(), mode).redirectErrorStream(true).start();
    }

    public static final class LauncherProcessFixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("output")) {
                System.out.write(new byte[4096]);
                System.out.flush();
                Thread.sleep(30000);
            } else {
                Thread.sleep(30000);
            }
        }
    }

    private static boolean rejectedCapsule(Path archive, Path runtime, Path mappings, String expected) throws Exception {
        try {
            Linkage.verifyCapsule(archive, 50, java.util.Map.of("recon-payload/payload.jar", 52), List.of(runtime), mappings, "", "", List.of());
            return false;
        } catch (IllegalStateException e) {
            return e.getMessage().contains(expected);
        }
    }

    private static byte[] emptyClass(String name, String parent, int access) {
        return emptyClassMajor(name, parent, access, 52);
    }

    private static byte[] emptyClassMajor(String name, String parent, int access, int major) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(major, access, name, null, parent, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] typedClass(String name, String returnType) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", null);
        writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                "value", "()L" + returnType + ";", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] framedClass(String name, String frameType) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "frameOnly", "()V", null, null);
        method.visitCode();
        method.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        method.visitVarInsn(org.objectweb.asm.Opcodes.ASTORE, 0);
        org.objectweb.asm.Label target = new org.objectweb.asm.Label();
        method.visitLabel(target);
        method.visitFrame(org.objectweb.asm.Opcodes.F_FULL, 1, new Object[]{frameType}, 0, new Object[0]);
        method.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        method.visitInsn(org.objectweb.asm.Opcodes.POP);
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] constantClass(String name, int value) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "value", "()I", null, null);
        method.visitCode(); method.visitInsn(value == 1 ? org.objectweb.asm.Opcodes.ICONST_1 : org.objectweb.asm.Opcodes.ICONST_2);
        method.visitInsn(org.objectweb.asm.Opcodes.IRETURN); method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] baseClass() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, "runtime/Base", null, "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "answerField", "I", null, null).visitEnd();
        var init = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd();
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "answer", "()I", null, null);
        method.visitCode(); method.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); method.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        method.visitMaxs(1, 1); method.visitEnd();
        var staticMethod = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "staticValue", "()I", null, null);
        staticMethod.visitCode(); staticMethod.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); staticMethod.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        staticMethod.visitMaxs(1, 0); staticMethod.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] objectClass() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "java/lang/Object", null, null, null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        method.visitCode(); method.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL); method.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] baseClassWithPrivateAnswer() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "runtime/Base", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "privateAnswer", "()I", null, null);
        method.visitCode(); method.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); method.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] baseClassWithProtectedStatic() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "runtime/Base", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PROTECTED | org.objectweb.asm.Opcodes.ACC_STATIC,
                "protectedValue", "()I", null, null);
        method.visitCode(); method.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); method.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] baseClassWithProtectedInstance() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "runtime/Base", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PROTECTED, "protectedValue", "()I", null, null);
        method.visitCode(); method.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); method.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] protectedStaticCaller() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, "consumer/Child", null, "runtime/Base", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "()I", null, null);
        method.visitCode(); method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
                "runtime/Base", "protectedValue", "()I", false);
        method.visitInsn(org.objectweb.asm.Opcodes.IRETURN); method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] protectedInstanceCaller(String caller, String owner) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        String parent = caller.equals("consumer/Child") ? "runtime/Base" : "java/lang/Object";
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, caller, null, parent, null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "(L" + owner + ";)I", null, null);
        method.visitCode(); method.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, owner, "protectedValue", "()I", false);
        method.visitInsn(org.objectweb.asm.Opcodes.IRETURN); method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] ownPrivateCaller() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, "capsule/Entry", null, "java/lang/Object", null);
        var secret = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_STATIC,
                "secret", "()I", null, null);
        secret.visitCode(); secret.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); secret.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        secret.visitMaxs(1, 0); secret.visitEnd();
        var caller = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "()I", null, null);
        caller.visitCode(); caller.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "capsule/Entry", "secret", "()I", false);
        caller.visitInsn(org.objectweb.asm.Opcodes.IRETURN); caller.visitMaxs(1, 0); caller.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] packagePrivateOwner() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, "capsule/PackageOwner", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_STATIC, "secret", "()I", null, null);
        method.visitCode(); method.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); method.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] packagePrivateCaller() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "capsule/PackageCaller", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "()I", null, null);
        method.visitCode(); method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
                "capsule/PackageOwner", "secret", "()I", false);
        method.visitInsn(org.objectweb.asm.Opcodes.IRETURN); method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] nestHost(boolean listsNeighbor) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(55, org.objectweb.asm.Opcodes.ACC_PUBLIC, "capsule/Host", null, "java/lang/Object", null);
        if (listsNeighbor) writer.visitNestMember("capsule/Neighbor");
        var secret = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_STATIC,
                "secret", "()I", null, null);
        secret.visitCode(); secret.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); secret.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        secret.visitMaxs(1, 0); secret.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] nestNeighbor() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(55, org.objectweb.asm.Opcodes.ACC_PUBLIC, "capsule/Neighbor", null, "java/lang/Object", null);
        writer.visitNestHost("capsule/Host");
        var caller = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "()I", null, null);
        caller.visitCode(); caller.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "capsule/Host", "secret", "()I", false);
        caller.visitInsn(org.objectweb.asm.Opcodes.IRETURN); caller.visitMaxs(1, 0); caller.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] baseClassWithStaticAnswer() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, "runtime/Base", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "answer", "()I", null, null);
        method.visitCode(); method.visitInsn(org.objectweb.asm.Opcodes.ICONST_1); method.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] childClass() { return emptyClass("runtime/Child", "runtime/Base", org.objectweb.asm.Opcodes.ACC_PUBLIC); }

    private static byte[] interfaceClass() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                "runtime/Face", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT, "value", "()I", null, null);
        method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] interfaceChildClass() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                "runtime/FaceChild", null, "java/lang/Object", new String[]{"runtime/Face"});
        writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] inheritedCaller(String caller, String owner, String methodName, String descriptor, int opcode) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, caller, null, "java/lang/Object", null);
        String methodDescriptor = opcode == org.objectweb.asm.Opcodes.INVOKEINTERFACE ? "(L" + owner + ";)I" : "(L" + owner + ";)I";
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "call", methodDescriptor, null, null);
        method.visitCode(); method.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        method.visitMethodInsn(opcode, owner, methodName, descriptor, opcode == org.objectweb.asm.Opcodes.INVOKEINTERFACE);
        method.visitInsn(org.objectweb.asm.Opcodes.IRETURN); method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] fieldCaller(String caller, String owner, String fieldName, int opcode) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, caller, null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "(L" + owner + ";)I", null, null);
        method.visitCode(); method.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        method.visitFieldInsn(opcode, owner, fieldName, "I");
        method.visitInsn(org.objectweb.asm.Opcodes.IRETURN); method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] interfaceObjectMethodCaller() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "consumer/ObjectMethodCaller", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "(Lruntime/Face;)Ljava/lang/String;", null, null);
        method.visitCode(); method.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "runtime/Face", "toString", "()Ljava/lang/String;", true);
        method.visitInsn(org.objectweb.asm.Opcodes.ARETURN); method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] arrayOwnerCalls() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "consumer/ArrayOwnerCaller", null, "java/lang/Object", null);
        var clone = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "cloneArray", "([Ljava/lang/String;)Ljava/lang/Object;", null, null);
        clone.visitCode(); clone.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        clone.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "[Ljava/lang/String;", "clone", "()Ljava/lang/Object;", false);
        clone.visitInsn(org.objectweb.asm.Opcodes.ARETURN); clone.visitMaxs(1, 1); clone.visitEnd();
        var string = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "arrayString", "([Ljava/lang/String;)Ljava/lang/String;", null, null);
        string.visitCode(); string.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        string.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "[Ljava/lang/String;", "toString", "()Ljava/lang/String;", false);
        string.visitInsn(org.objectweb.asm.Opcodes.ARETURN); string.visitMaxs(1, 1); string.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] arrayOwnerCaller(String owner, String name, String descriptor, int opcode, boolean itf) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "consumer/Caller", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "([I)V", null, null);
        method.visitCode();
        if (opcode != org.objectweb.asm.Opcodes.INVOKESTATIC) method.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        method.visitMethodInsn(opcode, owner, name, descriptor, itf);
        int returnSort = org.objectweb.asm.Type.getReturnType(descriptor).getSort();
        if (returnSort != org.objectweb.asm.Type.VOID) {
            method.visitInsn(returnSort == org.objectweb.asm.Type.LONG || returnSort == org.objectweb.asm.Type.DOUBLE
                    ? org.objectweb.asm.Opcodes.POP2 : org.objectweb.asm.Opcodes.POP);
        }
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN); method.visitMaxs(2, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] handleCaller(String owner, String methodName) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, "capsule/Entry", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "()V", null, null);
        method.visitCode(); method.visitLdcInsn(new org.objectweb.asm.Handle(org.objectweb.asm.Opcodes.H_INVOKEVIRTUAL,
                owner, methodName, "()I", false));
        method.visitInsn(org.objectweb.asm.Opcodes.POP); method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] arrayCheckcastCaller(String component) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(50, org.objectweb.asm.Opcodes.ACC_PUBLIC, "capsule/Entry", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "call", "(Ljava/lang/Object;)V", null, null);
        method.visitCode(); method.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        method.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "[L" + component + ";");
        method.visitInsn(org.objectweb.asm.Opcodes.POP); method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] constructorCaller() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "consumer/Caller", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "call", "()V", null, null);
        method.visitCode(); method.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "runtime/Child");
        method.visitInsn(org.objectweb.asm.Opcodes.DUP);
        method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "runtime/Child", "<init>", "()V", false);
        method.visitInsn(org.objectweb.asm.Opcodes.POP); method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        method.visitMaxs(2, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    static void privateLibraries() throws Exception {
        Path dir = Files.createTempDirectory("mycelium-private-libraries-");
        try {
            Path library = dir.resolve("library.jar");
            Path conflict = dir.resolve("old-library.jar");
            Path artifact = dir.resolve("artifact.jar");
            Path output = dir.resolve("isolated.jar");
            writePrivateJar(library, java.util.Map.of(
                    "library/api/Value.class", privateConstant("library/api/Value", 42),
                    "META-INF/LICENSE", "the feature library license".getBytes(StandardCharsets.UTF_8),
                    "META-INF/services/library.api.Value", "library.api.Value\n".getBytes(StandardCharsets.UTF_8)));
            writePrivateJar(conflict, java.util.Map.of("library/api/Value.class", privateConstant("library/api/Value", 13)));
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, "consumer/Caller", null, "java/lang/Object", null);
            org.objectweb.asm.MethodVisitor method = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "answer", "()I", null, null);
            method.visitCode();
            method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "library/api/Value", "answer", "()I", false);
            method.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            method.visitMaxs(1, 0);
            method.visitEnd();
            cw.visitEnd();
            writePrivateJar(artifact, java.util.Map.of("consumer/Caller.class", cw.toByteArray(),
                    "library/apishim/Keep.class", privateConstant("library/apishim/Keep", 7)));
            List<PrivateLibraries.Relocation> declarations = PrivateLibraries.declarations(List.of("library.api=owned.consumer.library"));
            java.util.Map<String, Integer> counts = PrivateLibraries.isolate(artifact, List.of(library), declarations, output);
            that(counts.equals(java.util.Map.of("library.api", 1)), "private libraries: exact relocated class count");
            try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(output.toFile())) {
                that(jar.getEntry("library/api/Value.class") == null && jar.getEntry("owned/consumer/library/Value.class") != null,
                        "private libraries: public identity replaced by artifact-owned identity");
                that(jar.getEntry("library/apishim/Keep.class") != null, "private libraries: package boundary preserves unrelated names");
                that(jar.getEntry("META-INF/private-libraries/library.jar/LICENSE") != null, "private libraries: library license preserved");
                java.util.zip.ZipEntry service = jar.getEntry("META-INF/services/owned.consumer.library.Value");
                that(service != null && new String(jar.getInputStream(service).readAllBytes(), StandardCharsets.UTF_8).equals("owned.consumer.library.Value\n"),
                        "private libraries: service type and provider identities both relocated");
            }
            try (java.net.URLClassLoader parent = new java.net.URLClassLoader(new java.net.URL[]{conflict.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
                 java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, parent)) {
                that(parent.loadClass("library.api.Value").getMethod("answer").invoke(null).equals(13), "private libraries: incompatible parent library really loaded");
                that(loader.loadClass("consumer.Caller").getMethod("answer").invoke(null).equals(42), "private libraries: caller executes its own version despite incompatible parent");
            }
            Path again = dir.resolve("again.jar");
            PrivateLibraries.isolate(artifact, List.of(library), declarations, again);
            that(java.util.Arrays.equals(Files.readAllBytes(output), Files.readAllBytes(again)), "private libraries: repeated artifact is byte-identical");
            for (List<String> bad : List.of(List.of("a.b=a.b"), List.of("a.b=java.foo"), List.of("bad"),
                    List.of("a.b=owned.one", "a.b.c=owned.two"), List.of("a.b=owned.one", "c.d=owned.one.child"))) {
                boolean rejected = false;
                try { PrivateLibraries.declarations(bad); } catch (IllegalArgumentException expected) { rejected = true; }
                that(rejected, "private libraries: malformed or overlapping declarations rejected " + bad);
            }
            Path multi = dir.resolve("multi.jar");
            writePrivateJar(multi, java.util.Map.of("library/api/Value.class", privateConstant("library/api/Value", 42),
                    "META-INF/versions/11/library/api/Value.class", privateConstant("library/api/Value", 99)));
            PrivateLibraries.isolate(artifact, List.of(multi), declarations, again, 8);
            try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(again.toFile())) {
                that(jar.getEntry("META-INF/versions/11/owned/consumer/library/Value.class") == null,
                        "private libraries: Java 8 artifact omits variants its JVM cannot use");
            }
            PrivateLibraries.isolate(artifact, List.of(multi), declarations, again, 17);
            try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(again.toFile())) {
                that(jar.getEntry("META-INF/versions/11/owned/consumer/library/Value.class") != null,
                        "private libraries: newer artifact retains relocated multi-release variants");
            }
            Path modular = dir.resolve("modular.jar");
            org.objectweb.asm.ClassWriter moduleWriter = new org.objectweb.asm.ClassWriter(0);
            moduleWriter.visit(org.objectweb.asm.Opcodes.V9, org.objectweb.asm.Opcodes.ACC_MODULE, "module-info", null, null, null);
            moduleWriter.visitModule("fixture.module", 0, null).visitEnd();
            moduleWriter.visitEnd();
            writePrivateJar(modular, java.util.Map.of("module-info.class", moduleWriter.toByteArray()));
            boolean moduleRejected = false;
            try { PrivateLibraries.isolate(modular, List.of(library), declarations, again, 17); }
            catch (IOException expected) { moduleRejected = expected.getMessage().contains("explicit module descriptor"); }
            that(moduleRejected, "private libraries: explicit artifact module is refused rather than silently altered");
            boolean missing = false;
            try { PrivateLibraries.isolate(artifact, List.of(library), PrivateLibraries.declarations(List.of("absent.api=owned.absent")), again); }
            catch (IOException expected) { missing = expected.getMessage().contains("no declared relocation"); }
            that(missing, "private libraries: undeclared library class cannot leak into artifact");
            Path duplicate = dir.resolve("duplicate.jar");
            writePrivateJar(duplicate, java.util.Map.of("library/api/Value.class", privateConstant("library/api/Value", 99)));
            boolean clash = false;
            try { PrivateLibraries.isolate(artifact, List.of(library, duplicate), declarations, again); }
            catch (IOException expected) { clash = expected.getMessage().contains("conflicting jar entry"); }
            that(clash, "private libraries: conflicting class bodies fail before shipping");
        } finally {
            Harness.deleteRecursively(dir);
        }
    }

    private static byte[] privateConstant(String owner, int answer) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "answer", "()I", null, null);
        method.visitCode();
        method.visitLdcInsn(answer);
        method.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writePrivateJar(Path path, java.util.Map<String, byte[]> entries) throws IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            for (java.util.Map.Entry<String, byte[]> entry : new java.util.TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    // --- eras ------------------------------------------------------------------------------------------------------

    static void eras(Path root) {
        Eras eras = Eras.load(root.toFile());
        String[][] expect = {
                {"c0.0.11a", "classic"}, {"c0.0.13a_03", "classic"}, {"c0.30_01c", "classic"},
                {"a1.0.4", "alpha"}, {"a1.2.6", "alpha"},
                {"b1.0", "beta"}, {"b1.7.3", "beta"}, {"b1.8.1", "beta"}, {"b1.9-pre1", "beta"}, {"b1.9-pre6", "beta"},
                {"1.0", "release"}, {"1.7.10", "release"}, {"1.8", "modpacks"}, {"1.12.2", "modpacks"},
                {"1.13", "flattening"}, {"1.13.2", "flattening"}, {"1.14", "fabric"}, {"1.16.5", "fabric"},
                {"1.17", "java16"}, {"1.17.1", "java16"}, {"1.18", "caves"}, {"1.20.1", "caves"},
                {"1.20.2", "configuration"}, {"1.20.4", "configuration"}, {"1.20.5", "components"},
                {"1.20.5-pre1", "components"}, {"1.21.4", "components"}, {"1.21.11", "components"}, {"1.21.100", "components"},
                {"26.1", "unobfuscated"}, {"26.1.2", "unobfuscated"}, {"26.3", "unobfuscated"}, {"26.99", "unobfuscated"},
        };
        for (String[] e : expect) {
            String got;
            try {
                got = eras.of(e[0]).id();
            } catch (RuntimeException ex) {
                got = "throws: " + ex.getMessage();
            }
            that(e[1].equals(got), "eras:of " + e[0] + " is " + e[1] + ", got " + got);
        }
        that(eras.allJavas().equals(List.of(8, 16, 17, 21, 25)), "eras:allJavas is 8,16,17,21,25: " + eras.allJavas());
        String[] order = {"c0.0.11a", "a1.0.4", "b1.0", "1.0", "1.21.1", "26.3"};
        for (int i = 0; i < order.length; i++) {
            for (int j = 0; j < order.length; j++) {
                int c = Eras.compare(order[i], order[j]);
                that(Integer.signum(c) == Integer.signum(Integer.compare(i, j)), "eras:compare orders " + order[i] + " and " + order[j]);
                that(c == -Eras.compare(order[j], order[i]), "eras:compare is antisymmetric on " + order[i] + ", " + order[j]);
            }
        }
        try {
            eras.of("27.1");
            that(false, "eras:a version past every era is a miss");
        } catch (IllegalArgumentException ex) {
            that(ex.getMessage().contains("after 'unobfuscated'"), "eras:a miss names the nearest era: " + ex.getMessage());
        }
        that(!Eras.readable("rd-132211") && !Eras.readable("inf-20100618") && Eras.readable("c0.30_01c"),
                "eras:pre-Classic ids are not readable versions; Classic ones are");
        that(Eras.atMost("26.3", "26.x") && Eras.atMost("26.40.1", "26.x") && !Eras.atMost("27.1", "26.x"), "eras:a line ends after its last version");
        that(Eras.atLeast("a1.0.4", "a1.x") && !Eras.atLeast("c0.30_01c", "a1.x"), "eras:a line starts at its first version");
    }

    // --- the registry ------------------------------------------------------------------------------------------------

    static final String ROW_A = "{\"id\":\"x.a@1\",\"path\":\"a\",\"package\":\"mycelium.a.v1\",\"status\":\"reference\"}";
    static final String ROW_B = "{\"id\":\"x.a@2\",\"path\":\"a-2\",\"package\":\"mycelium.a.v2\",\"status\":\"reference\",\"supersedes\":\"x.a@1\"}";

    static String registryText(String... rows) {
        return "{\"schema\":2,\"note\":\"n\",\"contracts\":[" + String.join(",", rows) + "]}";
    }

    static boolean refuses(Runnable r, String fragment) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return e.getMessage() != null && e.getMessage().contains(fragment);
        }
    }

    static void registry() {
        that(ContractRegistry.parse(registryText(ROW_A, ROW_B), "t").all().size() == 2, "registry:a valid registry reads");
        that(refuses(() -> ContractRegistry.parse(registryText(ROW_A.replace("}", ",\"liveConsumers\":[]}")), "t"), "liveConsumers"),
                "registry:an unknown field is refused, by name");
        that(refuses(() -> ContractRegistry.parse(registryText(ROW_A.replace(",\"status\":\"reference\"", "")), "t"), "status"),
                "registry:a row without status is refused");
        that(refuses(() -> ContractRegistry.parse(registryText(ROW_B), "t"), "supersedes unknown"),
                "registry:superseding an unknown contract is refused");
        that(refuses(() -> ContractRegistry.parse("{\"schema\":1,\"contracts\":[]}", "t"), "schema"),
                "registry:another schema is refused");
        ContractRegistry reg = ContractRegistry.parse(registryText(ROW_A, ROW_B), "t");
        that(refuses(() -> reg.resolve(List.of("x.a@1", "x.a@2")), "never both"),
                "registry:a build selecting a contract and its successor is refused, naming both");
        that(reg.resolve(List.of("x.a@2")).size() == 1, "registry:a build selecting the successor alone is fine");
        that(reg.require("x.a@1").runFile().equals("contracts/a/src/mycelium/a/v1/harness/Run.java"),
                "registry:the harness main lives where every runner looks");
    }

    // --- integrations ------------------------------------------------------------------------------------------------

    static void integrations() {
        that(refuses(() -> IsolationTask.Integration.decode("voicechat|woflo.voice|"), "no API packages"),
                "integration:an empty API list is refused, not a gate that keeps nothing out");
        that(refuses(() -> IsolationTask.Integration.decode("voicechat||de.maxhenkel"), "home package"),
                "integration:a blank home package is refused");
        that(refuses(() -> IsolationTask.Integration.decode("voicechat|woflo.voice"), "malformed"),
                "integration:a declaration missing its parts is refused");
        that(refuses(() -> IsolationTask.Integration.decode("voicechat|woflo.voice|de maxhenkel"), "not a Java package"),
                "integration:an API that is not a package is refused");
        IsolationTask.Integration ok = IsolationTask.Integration.decode("voicechat|woflo.voice|de.maxhenkel.voicechat|a/B.java");
        that(IsolationTask.Integration.decode(ok.encode()).equals(ok), "integration:encode and decode round-trip");
    }

    // --- the evidence convention ------------------------------------------------------------------------------------

    static void evidence() {
        JsonObject none = EvidenceTask.usageOf(null, "a@1");
        that(!none.get("checked").getAsBoolean() && none.has("why"), "evidence:usage that did not run says so, with why");
        JsonObject empty = EvidenceTask.usageOf(JsonParser.parseString("{\"schema\":2,\"checked\":false,\"why\":\"no sources\"}")
                .getAsJsonObject(), "a@1");
        that(!empty.get("checked").getAsBoolean() && "no sources".equals(empty.get("why").getAsString()),
                "evidence:usage with nothing to judge by says it checked nothing, and why");
        JsonObject seen = EvidenceTask.usageOf(JsonParser.parseString("{\"schema\":2,\"checked\":true,\"results\":"
                + "[{\"id\":\"a@1\",\"referencingFiles\":3}]}").getAsJsonObject(), "a@1");
        that(seen.get("checked").getAsBoolean() && seen.get("referenced").getAsBoolean(), "evidence:usage that ran says what it saw");
    }

    // --- loaders -----------------------------------------------------------------------------------------------------

    static void loaders(Path root) {
        List<String> packages = Loaders.load(root.toFile()).platformPackages();
        for (String p : List.of("net.minecraft", "com.mojang", "net.fabricmc", "net.neoforged", "net.minecraftforge", "cpw.mods",
                "org.spongepowered", "net.legacyfabric", "net.ornithemc", "net.modificationstation", "org.quiltmc")) {
            that(packages.contains(p), "loaders:the pure layer keeps " + p + " out");
        }
    }

    // --- shipping downgraded jars (docs/decisions/0006) ---------------------------------------------------------------

    static void shipping() throws IOException {
        Set<String> pure = Set.of("fixture.ship.kernel");
        that(PureShipTask.inShippablePackage("fixture/ship/kernel/Greeting.class", pure, "fixture_ship_mycelium_jvmdg"),
                "shipping:a class in a declared pure package ships");
        that(!PureShipTask.inShippablePackage("fixture/ship/kernelstuff/Other.class", pure, "fixture_ship_mycelium_jvmdg"),
                "shipping:a package that merely starts with a pure package's name does not ship (directory-boundary safe)");
        that(!PureShipTask.inShippablePackage("mycelium/harness/Check.class", pure, "fixture_ship_mycelium_jvmdg"),
                "shipping:the shared harness never ships (Hard rule 2), even though it is part of the same bundle");
        that(PureShipTask.inShippablePackage("fixture_ship_mycelium_jvmdg/xyz/wagyourtail/jvmdg/j9/stub/java_base/J_U_List.class",
                pure, "fixture_ship_mycelium_jvmdg"), "shipping:JvmDowngrader's own shaded backport support classes ship too"
                + " (the downgraded bytecode calls them directly)");
        that(!PureShipTask.inShippablePackage("fixture_ship_mycelium_jvmdgsomethingelse/Foo.class", pure, "fixture_ship_mycelium_jvmdg"),
                "shipping:a package that merely starts with the shade prefix's name is not the shade prefix (directory-boundary safe)");

        Path era = Files.createTempDirectory("mycelium-selftest-ship");
        try {
            JsonObject none = PureShipTask.eraGateEvidence(era, "ship-fixture pure layer", 8);
            that(!none.get("checked").getAsBoolean() && none.has("why"), "shipping:no era.json says so, with why");

            String eraJson = "{\"schema\":3,\"java\":8,\"results\":[{\"bundle\":\"ship-fixture pure layer\",\"status\":\"pass\","
                    + "\"bytecode\":\"downgraded to class version 52\",\"outsideReferences\":106,\"unresolved\":0,\"suites\":[]}]}";
            Files.writeString(era.resolve("era.json"), eraJson, StandardCharsets.UTF_8);
            JsonObject found = PureShipTask.eraGateEvidence(era, "ship-fixture pure layer", 8);
            that(found.get("checked").getAsBoolean() && "pass".equals(found.get("status").getAsString())
                    && "downgraded to class version 52".equals(found.get("bytecode").getAsString()),
                    "shipping:the shipped bytes point at the era gate's own result for that exact bundle");

            JsonObject missing = PureShipTask.eraGateEvidence(era, "some other bundle", 8);
            that(!missing.get("checked").getAsBoolean() && missing.get("why").getAsString().contains("some other bundle"),
                    "shipping:a bundle name the era gate never reported on says so, naming it");
        } finally {
            delete(era);
        }
    }

    // --- downgrading the jar's own classes (docs/decisions/0007) --------------------------------------------------------

    static void downgrading() throws IOException {
        Path dir = Files.createTempDirectory("mycelium-selftest-downgrade");
        try {
            // whether a jar needs downgrading is read from its classes' own versions, not from a promise
            Path jar = dir.resolve("in.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                entry(z, "a/A.class", header(61));
                entry(z, "a/B.class", header(52));
                entry(z, "META-INF/versions/21/a/C.class", header(65));
                entry(z, "notes.txt", "class".getBytes(StandardCharsets.UTF_8));
                entry(z, "pfx/j9/Stub.class", header(52));
            }
            that(DowngradeJarTask.maxMajor(jar) == 61, "downgrade:the newest class version in the jar decides, and a multi-release copy is not the jar's own");
            Path only8 = dir.resolve("only52.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(only8))) {
                entry(z, "a/B.class", header(52));
            }
            that(DowngradeJarTask.maxMajor(only8) == 52, "downgrade:a jar of Java 8 classes reads as 52");

            // a nested or synthetic class follows its outermost class: two compilations do not make the same ones
            Path ship = dir.resolve("ship");
            write(ship.resolve("a/B.class"), "x");
            that(PureShipTask.makesWay(ship, "a/B.class"), "downgrade:a class ship supplies makes way");
            that(PureShipTask.makesWay(ship, "a/B$1.class"), "downgrade:a synthetic class of a class ship supplies makes way (its other compilation has none)");
            that(PureShipTask.makesWay(ship, "a/B$Inner$1.class"), "downgrade:so does a class nested twice");
            that(!PureShipTask.makesWay(ship, "a/C$1.class"), "downgrade:a nested class of a class ship does not supply stays");
            that(!PureShipTask.makesWay(ship, "a/Bee.class"), "downgrade:a name that merely starts like a shipped class stays");
            that(!PureShipTask.makesWay(ship, "a/B.txt"), "downgrade:only classes make way");

            // a backport that throws is the artifact's problem only when the artifact calls it
            Path called = dir.resolve("called.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(called))) {
                entry(z, "pfx/J_Q_Thing.class", throwing("pfx/J_Q_Thing", "onExit", "no backport"));
                entry(z, "own/Caller.class", calling("own/Caller", "pfx/J_Q_Thing", "onExit"));
            }
            that(Linkage.missingStubs(called, java.util.List.of("pfx", "pfx_ports"), false).stream().anyMatch(l -> l.startsWith("own/Caller#run -> ")),
                    "downgrade:a call of a backport that throws is named");
            Path unreached = dir.resolve("unreached.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(unreached))) {
                entry(z, "pfx/J_Q_Thing.class", throwing("pfx/J_Q_Thing", "onExit", "no backport"));
                entry(z, "own/Other.class", calling("own/Other", "java/lang/System", "gc"));
            }
            that(Linkage.missingStubs(unreached, "pfx", false).isEmpty(),
                    "downgrade:a partial backport nothing calls does not fail a jar of the game's own size");
            that(!Linkage.missingStubs(unreached, "pfx", true).isEmpty(),
                    "downgrade:the era gate still names it: a bundle small enough to have no such corner");

            // a member named on a class of the jar that only a class outside it declares is where the era must have it
            Path inherited = dir.resolve("inherited.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(inherited))) {
                entry(z, "own/Loader.class", extending("own/Loader", "java/lang/ClassLoader", "declared"));
                entry(z, "own/Use.class", callingVirtual("own/Use", "own/Loader", "getDefinedPackage", "(Ljava/lang/String;)Ljava/lang/Package;"));
                entry(z, "own/Use2.class", callingVirtual("own/Use2", "own/Loader", "declared", "()V"));
            }
            java.util.Map<String, String> refs = Linkage.outsideReferences(inherited);
            that(refs.containsKey("M java/lang/ClassLoader getDefinedPackage (Ljava/lang/String;)Ljava/lang/Package;"),
                    "downgrade:a method the jar's class inherits from a class outside is traced to it (Java 9's getDefinedPackage on Java 8)");
            that(refs.keySet().stream().noneMatch(r -> r.contains(" declared ")), "downgrade:a method the jar's class declares is not");

            downgradedMetadata(dir);
        } finally {
            delete(dir);
        }
    }

    private static void downgradedMetadata(Path dir) throws IOException {
        String api = "xyz/wagyourtail/jvmdg/j16/RecordComponents";
        String nested = api + "$Value";
        String kind = api + "$Kind";
        String codeMark = api + "$CodeMark";
        String codeDefault = api + "$CodeDefault";
        String codeDependency = api + "$CodeDependency";
        String privateDependency = api + "$PrivateDependency";
        Path artifact = dir.resolve("metadata-in.jar");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(artifact))) {
            entry(z, "fixture/Record.class", recordMetadata(api, nested, kind, codeMark));
        }
        Path apiJar = dir.resolve("api-downgraded-52.jar");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(apiJar))) {
            entry(z, api + ".class", annotationType(api, new String[][] {
                    {"value", "[L" + nested + ";"}, {"kind", "L" + kind + ";"}, {"type", "Ljava/lang/Class;"}}));
            entry(z, nested + ".class", annotationType(nested, new String[][] {{"name", "Ljava/lang/String;"}}));
            entry(z, kind + ".class", enumType(kind));
            entry(z, codeMark + ".class", annotationType(codeMark, new String[][] {{"classRef", "Ljava/lang/Class;", codeDefault}}));
            entry(z, codeDefault + ".class", classUsing(codeDefault, codeDependency));
            entry(z, codeDependency + ".class", plainClass(codeDependency));
        }
        DowngradedMetadata.Result result = DowngradedMetadata.relocate(artifact, apiJar, apiJar, "fixture_private", 8);
        that(result.classes() == 6 && result.names().containsAll(List.of(api, nested, kind, codeMark, codeDefault, codeDependency)),
                "downgrade:annotation, nested annotation, enum, default class literal and bytecode dependency closure is selected");
        String privateRoot = "fixture_private/";
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(artifact.toFile())) {
            that(zip.getEntry(privateRoot + api + ".class") != null && zip.getEntry(privateRoot + nested + ".class") != null
                    && zip.getEntry(privateRoot + kind + ".class") != null && zip.getEntry(privateRoot + codeMark + ".class") != null
                    && zip.getEntry(privateRoot + codeDefault + ".class") != null && zip.getEntry(privateRoot + codeDependency + ".class") != null,
                    "downgrade:annotation API closure is carried under the artifact-owned prefix");
            that(zip.getEntry(api + ".class") == null && zip.getEntry(nested + ".class") == null && zip.getEntry(kind + ".class") == null,
                    "downgrade:original shared JvmDowngrader annotation names are not shipped");
            byte[] rewritten = zip.getInputStream(zip.getEntry("fixture/Record.class")).readAllBytes();
            Set<String> annotations = new HashSet<>();
            Set<String> instructionAnnotations = new HashSet<>();
            new org.objectweb.asm.ClassReader(rewritten).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    annotations.add(descriptor);
                    return null;
                }
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override public org.objectweb.asm.AnnotationVisitor visitInsnAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
                            instructionAnnotations.add(descriptor);
                            return null;
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_FRAMES);
            that(annotations.contains("L" + privateRoot + api + ";"),
                    "downgrade:record runtime annotation descriptor points to the private annotation class");
            that(instructionAnnotations.contains("L" + privateRoot + codeMark + ";"),
                    "downgrade:method-body type-use annotation references are remapped too");
        }
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] {artifact.toUri().toURL()}, null)) {
            Class<?> recordClass = Class.forName("fixture.Record", true, loader);
            java.lang.annotation.Annotation annotation = recordClass.getAnnotations()[0];
            Object[] values = (Object[]) annotation.annotationType().getMethod("value").invoke(annotation);
            that(annotation.annotationType().getName().equals("fixture_private.xyz.wagyourtail.jvmdg.j16.RecordComponents"),
                    "downgrade:record annotation interface resolves from the private prefix");
            that(values.length == 1 && ((java.lang.annotation.Annotation) values[0]).annotationType().getName().equals(
                            "fixture_private.xyz.wagyourtail.jvmdg.j16.RecordComponents$Value"),
                    "downgrade:the Java runtime resolves the record and nested annotations from the private archive classes");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("downgrade:private annotation closure did not resolve at runtime", e);
        }

        String frameOnly = api + "$FrameOnly";
        Path frameArtifact = dir.resolve("metadata-frame-array-in.jar");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(frameArtifact))) {
            entry(z, "fixture/FrameArray.class", framedClass("fixture/FrameArray", "[L" + frameOnly + ";"));
        }
        Path frameApi = dir.resolve("api-frame-array.jar");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(frameApi))) {
            entry(z, frameOnly + ".class", plainClass(frameOnly));
        }
        DowngradedMetadata.Result frameClosure = DowngradedMetadata.relocate(frameArtifact, frameApi, frameApi, "fixture_private", 8);
        that(frameClosure.classes() == 1 && frameClosure.names().contains(frameOnly),
                "downgrade:array component used only by a stack-map frame enters the private metadata closure");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(frameArtifact.toFile())) {
            that(zip.getEntry("fixture_private/" + frameOnly + ".class") != null
                            && zip.getEntry(frameOnly + ".class") == null,
                    "downgrade:frame-only metadata class is shipped privately with no original namespace entry");
        }

        Path incomplete = dir.resolve("api-missing-annotation-class.jar");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(incomplete))) {
            entry(z, api + ".class", annotationType(api, new String[][] {{"value", "[L" + nested + ";"}}));
        }
        Path brokenArtifact = dir.resolve("metadata-missing-class.jar");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(brokenArtifact))) {
            entry(z, "fixture/Record.class", recordMetadata(api, nested, kind, null));
        }
        boolean rejected = false;
        try {
            DowngradedMetadata.relocate(brokenArtifact, incomplete, incomplete, "fixture_private", 8);
        } catch (org.gradle.api.GradleException expected) {
            rejected = expected.getMessage().contains(nested);
        }
        that(rejected, "downgrade:missing nested annotation API class fails instead of leaving unresolved metadata");

        Path tooNewTool = dir.resolve("api-tool-class-too-new.jar");
        byte[] tooNewNested = annotationType(nested, new String[][] {{"name", "Ljava/lang/String;"}});
        tooNewNested[6] = 0;
        tooNewNested[7] = 61;
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(tooNewTool))) {
            entry(z, nested + ".class", tooNewNested);
            entry(z, kind + ".class", enumType(kind));
        }
        String tooNewReason = null;
        try {
            DowngradedMetadata.relocate(brokenArtifact, incomplete, tooNewTool, "fixture_private", 8);
        } catch (org.gradle.api.GradleException expected) {
            tooNewReason = expected.getMessage();
        }
        that(tooNewReason != null && tooNewReason.contains("class version 61"),
                "downgrade:a metadata class above the target floor fails even when supplied by the pinned tool jar"
                        + (tooNewReason == null ? " (no rejection)" : " (" + tooNewReason + ")"));

        Path existingSupport = dir.resolve("metadata-with-existing-private-type.jar");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(existingSupport))) {
            entry(z, "fixture/Record.class", recordMetadata(api, nested, kind, codeMark));
            entry(z, "fixture_private/" + codeDependency + ".class",
                    classUsing("fixture_private/" + codeDependency, "fixture_private/" + privateDependency));
            entry(z, "fixture_private/" + privateDependency + ".class", plainClass("fixture_private/" + privateDependency));
        }
        DowngradedMetadata.Result withExisting = DowngradedMetadata.relocate(existingSupport, apiJar, apiJar, "fixture_private", 8);
        that(withExisting.artifactClasses().containsAll(List.of(codeDependency, privateDependency)),
                "downgrade:the existing same-identity artifact class and its recursively scanned private dependency win");
        Path missingPrivate = dir.resolve("metadata-missing-private-dependency.jar");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(missingPrivate))) {
            entry(z, "fixture/Record.class", recordMetadata(api, nested, kind, codeMark));
            entry(z, "fixture_private/" + codeDependency + ".class",
                    classUsing("fixture_private/" + codeDependency, "fixture_private/" + privateDependency));
        }
        rejected = false;
        try {
            DowngradedMetadata.relocate(missingPrivate, apiJar, apiJar, "fixture_private", 8);
        } catch (org.gradle.api.GradleException expected) {
            rejected = expected.getMessage().contains(privateDependency);
        }
        that(rejected, "downgrade:an existing private closure class with a missing transitive type fails");
    }

    private static byte[] annotationType(String name, String[][] methods) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE
                | org.objectweb.asm.Opcodes.ACC_ABSTRACT | org.objectweb.asm.Opcodes.ACC_ANNOTATION,
                name, null, "java/lang/Object", new String[] {"java/lang/annotation/Annotation"});
        org.objectweb.asm.AnnotationVisitor retention = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true);
        retention.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME");
        retention.visitEnd();
        org.objectweb.asm.AnnotationVisitor target = cw.visitAnnotation("Ljava/lang/annotation/Target;", true);
        org.objectweb.asm.AnnotationVisitor targets = target.visitArray("value");
        targets.visitEnum(null, "Ljava/lang/annotation/ElementType;", "TYPE");
        targets.visitEnum(null, "Ljava/lang/annotation/ElementType;", "TYPE_USE");
        targets.visitEnd();
        target.visitEnd();
        for (String[] method : methods) {
            org.objectweb.asm.MethodVisitor visitor = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                    method[0], "()" + method[1], null, null);
            if (method.length > 2) {
                org.objectweb.asm.AnnotationVisitor defaultValue = visitor.visitAnnotationDefault();
                defaultValue.visit(null, org.objectweb.asm.Type.getObjectType(method[2]));
                defaultValue.visitEnd();
            }
            visitor.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] enumType(String name) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL
                | org.objectweb.asm.Opcodes.ACC_ENUM, name, null, "java/lang/Enum", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] recordMetadata(String api, String nested, String kind, String codeMark) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, "fixture/Record", null, "java/lang/Object", null);
        org.objectweb.asm.AnnotationVisitor record = cw.visitAnnotation("L" + api + ";", true);
        org.objectweb.asm.AnnotationVisitor values = record.visitArray("value");
        org.objectweb.asm.AnnotationVisitor value = values.visitAnnotation(null, "L" + nested + ";");
        value.visit("name", "field");
        value.visitEnd();
        values.visitEnd();
        record.visitEnum("kind", "L" + kind + ";", "VALUE");
        record.visit("type", org.objectweb.asm.Type.getObjectType(nested));
        record.visitEnd();
        if (codeMark != null) {
            org.objectweb.asm.MethodVisitor method = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "withTypeUse", "()V", null, null);
            method.visitCode();
            method.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "java/lang/Object");
            method.visitInsnAnnotation(org.objectweb.asm.TypeReference.newTypeReference(org.objectweb.asm.TypeReference.NEW).getValue(),
                    null, "L" + codeMark + ";", true).visitEnd();
            method.visitInsn(org.objectweb.asm.Opcodes.DUP);
            method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            method.visitInsn(org.objectweb.asm.Opcodes.POP);
            method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
            method.visitMaxs(2, 1);
            method.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] plainClass(String name) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classUsing(String name, String dependency) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor method = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "use", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, dependency);
        method.visitInsn(org.objectweb.asm.Opcodes.DUP);
        method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, dependency, "<init>", "()V", false);
        method.visitInsn(org.objectweb.asm.Opcodes.POP);
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class extending {@code superName} that declares one static method {@code declared()V}. */
    private static byte[] extending(String owner, String superName, String declared) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, superName, null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, declared, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose {@code run} calls {@code target.method(desc)} on a null receiver (never run; only read). */
    private static byte[] callingVirtual(String owner, String target, String method, String desc) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, target);
        if (desc.startsWith("(Ljava/lang/String;)")) {
            mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        }
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, target, method, desc, false);
        if (!desc.endsWith(")V")) {
            mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        }
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] header(int major) {
        return new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, (byte) (major >> 8), (byte) major};
    }

    private static void entry(java.util.zip.ZipOutputStream z, String name, byte[] bytes) throws IOException {
        z.putNextEntry(new java.util.zip.ZipEntry(name));
        z.write(bytes);
        z.closeEntry();
    }

    /** A class whose static method {@code name} throws {@code MissingStubError(what)}, as JvmDowngrader writes one. */
    private static byte[] throwing(String owner, String name, String what) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                name, "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "xyz/wagyourtail/jvmdg/exc/MissingStubError");
        mv.visitInsn(org.objectweb.asm.Opcodes.DUP);
        mv.visitLdcInsn(what);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "xyz/wagyourtail/jvmdg/exc/MissingStubError", "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.ATHROW);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose static {@code run} calls {@code target.method()} (static, no arguments). */
    private static byte[] calling(String owner, String target, String method) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, target, method, "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // --- the portability surface (docs/decisions/0006) ----------------------------------------------------------------

    static void surface() {
        that(SurfaceTask.count("net.minecraft.World w;", "net.minecraft") == 1, "surface:one reference counts once");
        that(SurfaceTask.count("net.minecraft.World a; net.minecraft.World b;", "net.minecraft") == 2,
                "surface:the same reference twice counts twice (a textual count, not a distinct-type count)");
        that(SurfaceTask.count("import net.minecraft.server.MinecraftServer;", "net.minecraft") == 1,
                "surface:a class in a package under the root counts");
        that(SurfaceTask.count("net.minecraftforge.Something s;", "net.minecraft") == 0,
                "surface:a package that merely starts with the platform root's name is not a reference to it");
        // count() itself takes already-stripped code, exactly as SurfaceTask.run() strips it: comments out
        // (IsolationTask.code()), then string literal contents out too (PurityTask.STRING) — once, not by count() on
        // every call.
        that(SurfaceTask.count(strip("// net.minecraft.World w;\n/* net.minecraft.World */"), "net.minecraft") == 0,
                "surface:a mention only in a comment is not a reference");
        that(SurfaceTask.count(strip("String s = \"net.minecraft.World\";"), "net.minecraft") == 0,
                "surface:a mention only in a string literal is not a reference");
        that("com.example.mymod.core".equals(SurfaceTask.packageOf("package com.example.mymod.core;\nclass X {}")),
                "surface:the package a file declares is read the same way everywhere else in the plugin reads it");
    }

    /** Exactly how {@link SurfaceTask#run()} strips a file before counting: comments, then string literal contents. */
    static String strip(String text) {
        return PurityTask.STRING.matcher(IsolationTask.code(text)).replaceAll("\"\"");
    }

    // --- the repository check, on broken copies -----------------------------------------------------------------------

    static final Set<String> SKIP = Set.of("build", "out", ".gradle", ".git", ".jvmdg", "archive");

    static void repository(Path root) throws IOException {
        Path base = copy(root);
        Set<String> baseline = findings(base);
        expect(root, "an unregistered package on disk", r -> mkdirs(r.resolve("contracts/zzz-unregistered/src/mycelium/zzz/v1/model")),
                f -> f.contains("zzz-unregistered") && f.contains("no row"), baseline);
        expect(root, "a registry row with no package", r -> rename(r.resolve("contracts/cue"), r.resolve("contracts/cue-moved")),
                f -> f.contains("presentation.particle-cue@1") && f.contains("not a package on disk"), baseline);
        expect(root, "a transaction copy that drifted", r -> replace(r.resolve("contracts/cue/src/mycelium/cue/v1/model/Tx.java"),
                        "undos.clear();", "undos.clear(); undos.clear();"),
                f -> f.contains("cue/v1/model/Tx.java") && f.contains("transaction"), baseline);
        expect(root, "only the class's name and comments changed", r -> {
                    Path tx = r.resolve("contracts/cue/src/mycelium/cue/v1/model/Tx.java");
                    replace(tx, "public final class Tx", "/** renamed */ public final class Tx");
                },
                f -> f.contains("cue/v1/model/Tx.java"), baseline, false);
        expect(root, "an era bound that names no real version", r -> replace(r.resolve("eras/minecraft.json"), "\"to\": \"b1.x\"", "\"to\": \"b1.9\""),
                f -> f.contains("'b1.9'") && f.contains("not a version the launcher names"), baseline);
        expect(root, "an era naming a loader with no packages", r -> replace(r.resolve("eras/minecraft.json"),
                        "\"babric\",", "\"babric\", \"riftloader\","),
                f -> f.contains("riftloader"), baseline);
        expect(root, "a gap in the era table", r -> replace(r.resolve("eras/minecraft.json"), "\"from\": \"1.18\"", "\"from\": \"1.19\""),
                f -> f.contains("1.18") && f.contains("no era"), baseline);
        expect(root, "a wrong count in README's status line", r -> replaceRegex(r.resolve("README.md"), "(\\| Status \\|[^\\n]*?)\\d+ packages", "$1999 packages"),
                f -> f.contains("999 packages"), baseline);
        expect(root, "a count stated outside the status line", r -> append(r.resolve("AGENTS.md"), "\nThere are twelve contract packages.\n"),
                f -> f.contains("AGENTS.md") && f.contains("twelve contract packages"), baseline);
        expect(root, "a path that does not exist", r -> append(r.resolve("CONTRACT-PACKAGE.md"), "\nSee `harness/src/mycelium/harness/Nowhere.java`.\n"),
                f -> f.contains("Nowhere.java"), baseline);
        expect(root, "a package keeping its own harness", r -> write(r.resolve("contracts/cue/src/mycelium/cue/v1/harness/Check.java"),
                        "package mycelium.cue.v1.harness; public final class Check {}"),
                f -> f.contains("cue") && f.contains("own Check.java"), baseline);
        expect(root, "a package whose harness did not report", r -> UNREPORTED.add("presentation.particle-cue@1"),
                f -> f.contains("presentation.particle-cue@1") && f.contains("no harness report"), baseline);
        expect(root, "a path that leaves the repository", r -> append(r.resolve("AGENTS.md"), "\nSee `../elsewhere/AGENTS.md`.\n"),
                f -> f.contains("../elsewhere/AGENTS.md") && f.contains("outside this repository"), baseline);
        expect(root, "a contract document naming a file its package lacks", r -> append(r.resolve("contracts/cue/SPEC.md"),
                        "\nSee `harness/Nowhere.java`.\n"),
                f -> f.contains("contracts/cue/SPEC.md") && f.contains("harness/Nowhere.java"), baseline);
        expect(root, "a contract document naming a file its package has", r -> append(r.resolve("contracts/cue/SPEC.md"),
                        "\nSee `harness/Run.java` and `model/Tx.java`.\n"),
                f -> f.contains("contracts/cue/SPEC.md"), baseline, false);
        expect(root, "a bare file name from a consumer's project", r -> append(r.resolve("CONTRACT-PACKAGE.md"),
                        "\nA consumer's `fabric.mod.json` is its own.\n"),
                f -> f.contains("fabric.mod.json"), baseline, false);
        expect(root, "an era that runs backwards", r -> replaceRegex(r.resolve("eras/minecraft.json"),
                        "\"from\": \"1\\.20\\.5\",(\\s+)\"to\": \"1\\.21\\.x\"", "\"from\": \"1.21.4\",$1\"to\": \"1.20.6\""),
                f -> f.contains("runs backwards"), baseline);
        expect(root, "a model reaching another contract", r -> append(r.resolve("contracts/cue/src/mycelium/cue/v1/model/Tx.java"),
                        "\nclass Reach { mycelium.flow.v1.model.Receipt r; }\n"),
                f -> f.contains("reaches mycelium.flow.v1"), baseline);
        expect(root, "a plugin class the map does not name", r -> write(r.resolve("gradle-plugin/src/main/java/dev/woflo/mycelium/gradle/Unmapped.java"),
                        "package dev.woflo.mycelium.gradle; final class Unmapped {}"),
                f -> f.contains("Unmapped.java"), baseline);
        delete(base);
    }

    interface Break {
        void apply(Path copy) throws IOException;
    }

    static void expect(Path root, String what, Break change, Predicate<String> finding, Set<String> baseline) throws IOException {
        expect(root, what, change, finding, baseline, true);
    }

    static void expect(Path root, String what, Break change, Predicate<String> finding, Set<String> baseline, boolean fires)
            throws IOException {
        Path copy = copy(root);
        try {
            change.apply(copy);
            List<String> fresh = new ArrayList<>();
            for (String f : findings(copy)) {
                if (!baseline.contains(f)) {
                    fresh.add(f);
                }
            }
            boolean fired = fresh.stream().anyMatch(finding);
            that(fired == fires, "repository:" + what + (fires ? " is found" : " is not a finding") + " (new findings: " + fresh + ")");
        } finally {
            UNREPORTED.clear();
            delete(copy);
        }
    }

    /** Contracts whose harness report the next check is not handed (a break of what ran, not of a file). */
    static final Set<String> UNREPORTED = new java.util.HashSet<>();

    /** What myceliumVerify would hand the check: a report for every registered contract, but those {@link #UNREPORTED}. */
    static java.util.Map<String, Integer> reported(Path copy) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try {
            for (ContractRegistry.Entry e : ContractRegistry.load(copy.toFile()).all()) {
                if (!UNREPORTED.contains(e.id())) {
                    out.put(e.id(), 1);
                }
            }
        } catch (RuntimeException e) {
            // a registry that does not load is the check's own finding
        }
        return out;
    }

    static Set<String> findings(Path copy) throws IOException {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (RepositoryCheck.Finding f : RepositoryCheck.run(copy, reported(copy)).findings()) {
            out.add(f.toString().replace(copy.toString().replace('\\', '/'), "<root>"));
        }
        return out;
    }

    static Path copy(Path root) throws IOException {
        Path to = Files.createTempDirectory("mycelium-selftest");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = root.relativize(p);
                boolean skip = false;
                for (Path part : rel) {
                    if (SKIP.contains(part.toString())) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    continue;
                }
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        return to;
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    static void mkdirs(Path p) throws IOException {
        Files.createDirectories(p);
    }

    static void rename(Path from, Path to) throws IOException {
        Files.move(from, to);
    }

    static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    static void append(Path p, String text) throws IOException {
        Files.writeString(p, Files.readString(p, StandardCharsets.UTF_8) + text, StandardCharsets.UTF_8);
    }

    static void replace(Path p, String from, String to) throws IOException {
        String s = Files.readString(p, StandardCharsets.UTF_8);
        if (!s.contains(from)) {
            throw new IllegalStateException("self-test setup: '" + from + "' not in " + p);
        }
        Files.writeString(p, s.replace(from, to), StandardCharsets.UTF_8);
    }

    static void replaceRegex(Path p, String regex, String to) throws IOException {
        String s = Files.readString(p, StandardCharsets.UTF_8);
        String t = s.replaceFirst(regex, to);
        if (t.equals(s)) {
            throw new IllegalStateException("self-test setup: /" + regex + "/ not in " + p);
        }
        Files.writeString(p, t, StandardCharsets.UTF_8);
    }
}
