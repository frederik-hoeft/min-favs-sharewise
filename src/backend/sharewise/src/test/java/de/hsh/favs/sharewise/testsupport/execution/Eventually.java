package de.hsh.favs.sharewise.testsupport.execution;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded polling for effects that become visible asynchronously, such as event delivery after
 * commit. Each wait has a deadline and fails with its description; conditions read committed state
 * afresh on every attempt. Use only for genuinely asynchronous effects, never to order steps that
 * a test can coordinate explicitly.
 */
public final class Eventually {

    /** Default deadline for asynchronous delivery in E2E tests. */
    public static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final long POLL_MILLIS = 50;

    @FunctionalInterface
    public interface Condition {
        boolean holds() throws Exception;
    }

    private Eventually() {
    }

    public static void await(String description, Condition condition) {
        await(description, TIMEOUT, condition);
    }

    /** Polls until {@code condition} holds; a condition failure fails the wait immediately. */
    public static void await(String description, Duration timeout, Condition condition) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(condition, "condition");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                if (condition.holds()) {
                    return;
                }
            } catch (AssertionError failure) {
                throw failure;
            } catch (Exception failure) {
                throw new AssertionError("Condition failed while waiting for " + description, failure);
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out after " + timeout + " waiting for " + description);
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, interrupted);
            }
        }
    }
}
