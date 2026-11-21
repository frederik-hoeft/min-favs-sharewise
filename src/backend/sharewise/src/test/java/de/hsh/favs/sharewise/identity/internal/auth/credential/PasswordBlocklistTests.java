package de.hsh.favs.sharewise.identity.internal.auth.credential;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasswordBlocklistTests {
    @TempDir Path directory;

    @Test
    void loadsPinnedVersionedLocalListAndRejectsChangedOrUndersizedFiles() throws Exception {
        Path list = directory.resolve("reviewed-passwords.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            content.append("popular-password-").append(i).append('\n');
        }
        Files.writeString(list, content.toString(), StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(list)));
        PasswordBlocklist blocklist = PasswordBlocklist.load(list, "synthetic-test-1", sha256);
        assertTrue(blocklist.contains("POPULAR-PASSWORD-9999"));
        assertFalse(blocklist.contains("secure-random-user-password"));
        assertThrows(IllegalStateException.class,
                () -> PasswordBlocklist.load(list, "synthetic-test-1", "0".repeat(64)));
        Files.writeString(list, "just one password\n");
        String insufficient = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(list)));
        assertThrows(IllegalStateException.class,
                () -> PasswordBlocklist.load(list, "synthetic-test-1", insufficient));
    }
}
