package de.hsh.favs.sharewise.identity.internal.auth.credential;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Exercises the production Spring/Bouncy Castle provider and admission behavior. */
class Argon2CredentialEncoderTests {
    private static final PasswordPolicy POLICY = PasswordPolicy.initial();

    @Test
    void realArgon2idHasReviewedCostAndVerifiesFullNormalizedUnicodeInput() {
        Argon2CredentialEncoder encoder = new Argon2CredentialEncoder(
                POLICY, PasswordBlocklist.fixture(Set.of("password123456789")), 1);
        String encoded = encoder.encode("e\u0301".repeat(40) + "🌍".repeat(80));
        assertTrue(encoded.startsWith("$argon2id$v=19$m=65536,t=3,p=4$"));
        assertTrue(encoder.matches("é".repeat(40) + "🌍".repeat(80), encoded));
        assertFalse(encoder.matches("é".repeat(40) + "🌎".repeat(80), encoded));
        assertFalse(encoder.matches("e".repeat(40) + "🌍".repeat(80), encoded));
        assertFalse(encoder.matches("different-password", "{bcrypt}$2a$..."));
        assertFalse(encoder.matches("different-password", "$argon2id$v=19$m=999999999,t=1,p=1$x$y"));
        assertFalse(encoder.matches("different-password", "not-an-encoded-hash"));
        assertFalse(encoder.matches("different-password",
                encoded.replace("m=65536", "m=4096")));
        assertFalse(encoder.matches("different-password",
                encoded.replace("t=3", "t=1")));
    }

    @Test
    void saltIsRandomAndCommonPasswordsAreRejectedBeforeHashing() {
        Argon2CredentialEncoder encoder = new Argon2CredentialEncoder(
                POLICY, PasswordBlocklist.fixture(Set.of("password123456789")), 1);
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("PASSWORD123456789"));
        String password = "a very lengthy secret!";
        assertNotEquals(encoder.encode(password), encoder.encode(password));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("a".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> encoder.matches("abc\uD800", "bad"));
    }

    @Test
    void admissionIsBoundedAndReleasedAfterExceptions() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PasswordEncoder waitingProvider = new PasswordEncoder() {
            @Override
            public String encode(CharSequence password) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted test", failure);
                }
                throw new IllegalStateException("simulated provider failure");
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                return false;
            }
        };
        Argon2CredentialEncoder bounded = new Argon2CredentialEncoder(
                POLICY, PasswordBlocklist.fixture(Set.of("denylisted-common-password")), 1, waitingProvider);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<String> busy = workers.submit(() -> bounded.encode("a".repeat(30)));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(Argon2CredentialEncoder.PasswordWorkloadExhaustedException.class,
                    () -> bounded.encode("b".repeat(30)));
            release.countDown();
            assertThrows(ExecutionException.class, busy::get);
            // Permit is returned despite provider failure; no deadlock/saturation is retained.
            assertThrows(IllegalStateException.class, () -> bounded.encode("c".repeat(30)));
        } finally {
            release.countDown();
            workers.close();
        }
    }
}
