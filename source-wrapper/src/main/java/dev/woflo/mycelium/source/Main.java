package dev.woflo.mycelium.source;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Source lifecycle only. Git owns transport, object storage, normalization and merge semantics. */
public final class Main {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final String VERSION = "1.0.0";
    record Result(int code, byte[] out, String error) { String text() { return new String(out, StandardCharsets.UTF_8).strip(); } }
    record Lock(Path root, String repository, String revision, Path patch, String patchHash, String tree, String completion) {}

    public static void main(String[] args) {
        // stdout is also the settings hook's protocol, including on pre-UTF-8 Java 17 hosts.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        try { run(args); }
        catch (Exception e) { System.err.println("Mycelium source: " + e.getMessage()); System.exit(2); }
    }

    static void run(String... args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("Mycelium wrapper " + VERSION + " (Java 17+, Git)\n"
                + "prepare [--root DIR] [--destination DIR]\ncheck [--root DIR] [--source DIR] [--report FILE]\n"
                + "capture --source DIR --repository URL --base FULL_COMMIT [--root DIR]\n"
                + "update --revision FULL_COMMIT --destination DIR [--root DIR]\n"
                + "locate [--root DIR]  (Gradle pre-load helper; honors MYCELIUM_HOME)\n"
                + "Completion policy in mycelium.lock: locked or development.");
            return;
        }
        String command = args[0];
        Map<String,String> options = new HashMap<>();
        Set<String> allowed = switch (command) {
            case "prepare" -> Set.of("root", "destination");
            case "check" -> Set.of("root", "source", "report");
            case "locate" -> Set.of("root");
            case "capture" -> Set.of("root", "source", "repository", "base");
            case "update" -> Set.of("root", "revision", "destination");
            default -> throw new IOException("Unknown operation: " + command);
        };
        for (int i=1; i<args.length; i+=2) {
            String key = args[i].startsWith("--") ? args[i].substring(2) : "";
            if (!allowed.contains(key) || i+1 == args.length || options.put(key, args[i+1]) != null)
                throw new IOException("Invalid or repeated option: " + args[i]);
        }
        Path root = path(options.getOrDefault("root", ".")).toAbsolutePath().normalize().toRealPath();
        if (command.equals("capture")) {
            capture(root, path(required(options,"source")), required(options,"repository"), required(options,"base"));
            return;
        }
        Lock lock = readLock(root);
        switch (command) {
            case "prepare" -> prepare(lock, resolve(root, options.getOrDefault("destination", ".mycelium")));
            case "update" -> update(lock, required(options,"revision"), resolve(root, required(options,"destination")));
            case "check", "locate" -> {
                String override = options.getOrDefault("source", System.getenv("MYCELIUM_HOME"));
                if (override != null && !path(override).isAbsolute()) throw new IOException("Source override must be an absolute path.");
                Path source = override == null ? root.resolve(".mycelium") : path(override);
                JsonObject report = inspect(lock, source);
                boolean matches = report.get("matches").getAsBoolean();
                boolean strict = command.equals("locate") ? override == null : lock.completion.equals("locked");
                if (options.containsKey("report")) atomicWrite(resolve(root, options.get("report")), bytes(report));
                if (!matches && strict) throw new IOException("Selected source differs from mycelium.lock: expected " + lock.tree
                    + ", actual " + report.get("actual_tree").getAsString() + ". Capture reviewed changes, or prepare the locked source in a fresh destination.");
                if (!matches) System.err.println("MYCELIUM DEVELOPMENT SOURCE: expected " + lock.tree + ", actual "
                    + report.get("actual_tree").getAsString() + "; source is uncaptured. Completion policy: " + lock.completion + ".");
                if (command.equals("locate")) System.out.println(source.toRealPath());
                else System.out.println(JSON.toJson(report));
            }
        }
    }

    static String required(Map<String,String> options, String key) throws IOException {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IOException("Missing --" + key);
        return value;
    }
    static Path path(String value) { return value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value); }
    static Path resolve(Path root, String value) { return root.resolve(path(value)).toAbsolutePath().normalize(); }
    static byte[] bytes(JsonObject object) { return (JSON.toJson(object) + "\n").getBytes(StandardCharsets.UTF_8); }
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    static String sha(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String hash(JsonObject j, String key, int length) throws IOException {
        String value = string(j,key);
        if (!value.matches("[0-9a-f]{"+length+"}")) throw new IOException(key + " must be a full lowercase hexadecimal identity.");
        return value;
    }
    static String string(JsonObject j, String key) throws IOException {
        JsonElement e = j.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank())
            throw new IOException("Missing or invalid lock field: " + key);
        return e.getAsString();
    }
    static Lock readLock(Path root) throws Exception {
        JsonObject j;
        try { j = JsonParser.parseString(Files.readString(root.resolve("mycelium.lock"))).getAsJsonObject(); }
        catch (Exception e) { throw new IOException("Cannot read mycelium.lock: " + e.getMessage()); }
        if (!j.has("schema") || !j.get("schema").toString().equals("2")) throw new IOException("Unsupported lock schema; expected 2. Use capture to migrate a reviewed source.");
        if (!Set.of("schema","repository","revision","patch","patch_sha256","effective_tree","completion").containsAll(j.keySet()))
            throw new IOException("Unknown lock fields; upgrade the wrapper if this recipe uses a newer format.");
        String repository = repository(root,string(j,"repository")), revision = hash(j,"revision",40), tree = hash(j,"effective_tree",40);
        String completion = string(j,"completion");
        if (!Set.of("locked", "development").contains(completion)) throw new IOException("completion must be locked or development.");
        Path patch = null; String digest = null;
        if (j.has("patch") != j.has("patch_sha256")) throw new IOException("patch and patch_sha256 must be declared together.");
        if (j.has("patch")) {
            patch = inside(root, string(j,"patch")); digest = hash(j,"patch_sha256",64);
            if (!sha(Files.readAllBytes(patch)).equals(digest)) throw new IOException("Patch digest mismatch: " + root.relativize(patch));
        }
        return new Lock(root,repository,revision,patch,digest,tree,completion);
    }
    static void repository(String value) throws IOException {
        // Git's own supported transports remain available; option injection and shell helpers do not.
        if (value.isBlank() || value.startsWith("-") || value.contains("\n") || value.contains("\r") || value.contains("::"))
            throw new IOException("Invalid repository URL or path.");
    }
    static String repository(Path root, String value) throws IOException {
        repository(value);
        boolean drive = File.separatorChar == '\\' && value.matches("^[A-Za-z]:.*");
        if (!drive && value.matches("^[^/\\\\:]+:.*")) return value;
        Path local = path(value);
        if (drive && !local.isAbsolute()) throw new IOException("A local repository drive path must be absolute.");
        return root.resolve(local).toAbsolutePath().normalize().toString();
    }
    static Path inside(Path root, String value) throws IOException {
        Path path = root.resolve(value).normalize().toAbsolutePath();
        if (Path.of(value).isAbsolute() || !path.startsWith(root) || path.equals(root)) throw new IOException("Recipe path must stay inside the consumer repository.");
        noLinks(path);
        return path;
    }
    static void noLinks(Path path) throws IOException {
        for (Path p=path.toAbsolutePath().normalize(); p!=null; p=p.getParent())
            if (Files.isSymbolicLink(p) || (Files.exists(p,LinkOption.NOFOLLOW_LINKS)
                && (!p.toRealPath().equals(p) || Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther())))
                throw new IOException("Symlinks and reparse points are not supported source or recipe paths: " + p);
    }
    static Result process(Path cwd, Map<String,String> extra, String... args) throws Exception {
        return process(cwd, extra, null, args);
    }
    static Result process(Path cwd, Map<String,String> extra, Path input, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.longpaths=true", "-c", "core.quotePath=true", "-c", "color.ui=false", "-C", cwd.toString()));
        command.addAll(List.of(args));
        ProcessBuilder b = new ProcessBuilder(command);
        if (input != null) b.redirectInput(input.toFile());
        for (String key : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE", "GIT_PREFIX", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES"))
            b.environment().remove(key);
        b.environment().keySet().removeIf(k -> k.equals("GIT_CONFIG_COUNT") || k.equals("GIT_CONFIG_PARAMETERS")
            || k.startsWith("GIT_CONFIG_KEY_") || k.startsWith("GIT_CONFIG_VALUE_"));
        b.environment().put("GIT_TERMINAL_PROMPT", "0"); b.environment().put("GIT_OPTIONAL_LOCKS", "0");
        b.environment().put("GIT_NO_REPLACE_OBJECTS", "1");
        b.environment().put("GIT_NO_LAZY_FETCH", "1");
        b.environment().putAll(extra);
        Path error = Files.createTempFile("mycelium-git-", ".err");
        try {
            b.redirectError(error.toFile());
            Process p = b.start(); byte[] output = p.getInputStream().readAllBytes(); int code=p.waitFor();
            return new Result(code,output,Files.readString(error,StandardCharsets.UTF_8).strip());
        } finally { Files.deleteIfExists(error); }
    }
    static byte[] git(Path cwd, Map<String,String> env, String... args) throws Exception {
        Result r = process(cwd,env,args);
        if (r.code != 0) throw new IOException("git " + args[0] + " failed: " + r.error);
        return r.out;
    }
    static String git(Path cwd, String... args) throws Exception { return new String(git(cwd,Map.of(),args), StandardCharsets.UTF_8).strip(); }
    static void sourceRoot(Path source) throws Exception {
        noLinks(source);
        if (!Files.isDirectory(source)) throw new IOException("Source checkout missing. Run mycelium prepare first: " + source);
        Path top = Path.of(git(source,"rev-parse","--show-toplevel")).toRealPath();
        if (!top.equals(source.toRealPath())) throw new IOException("Source must be the root of its own Git checkout, not a directory inside another repository.");
        if (!Files.isDirectory(source.resolve("gradle-plugin")) || !Files.isRegularFile(source.resolve("contracts/registry.json")))
            throw new IOException("Source is not a usable Mycelium checkout (gradle-plugin and contracts/registry.json required).");
    }
    static Path tempIndex(Path source) throws Exception {
        Path temporary = Files.createTempDirectory("mycelium-index-");
        Path actual = Path.of(git(source,"rev-parse","--git-path","index"));
        if (!actual.isAbsolute()) actual = source.resolve(actual);
        if (Files.exists(actual)) Files.copy(actual,temporary.resolve("index"));
        else git(source,Map.of("GIT_INDEX_FILE",temporary.resolve("index").toString()),"read-tree","HEAD");
        return temporary;
    }
    static String actualTree(Path source) throws Exception {
        Path temp = tempIndex(source);
        try {
            Map<String,String> env = Map.of("GIT_INDEX_FILE",temp.resolve("index").toString());
            if (git(source,env,"ls-files","--unmerged").length != 0) throw new IOException("Source has unresolved Git conflicts. Resolve them before capture or use.");
            Path tracked = temp.resolve("tracked"); Files.write(tracked,git(source,env,"ls-files","-z"));
            for (String flag:List.of("--no-assume-unchanged","--no-skip-worktree")) {
                Result cleared=process(source,env,tracked,"update-index",flag,"-z","--stdin");
                if (cleared.code!=0) throw new IOException("Cannot clear private-index flags: " + cleared.error);
            }
            git(source,env,"add","-A","--",".");
            git(source,env,"-c","core.fsmonitor=false","add","--renormalize","--",".");
            // Ignore rules travel with the source. User/global/info excludes may not hide uncaptured source.
            byte[] others = git(source,env,"ls-files","--others","--exclude-per-directory=.gitignore","-z");
            if (others.length != 0) {
                Path names=temp.resolve("paths"); Files.write(names,others);
                git(source,env,"add","--force","--pathspec-from-file="+names,"--pathspec-file-nul");
            }
            String entries = new String(git(source,env,"ls-files","--stage"),StandardCharsets.UTF_8);
            if (entries.lines().anyMatch(s -> s.startsWith("160000 ") || s.startsWith("120000 ")))
                throw new IOException("Source symlinks and submodules are not supported by source recipes.");
            return new String(git(source,env,"write-tree"),StandardCharsets.UTF_8).strip();
        } finally { removeTemp(temp); }
    }
    static String recipeTree(Lock lock, Path source) throws Exception {
        Path temp = Files.createTempDirectory("mycelium-recipe-");
        try {
            Map<String,String> env=Map.of("GIT_INDEX_FILE",temp.resolve("index").toString());
            git(source,env,"read-tree",lock.revision);
            if (lock.patch != null) git(source,env,"apply","--cached","--",lock.patch.toString());
            String tree=new String(git(source,env,"write-tree"),StandardCharsets.UTF_8).strip();
            if (!tree.equals(lock.tree)) throw new IOException("Recipe does not reproduce effective_tree: expected " + lock.tree + ", got " + tree);
            return tree;
        } finally { removeTemp(temp); }
    }
    static JsonObject inspect(Lock lock, Path source) throws Exception {
        sourceRoot(source);
        String tree=actualTree(source), revision=git(source,"rev-parse","HEAD");
        // No fetch. The explicit development checkout need not contain the locked base object.
        if (process(source,Map.of(),"cat-file","-e",lock.revision+"^{commit}").code==0) recipeTree(lock,source);
        JsonObject j=new JsonObject(); j.addProperty("schema",1); j.addProperty("wrapper",VERSION);
        j.addProperty("expected_revision",lock.revision); j.addProperty("expected_tree",lock.tree);
        j.addProperty("actual_revision",revision); j.addProperty("actual_tree",tree);
        j.addProperty("matches",revision.equals(lock.revision) && tree.equals(lock.tree));
        j.addProperty("completion",lock.completion);
        return j;
    }
    static void prepare(Lock lock, Path destination) throws Exception {
        noLinks(destination);
        if (Files.exists(destination)) {
            JsonObject report=inspect(lock,destination);
            if (!report.get("matches").getAsBoolean()) throw new IOException("Existing destination differs from the lock; it was left untouched. Use a fresh destination.");
            System.out.println("Locked source is ready at " + destination); return;
        }
        Files.createDirectories(destination.getParent());
        Path stage=Files.createTempDirectory(destination.getParent(),".mycelium-prepare-");
        try {
            git(stage,"init","--quiet"); git(stage,"remote","add","origin",lock.repository);
            git(stage,"fetch","--quiet","--depth","1","origin",lock.revision);
            git(stage,"-c","core.hooksPath="+stage.resolve(".git/no-hooks"),"checkout","--quiet","--detach","FETCH_HEAD");
            if (!git(stage,"rev-parse","HEAD").equals(lock.revision)) throw new IOException("Fetched revision differs from lock.");
            recipeTree(lock,stage);
            if (lock.patch!=null) git(stage,"apply","--index","--",lock.patch.toString());
            if (!inspect(lock,stage).get("matches").getAsBoolean()) throw new IOException("Prepared source does not reproduce the lock.");
            // Files.move without replacement also protects a destination created during preparation.
            Files.move(stage,destination);
            System.out.println("Locked source is ready at " + destination);
        } finally { if (Files.exists(stage)) removeTemp(stage); }
    }
    static void capture(Path root, Path source, String repository, String base) throws Exception {
        repository=repository(root,repository);
        if (!base.matches("[0-9a-f]{40}")) throw new IOException("--base must be a full lowercase commit identity.");
        source=source.toAbsolutePath().normalize(); sourceRoot(source);
        if (!git(source,"rev-parse",base+"^{commit}").equals(base)) throw new IOException("Base is not the requested commit.");
        Path lockFile=root.resolve("mycelium.lock"); noLinks(lockFile);
        byte[] previous=Files.exists(lockFile)?Files.readAllBytes(lockFile):null;
        String completion="development";
        if (previous!=null) {
            JsonObject old=JsonParser.parseString(new String(previous,StandardCharsets.UTF_8)).getAsJsonObject();
            if (!old.has("schema") || !Set.of("1","2").contains(old.get("schema").toString()))
                throw new IOException("Unsupported existing lock schema; capture will not downgrade a newer recipe.");
            if (old.has("completion")) completion=string(old,"completion");
        }
        if (!Set.of("locked","development").contains(completion)) throw new IOException("Invalid existing completion policy.");
        String tree=actualTree(source);
        Path temp=Files.createTempDirectory("mycelium-capture-");
        try {
            Map<String,String> env=Map.of("GIT_INDEX_FILE",temp.resolve("index").toString());
            git(source,env,"read-tree",tree);
            byte[] patch=git(source,env,"-c","diff.noprefix=false","diff","--cached","--binary","--full-index","--no-ext-diff","--no-textconv","--no-renames","--src-prefix=a/","--dst-prefix=b/",base,"--",".");
            JsonObject j=new JsonObject(); j.addProperty("schema",2); j.addProperty("repository",repository); j.addProperty("revision",base);
            Path candidate=temp.resolve("candidate.patch"); Files.write(candidate,patch);
            Lock provisional=new Lock(root,repository,base,patch.length==0?null:candidate,null,tree,completion);
            recipeTree(provisional,source);
            if (patch.length!=0) {
                String digest=sha(patch), relative="tools/mycelium/patches/"+digest+".patch";
                Path target=inside(root,relative); Files.createDirectories(target.getParent());
                if (Files.exists(target)) {
                    if (!Arrays.equals(patch,Files.readAllBytes(target))) throw new IOException("Content-addressed patch collision.");
                } else atomicWrite(target,patch);
                j.addProperty("patch",relative); j.addProperty("patch_sha256",digest);
            }
            j.addProperty("effective_tree",tree); j.addProperty("completion",completion);
            if (previous==null ? Files.exists(lockFile) : !Arrays.equals(previous,Files.readAllBytes(lockFile)))
                throw new IOException("Lock changed during capture; candidate patch remains, previous recipe was not overwritten.");
            atomicWrite(lockFile,bytes(j));
            System.out.println("Captured source tree " + tree + ". Review mycelium.lock and its optional patch, then prepare a fresh checkout and run the consumer gates.");
        } finally { removeTemp(temp); }
    }
    static String commit(Path repo, String tree, String parent, String message) throws Exception {
        return git(repo,"-c","user.name=Mycelium source wrapper","-c","user.email=source@mycelium.invalid",
            "-c","commit.gpgSign=false","commit-tree",tree,"-p",parent,"-m",message);
    }
    static void update(Lock lock, String revision, Path destination) throws Exception {
        if (!revision.matches("[0-9a-f]{40}")) throw new IOException("--revision must be a full lowercase commit identity.");
        noLinks(destination);
        if (Files.exists(destination)) throw new IOException("Update destination must be fresh. Active source and recipe are never changed by update.");
        // Leave the complete candidate on any merge conflict, available to ordinary Git tools.
        prepare(lock,destination);
        git(destination,"fetch","--quiet","origin",revision);
        if (!git(destination,"rev-parse","FETCH_HEAD").equals(revision)) throw new IOException("Fetched update differs from requested revision.");
        String local=commit(destination,lock.tree,lock.revision,"Captured consumer source");
        git(destination,"read-tree",local);
        git(destination,"-c","core.hooksPath="+destination.resolve(".git/no-hooks"),"checkout","--quiet","--detach",local);
        if (git(destination,"rev-parse","--is-shallow-repository").equals("true"))
            git(destination,"fetch","--quiet","--unshallow","origin",revision);
        Result merge=process(destination,Map.of(),"-c","user.name=Mycelium source wrapper","-c","user.email=source@mycelium.invalid",
            "-c","core.hooksPath="+destination.resolve(".git/no-hooks"),"-c","merge.autoStash=false","merge","--no-commit","--no-ff",revision);
        JsonObject next=new JsonObject(); next.addProperty("repository",lock.repository); next.addProperty("base",revision);
        Path gitDir=Path.of(git(destination,"rev-parse","--absolute-git-dir"));
        atomicWrite(gitDir.resolve("mycelium-update.json"),bytes(next));
        if (merge.code!=0) throw new IOException("Update candidate needs Git resolution at " + destination + ". Active source and lock are unchanged. " + merge.error);
        System.out.println("Update candidate ready at " + destination + ". Review and test it with MYCELIUM_HOME, then capture --source <candidate> --repository "
            + lock.repository + " --base " + revision + ". The active source and lock are unchanged.");
    }
    static void atomicWrite(Path path, byte[] bytes) throws IOException {
        noLinks(path); Files.createDirectories(path.getParent());
        Path temp=Files.createTempFile(path.getParent(),".mycelium-write-",".tmp");
        try {
            Files.write(temp,bytes);
            // No non-atomic fallback: interruption must leave the old recipe usable.
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static void removeTemp(Path directory) throws IOException {
        // Only caller-created temporary directories reach this method; never follows symlinks.
        try (var files=Files.walk(directory)) {
            for (Path p:files.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isSymbolicLink(p)) p.toFile().setWritable(true);
                Files.delete(p);
            }
        }
    }
}
