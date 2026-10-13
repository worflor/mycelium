package dev.woflo.mycelium.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * The pinned name data on disk ({@code docs/decisions/0010}): each input of {@link NameData} is kept under the SHA-256 it
 * is pinned to, fetched once and read from disk after, so a build that has run once links offline. A file that does not
 * hash to its pin is never used (a corrupt cache entry is fetched again; a download that does not match is refused and
 * named), and a build that cannot fetch what it lacks says which input it could not have.
 */
final class NameCache {
    /** An input that is not on disk and could not be fetched (offline, or the host did not answer): the version is not linked. */
    static final class Unavailable extends IOException {
        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Path dir;
    private final boolean offline;

    NameCache(Path dir, boolean offline) {
        this.dir = dir;
        this.offline = offline;
    }

    /** The cache directory: what is derived from the pinned files (a game opened into jars) is kept beside them. */
    Path dir() {
        return dir;
    }

    /** The cached file for {@code input}, fetched first when absent. Verified against the pin either way. */
    Path get(NameData.Input input) throws IOException {
        Path file = dir.resolve(input.sha256());
        if (Files.isRegularFile(file)) {
            if (sha256(file).equals(input.sha256())) {
                return file;
            }
            Files.delete(file);
        }
        if (offline) {
            throw new Unavailable("the " + input.role() + " input " + input.url() + " (sha256 " + input.sha256()
                    + ") is not in the name cache " + dir + ", and the build is offline", null);
        }
        Files.createDirectories(dir);
        Path part = Files.createTempFile(dir, "fetch-", ".part");
        try {
            IOException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    download(input.url(), part);
                    last = null;
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }
            if (last != null) {
                throw new Unavailable("could not fetch " + input.url() + " (" + last.getMessage() + ")", last);
            }
            String actual = sha256(part);
            if (!actual.equals(input.sha256())) {
                throw new IllegalStateException("Mycelium: " + input.url() + " has SHA-256 " + actual + ", not the " + input.sha256()
                        + " the name data pins: the published file changed, or the pin is wrong");
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return file;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static void download(String url, Path into) throws IOException {
        URI uri = URI.create(url);
        if (uri.getScheme().equals("file")) {
            Files.copy(Path.of(uri), into, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
            HttpResponse<Path> response = client.send(HttpRequest.newBuilder(uri).header("User-Agent", "mycelium-linkage")
                    .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofFile(into));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
