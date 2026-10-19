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

    /** SHA-256 of exactly the file's bytes, without a filename prefix or text normalization. */
    public static String ofFile(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int size; (size = input.read(buffer)) >= 0;) digest.update(buffer, 0, size);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("cannot fingerprint file " + file, failure);
        }
    }

    public static String ofDirectory(Path dir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(dir)) {
                // a single file (contracts/registry.json): before this, a file hashed as "missing", so editing the
                // registry never re-ran a task
                md.update(dir.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(normalized(dir));
            } else if (Files.isDirectory(dir)) {
                List<Path> files = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(files::add);
                }
                files.sort((a, b) -> a.toString().compareTo(b.toString()));
                for (Path file : files) {
                    md.update(dir.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update(normalized(file));
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

    /**
     * A file's bytes, with the line endings of a text file made one: a Windows checkout and a Linux one are the same sources
     * ({@code Proofs} does the same for a contract's fingerprint). Only a file whose extension names text is touched; a binary file's
     * bytes are its own.
     */
    static byte[] normalized(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        if (!TEXT.stream().anyMatch(name::endsWith)) {
            return bytes;
        }
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static final List<String> TEXT = List.of(".java", ".kt", ".scala", ".json", ".txt", ".md", ".toml", ".properties", ".mcmeta", ".kts", ".gradle");

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
