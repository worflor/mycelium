package dev.woflo.mycelium.source;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Independent Gradle processes exercise both settings DSLs and the distribution boundary. */
public final class ConsumerTest {
    static int assertions, invocation;
    static Path wrapper, consumer;
    static String java;
    static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    static String gradle(boolean success, Path override, String... tasks) throws Exception {
        List<String> command=new ArrayList<>(List.of(java,"-classpath",wrapper.toString(),"org.gradle.wrapper.GradleWrapperMain",
            "--no-daemon","-Dorg.gradle.jvmargs=-Xmx1g","--max-workers=1","--console=plain","--offline"));
        command.addAll(List.of(tasks));
        Path log=consumer.resolve("../run-"+(++invocation)+".log").normalize();
        ProcessBuilder b=new ProcessBuilder(command).directory(consumer.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        b.environment().remove("MYCELIUM_HOME");
        if (override!=null) b.environment().put("MYCELIUM_HOME",override.toString());
        int code=b.start().waitFor(); String output=Files.readString(log);
        check((code==0)==success,"Gradle exit " + code + " for " + Arrays.toString(tasks) + "\n" + output);
        return output;
    }
    public static void main(String[] args) throws Exception {
        Path bundle=Path.of(args[0]); wrapper=Path.of(args[1]);
        java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Path temp=Files.createTempDirectory(Path.of(args[2]),"consumer café 雪 with spaces-");
        consumer=Files.createDirectory(temp.resolve("mod"));
        try {
            try (ZipFile zip=new ZipFile(bundle.toFile())) {
                for (ZipEntry entry:Collections.list(zip.entries())) {
                    if (entry.isDirectory()) continue;
                    Path file=consumer.resolve(entry.getName()); Files.createDirectories(file.getParent());
                    Files.write(file,zip.getInputStream(entry).readAllBytes());
                }
            }
            Path source=Files.createDirectory(temp.resolve("compiler")); Main.git(source,"init","--quiet");
            Files.createDirectory(source.resolve("contracts")); Files.writeString(source.resolve("contracts/registry.json"),"{}\n");
            Files.writeString(source.resolve(".gitignore"),".gradle/\nbuild/\n");
            Files.writeString(source.resolve(".gitattributes"),"* text=auto eol=lf\n");
            Path plugin=Files.createDirectory(source.resolve("gradle-plugin"));
            Files.writeString(plugin.resolve("settings.gradle"),"rootProject.name='fixture-compiler'\n");
            Files.writeString(plugin.resolve("build.gradle"),"""
                plugins { id 'java-gradle-plugin' }
                java { sourceCompatibility = 17; targetCompatibility = 17 }
                gradlePlugin { plugins { fixture { id='dev.woflo.mycelium'; implementationClass='fixture.FixturePlugin' } } }
                """);
            Path sources=Files.createDirectories(plugin.resolve("src/main/java/fixture"));
            Files.writeString(sources.resolve("FixturePlugin.java"),"""
                package fixture;
                import org.gradle.api.*;
                import org.gradle.api.file.DirectoryProperty;
                import org.gradle.api.provider.ListProperty;
                public final class FixturePlugin implements Plugin<Project> {
                    public abstract static class Extension {
                        public abstract DirectoryProperty getRoot();
                        public abstract ListProperty<String> getArtifactJars();
                    }
                    public void apply(Project p) {
                        p.getExtensions().create("mycelium", Extension.class).getArtifactJars().convention(java.util.List.of());
                    }
                }
                """);
            Main.git(source,"add","-A"); Main.git(source,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid",
                "-c","commit.gpgsign=false","-c","core.hooksPath="+source.resolve(".git/no-hooks"),"commit","--quiet","-m","fixture");
            Main.capture(consumer,source,source.toString(),Main.git(source,"rev-parse","HEAD"));
            var lock=JsonParser.parseString(Files.readString(consumer.resolve("mycelium.lock"))).getAsJsonObject();
            lock.addProperty("completion","locked"); Files.write(consumer.resolve("mycelium.lock"),Main.bytes(lock));
            Main.prepare(Main.readLock(consumer),consumer.resolve(".mycelium"));
            Files.writeString(consumer.resolve("settings.gradle.kts"),"""
                pluginManagement { apply(from = file("tools/mycelium/settings.gradle")) }
                rootProject.name = "consumer"
                """);
            Files.writeString(consumer.resolve("build.gradle"),"""
                plugins { id 'java'; id 'dev.woflo.mycelium' }
                if (providers.gradleProperty('otherRoot').isPresent()) mycelium.root = file('../compiler')
                tasks.register('testOnlyJar', Jar) { from sourceSets.main.output; archiveClassifier = 'tests' }
                """);
            Files.createDirectories(consumer.resolve("src/main/java")); Files.writeString(consumer.resolve("src/main/java/Example.java"),"public class Example {}\n");
            gradle(true,null,"check","jar","--configuration-cache");
            check(gradle(true,null,"check","jar","--configuration-cache").contains("Reusing configuration cache"),"configuration cache reuses verified configuration");
            try (ZipFile zip=new ZipFile(consumer.resolve("build/libs/consumer.jar").toFile())) {
                var evidence=JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("META-INF/mycelium/source.json")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                check(evidence.get("matches").getAsBoolean(),"locked jar has matching provenance");
                check(!evidence.toString().contains(temp.toString()),"provenance carries no checkout path");
            }
            String conflict=gradle(false,null,"jar","-PotherRoot=true");
            check(conflict.contains("mycelium.root selects") && conflict.contains("verified compiler"),"conflicting source root rejected");
            Files.writeString(source.resolve("uncaptured.txt"),"development work\n");
            String compile=gradle(true,source,"compileJava","testOnlyJar");
            check(compile.contains("MYCELIUM DEVELOPMENT SOURCE"),"override compile makes mismatch conspicuous");
            check(!compile.contains("> Task :myceliumSourceCheck"),"test-only archive does not require captured development source");
            check(gradle(false,source,"check").contains("differs from mycelium.lock"),"strict completion rejects override");
            check(gradle(false,source,"jar").contains("differs from mycelium.lock"),"strict jar rejects even existing output");
            lock.addProperty("completion","development"); Files.write(consumer.resolve("mycelium.lock"),Main.bytes(lock));
            gradle(true,source,"check","jar");
            try (ZipFile zip=new ZipFile(consumer.resolve("build/libs/consumer.jar").toFile())) {
                String evidence=new String(zip.getInputStream(zip.getEntry("META-INF/mycelium/source.json")).readAllBytes(),StandardCharsets.UTF_8);
                check(!JsonParser.parseString(evidence).getAsJsonObject().get("matches").getAsBoolean(),"development jar records uncaptured source");
            }
            Files.delete(consumer.resolve("settings.gradle.kts"));
            Files.writeString(consumer.resolve("settings.gradle"),"pluginManagement { apply from: file('tools/mycelium/settings.gradle') }\nrootProject.name='consumer'\n");
            gradle(true,null,"check","jar");
            Files.writeString(consumer.resolve(".mycelium/default-uncaptured.txt"),"must fail before loading\n");
            check(gradle(false,null,"check","jar","--configuration-cache").contains("Selected source differs"),"default mismatch rejected before plugin load despite configuration cache and development policy");
            System.out.println("Consumer hooks: " + assertions + " assertions passed (Groovy and Kotlin settings)");
        } catch (Exception | AssertionError e) {
            System.err.println("Consumer fixture and logs retained at " + temp); throw e;
        }
    }
}
