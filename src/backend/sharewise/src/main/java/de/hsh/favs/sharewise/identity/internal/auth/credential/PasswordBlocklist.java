package de.hsh.favs.sharewise.identity.internal.auth.credential;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable locally provisioned common/compromised-password denylist; never makes network requests. */
public final class PasswordBlocklist {
    private static final long MAX_LIST_BYTES = 4L * 1024 * 1024;
    private static final int MIN_PRODUCTION_ENTRIES = 10_000;
    private final String version;
    private final Set<String> entries;

    private PasswordBlocklist(String version, Set<String> entries) {
        if (version == null || version.isBlank() || entries.isEmpty()) {
            throw new IllegalArgumentException("A versioned, nonempty password blocklist is required");
        }
        this.version = version;
        this.entries = Set.copyOf(entries);
    }

    /** Production provisioning must supply a reviewed local list and its expected SHA-256. */
    public static PasswordBlocklist load(Path file, String version, String expectedSha256) {
        Objects.requireNonNull(file, "file");
        if (expectedSha256 == null || !expectedSha256.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("A pinned password-blocklist SHA-256 is required");
        }
        try {
            if (Files.size(file) > MAX_LIST_BYTES) {
                throw new IllegalStateException("Password blocklist is too large");
            }
            byte[] bytes = Files.readAllBytes(file);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    expectedSha256.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Password blocklist checksum mismatch");
            }
            String content = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            Set<String> entries = new HashSet<>();
            for (String line : content.split("\\R")) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    entries.add(key(line));
                }
            }
            if (entries.size() < MIN_PRODUCTION_ENTRIES) {
                throw new IllegalStateException("Password blocklist is below the minimum reviewed coverage");
            }
            return new PasswordBlocklist(version, entries);
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot load password blocklist", failure);
        }
    }

    /** Test-only construction from a small explicit fixture, never used by production DI. */
    static PasswordBlocklist fixture(Set<String> entries) {
        Set<String> normalized = new HashSet<>();
        entries.forEach(value -> normalized.add(key(value)));
        return new PasswordBlocklist("unit-fixture", normalized);
    }

    public boolean contains(String nfcPassword) {
        return entries.contains(key(nfcPassword));
    }

    public String version() {
        return version;
    }

    private static String key(String value) {
        return Normalizer.normalize(Objects.requireNonNull(value), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
    }
}
