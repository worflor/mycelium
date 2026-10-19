package dev.woflo.mycelium.source;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real temporary repositories exercise preservation, reproducibility and merge failures. No network. */
public final class LifecycleTest {
    static int assertions;
    static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    interface Work { void run() throws Exception; }
    static void refuses(Work work, String message) throws Exception {
        try { work.run(); } catch (Exception expected) { check(expected.getMessage().contains(message),expected.toString()); return; }
        throw new AssertionError("Expected refusal: " + message);
    }
    static String commit(Path repo, String message) throws Exception {
        Main.git(repo,"add","-A");
        Main.git(repo,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid","-c","commit.gpgsign=false",
            "-c","core.hooksPath="+repo.resolve(".git/no-hooks"),"commit","--quiet","-m",message);
        return Main.git(repo,"rev-parse","HEAD");
    }
    static JsonObject lock(Path root) throws Exception { return JsonParser.parseString(Files.readString(root.resolve("mycelium.lock"))).getAsJsonObject(); }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("mycelium lifecycle café 雪 ");
        try { exercise(temp); divergentUpdate(temp); }
        finally { Main.removeTemp(temp); }
        System.out.println("Source wrapper: " + assertions + " assertions passed");
    }
    static void exercise(Path temp) throws Exception {
        Path source=Files.createDirectory(temp.resolve("source repo")), consumer=Files.createDirectory(temp.resolve("consumer"));
        Main.git(source,"init","--quiet");
        Files.createDirectories(source.resolve("gradle-plugin"));
        Files.writeString(source.resolve("gradle-plugin/settings.gradle.kts"),"rootProject.name=\"fixture\"\n");
        Files.createDirectories(source.resolve("contracts")); Files.writeString(source.resolve("contracts/registry.json"),"{}\n");
        Files.writeString(source.resolve(".gitignore"),"build/\nignored.txt\n");
        Files.writeString(source.resolve(".gitattributes"),"* text=auto eol=lf\n*.bin binary\n");
        Files.writeString(source.resolve("model.java"),"base\n"); Files.writeString(source.resolve("delete.txt"),"delete me\n");
        Files.writeString(source.resolve("other.txt"),"other\n");
        String base=commit(source,"base");
        Files.writeString(source.resolve("model.java"),"captured\n");
        Main.git(source,"add","model.java");
        Files.writeString(source.resolve("model.java"),"working after staged\n");
        Files.delete(source.resolve("delete.txt"));
        Files.write(source.resolve("bytes.bin"),new byte[]{0,1,2,(byte)255,0});
        Files.writeString(source.resolve("café 雪.txt"),"unicode\n");
        Files.createDirectory(source.resolve("build")); Files.writeString(source.resolve("build/result.txt"),"ignored output\n");
        Files.writeString(source.resolve("ignored.txt"),"ignored\n");
        byte[] originalIndex=Files.readAllBytes(source.resolve(".git/index"));
        String work=Files.readString(source.resolve("model.java"));
        Main.capture(consumer,source,consumer.relativize(source).toString(),base);
        check(Arrays.equals(originalIndex,Files.readAllBytes(source.resolve(".git/index"))),"capture preserves staged index bytes");
        check(Files.readString(source.resolve("model.java")).equals(work),"capture preserves working content");
        JsonObject lock=lock(consumer); check(lock.get("schema").getAsInt()==2,"schema 2");
        check(lock.get("repository").getAsString().equals(source.toString()),"relative repository capture records its absolute consumer-root resolution");
        check(lock.get("effective_tree").getAsString().equals(Main.actualTree(source)),"captured effective tree");
        check(lock.has("patch"),"changed source has patch");
        final Main.Lock recipe=Main.readLock(consumer);
        JsonObject relative=lock.deepCopy(); relative.addProperty("repository",consumer.relativize(source).toString());
        Files.write(consumer.resolve("mycelium.lock"),Main.bytes(relative));
        Path relocated=temp.resolve("different parent/legacy relative checkout");
        Main.prepare(Main.readLock(consumer),relocated);
        check(Main.actualTree(relocated).equals(recipe.tree()),"relative legacy repository is independent of preparation destination");
        Files.write(consumer.resolve("mycelium.lock"),Main.bytes(lock));
        check(Main.repository(consumer,"https://github.com/example/mycelium.git").equals("https://github.com/example/mycelium.git"),"HTTPS transport unchanged");
        check(Main.repository(consumer,"git@github.com:example/mycelium.git").equals("git@github.com:example/mycelium.git"),"SSH scp transport unchanged");
        Path ready=consumer.resolve(".mycelium"); Main.prepare(recipe,ready); Main.prepare(recipe,ready);
        ProcessBuilder cli=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
            "-Dfile.encoding=windows-1252","-cp",System.getProperty("java.class.path"),Main.class.getName(),"locate","--root",consumer.toUri().toASCIIString());
        cli.environment().remove("MYCELIUM_HOME");
        cli.redirectError(temp.resolve("cli-error.log").toFile());
        Process machine=cli.start(); String located=new String(machine.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
        check(machine.waitFor()==0 && located.equals(ready.toRealPath().toString()),"CLI path protocol stays UTF-8 with legacy host encoding");
        check(Main.inspect(recipe,ready).get("matches").getAsBoolean(),"fresh exact checkout");
        check(!Files.exists(ready.resolve("delete.txt")),"deletions captured");
        check(Arrays.equals(Files.readAllBytes(source.resolve("bytes.bin")),Files.readAllBytes(ready.resolve("bytes.bin"))),"binary roundtrip");
        check(!Files.exists(ready.resolve("build/result.txt")),"ignored output not captured");
        check(!Files.exists(ready.resolve("ignored.txt")),"ignored untracked source excluded explicitly");
        check(Arrays.equals(originalIndex,Files.readAllBytes(source.resolve(".git/index"))),"inspection preserves actual index");
        Files.writeString(ready.resolve("extra.java"),"uncaptured\n");
        byte[] dirtyIndex=Files.readAllBytes(ready.resolve(".git/index"));
        refuses(()->Main.prepare(recipe,ready),"left untouched");
        check(Files.readString(ready.resolve("extra.java")).equals("uncaptured\n"),"prepare preserves extra source");
        check(Arrays.equals(dirtyIndex,Files.readAllBytes(ready.resolve(".git/index"))),"prepare preserves index");
        check(!Main.inspect(recipe,ready).get("matches").getAsBoolean(),"new source mismatch detected");
        Files.delete(ready.resolve("extra.java"));
        Main.git(ready,"config","core.autocrlf","true");
        Files.writeString(ready.resolve("model.java"),work.replace("\n","\r\n"));
        check(Main.inspect(recipe,ready).get("matches").getAsBoolean(),"Git canonicalizes CRLF");
        for (String flag:List.of("assume-unchanged","skip-worktree")) {
            Main.git(ready,"update-index","--"+flag,"model.java");
            byte[] flagged=Files.readAllBytes(ready.resolve(".git/index"));
            Files.writeString(ready.resolve("model.java"),"hidden tracked change\n");
            check(!Main.inspect(recipe,ready).get("matches").getAsBoolean(),flag+" cannot hide modified source");
            check(Arrays.equals(flagged,Files.readAllBytes(ready.resolve(".git/index"))),flag+" preserved in real index");
            Main.git(ready,"update-index","--no-"+flag,"model.java");
            Files.writeString(ready.resolve("model.java"),work);
        }
        Main.git(source,"config","diff.mnemonicPrefix","true"); Main.git(source,"config","diff.noprefix","true");
        Main.git(source,"config","color.ui","always");
        Main.capture(consumer,source,source.toString(),base);
        check(Main.readLock(consumer).tree().equals(recipe.tree()),"display config independent");
        Path patch=Main.readLock(consumer).patch(); byte[] originalPatch=Files.readAllBytes(patch);
        Files.writeString(patch,"\n",StandardOpenOption.APPEND);
        refuses(()->Main.readLock(consumer),"digest mismatch"); Files.write(patch,originalPatch);
        byte[] oldLock=Files.readAllBytes(consumer.resolve("mycelium.lock"));
        refuses(()->Main.capture(consumer,source,source.toString(),"main"),"full lowercase");
        check(Arrays.equals(oldLock,Files.readAllBytes(consumer.resolve("mycelium.lock"))),"invalid capture preserves lock");
        Path previousPatch=Main.readLock(consumer).patch();
        Files.writeString(previousPatch,"corrupt immutable patch");
        refuses(()->Main.capture(consumer,source,source.toString(),base),"collision");
        check(Arrays.equals(oldLock,Files.readAllBytes(consumer.resolve("mycelium.lock"))),"failed patch install leaves prior lock unchanged");
        Files.write(previousPatch,originalPatch);
        Main.prepare(Main.readLock(consumer),temp.resolve("after interrupted capture"));
        check(true,"old recipe remains usable after failed capture");
        Path nested=Files.createDirectories(source.resolve("nested/gradle-plugin"));
        Files.createDirectories(source.resolve("nested/contracts")); Files.writeString(source.resolve("nested/contracts/registry.json"),"{}");
        refuses(()->Main.sourceRoot(nested.getParent()),"root of its own"); Main.removeTemp(nested.getParent());
        // A global/info ignore is local machine state and cannot conceal source from capture.
        Files.writeString(source.resolve(".git/info/exclude"),"hidden.java\n"); Files.writeString(source.resolve("hidden.java"),"must be captured\n");
        String withHidden=Main.actualTree(source); check(!withHidden.equals(recipe.tree()),"local ignore cannot hide source"); Files.delete(source.resolve("hidden.java"));
        // A force-added file stays tracked even when the project's ignore rules match it.
        Main.git(source,"add","--force","ignored.txt"); Main.capture(consumer,source,source.toString(),base);
        Path force=temp.resolve("forced checkout"); Main.prepare(Main.readLock(consumer),force);
        check(Files.readString(force.resolve("ignored.txt")).equals("ignored\n"),"force-added ignored source roundtrip");
        // Restore recipe without that file; no real-index reset is needed outside this owned fixture.
        Main.git(source,"reset","--quiet",base,"--","ignored.txt"); Main.capture(consumer,source,source.toString(),base);
        final Main.Lock captured=Main.readLock(consumer);
        Path upstream=temp.resolve("upstream"); Main.git(temp,"clone","--quiet",source.toString(),upstream.toString());
        Files.writeString(upstream.resolve("other.txt"),"upstream addition\n"); String next=commit(upstream,"disjoint upstream");
        Main.git(source,"fetch","--quiet",upstream.toString(),next);
        Path candidate=temp.resolve("update candidate"); Main.update(captured,next,candidate);
        check(Files.readString(candidate.resolve("model.java")).equals(work),"update keeps captured content");
        check(Files.readString(candidate.resolve("other.txt")).equals("upstream addition\n"),"update brings upstream change");
        check(Arrays.equals(Files.readAllBytes(consumer.resolve("mycelium.lock")),Main.bytes(lock(consumer))),"update leaves formatted lock unchanged");
        Main.capture(consumer,candidate,source.toString(),next);
        Path updated=temp.resolve("updated fresh"); Main.prepare(Main.readLock(consumer),updated);
        check(Main.actualTree(updated).equals(Main.actualTree(candidate)),"resolved update capture roundtrip");
        // Restore original lock and force a real overlapping merge conflict.
        Files.write(consumer.resolve("mycelium.lock"),oldLock);
        Main.git(upstream,"checkout","--quiet","--detach",base); Files.writeString(upstream.resolve("model.java"),"conflicting upstream\n");
        String conflict=commit(upstream,"overlap"); Main.git(source,"fetch","--quiet",upstream.toString(),conflict);
        Path conflicted=temp.resolve("conflicted candidate");
        refuses(()->Main.update(captured,conflict,conflicted),"needs Git resolution");
        check(!Main.git(conflicted,"ls-files","--unmerged").isBlank(),"conflict stages preserved");
        check(Files.readString(conflicted.resolve("model.java")).contains("<<<<<<<"),"conflict working file preserved");
        check(Arrays.equals(oldLock,Files.readAllBytes(consumer.resolve("mycelium.lock"))),"conflict leaves lock intact");
        check(Files.readString(source.resolve("model.java")).equals(work),"conflict leaves author source intact");
        Files.writeString(conflicted.resolve("model.java"),"reviewed resolution\n");
        Main.git(conflicted,"add","--","model.java");
        Main.capture(consumer,conflicted,source.toString(),conflict);
        Path resolved=temp.resolve("resolved conflict fresh"); Main.prepare(Main.readLock(consumer),resolved);
        check(Main.actualTree(resolved).equals(Main.actualTree(conflicted)),"resolved conflict captures and prepares without changing active source");
        // A clean fork commit is a valid recipe without an empty patch.
        Main.capture(consumer,upstream,source.toString(),conflict);
        check(!lock(consumer).has("patch") && !lock(consumer).has("patch_sha256"),"patch to no-patch transition");
        Main.prepare(Main.readLock(consumer),temp.resolve("fork commit only"));
        Main.git(upstream,"config","core.filemode","false");
        Main.git(upstream,"update-index","--chmod=+x","model.java");
        Main.capture(consumer,upstream,source.toString(),conflict);
        check(lock(consumer).has("patch"),"staged executable mode captured on Windows");
        Path modes=temp.resolve("executable modes"); Main.prepare(Main.readLock(consumer),modes);
        check(Main.actualTree(modes).equals(Main.actualTree(upstream)),"executable mode roundtrip");
        JsonObject strict=lock(consumer); strict.addProperty("completion","locked"); Files.write(consumer.resolve("mycelium.lock"),Main.bytes(strict));
        refuses(()->Main.run("check","--root",consumer.toString(),"--source",source.toString()),"differs from");
        strict.addProperty("schema",3); Files.write(consumer.resolve("mycelium.lock"),Main.bytes(strict));
        refuses(()->Main.readLock(consumer),"Unsupported lock schema");
        byte[] future=Files.readAllBytes(consumer.resolve("mycelium.lock"));
        refuses(()->Main.capture(consumer,source,source.toString(),base),"will not downgrade");
        check(Arrays.equals(future,Files.readAllBytes(consumer.resolve("mycelium.lock"))),"future lock remains untouched");
    }

    static void divergentUpdate(Path temp) throws Exception {
        Path upstream=Files.createDirectory(temp.resolve("diverged upstream")), consumer=Files.createDirectory(temp.resolve("diverged consumer"));
        Main.git(upstream,"init","--quiet");
        Files.createDirectory(upstream.resolve("gradle-plugin")); Files.writeString(upstream.resolve("gradle-plugin/fixture"),"fixture\n");
        Files.createDirectory(upstream.resolve("contracts")); Files.writeString(upstream.resolve("contracts/registry.json"),"{}\n");
        Files.writeString(upstream.resolve("shared.txt"),"ancestor\n"); String ancestor=commit(upstream,"ancestor");
        Files.writeString(upstream.resolve("old-branch.txt"),"old branch\n"); String old=commit(upstream,"old base");
        Files.writeString(upstream.resolve("consumer.txt"),"consumer patch\n"); Main.capture(consumer,upstream,upstream.toString(),old);
        Files.delete(upstream.resolve("consumer.txt"));
        Main.git(upstream,"checkout","--quiet","--detach",ancestor);
        Files.writeString(upstream.resolve("new-branch.txt"),"new branch\n"); String next=commit(upstream,"new branch");
        byte[] before=Files.readAllBytes(consumer.resolve("mycelium.lock"));
        Path candidate=temp.resolve("diverged update candidate"); Main.update(Main.readLock(consumer),next,candidate);
        check(Files.readString(candidate.resolve("consumer.txt")).replace("\r\n","\n").equals("consumer patch\n"),"diverged update keeps consumer patch");
        check(Files.exists(candidate.resolve("old-branch.txt")) && Files.exists(candidate.resolve("new-branch.txt")),"diverged merge uses real ancestor");
        check(Arrays.equals(before,Files.readAllBytes(consumer.resolve("mycelium.lock"))),"diverged update leaves lock untouched");
    }
}
