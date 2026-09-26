package dev.woflo.mycelium.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stable SHA-256 over a directory's files (relative path + bytes, sorted), or over one file's bytes. Used for evidence
 * and task inputs. A path that is neither hashes as "missing", so a thing appearing or vanishing changes the value too.
 */
public final class Fingerprint {
    private Fingerprint() {
    }

    public static String ofDirectory(Path dir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(dir)) {
                // a single file (contracts/registry.json): before this, a file hashed as "missing", so editing the
                // registry never re-ran a task
                md.update(dir.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(dir));
            } else if (Files.isDirectory(dir)) {
                List<Path> files = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(files::add);
                }
                files.sort((a, b) -> a.toString().compareTo(b.toString()));
                for (Path file : files) {
                    md.update(dir.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update(Files.readAllBytes(file));
                    md.update((byte) 0);
                }
            } else {
                md.update("missing".getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("cannot fingerprint " + dir, e);
        }
    }

    public static String ofDirectories(List<Path> dirs) {
        StringBuilder sb = new StringBuilder();
        for (Path dir : dirs) {
            sb.append(ofDirectory(dir)).append('\n');
        }
        return ofString(sb.toString());
    }

    public static String ofString(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
