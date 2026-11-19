package de.hsh.favs.sharewise.testsupport.execution;

import java.util.Objects;

/**
 * Checked callbacks for test code without per-test {@code throws} declarations.
 * JDK {@code Consumer} and {@code BiConsumer} do not declare checked exceptions.
 */
public final class TestCallbacks {

    @FunctionalInterface
    public interface Action<T> {
        void accept(T value) throws Exception;
    }

    @FunctionalInterface
    public interface Action2<T, U> {
        void accept(T first, U second) throws Exception;
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    private TestCallbacks() { }

    /**
     * Preserve assertion errors exactly; report setup, callback and cleanup failures as
     * assertion failures with their original exception (and suppressed failures) as cause.
     * Never suppress JVM-fatal errors, and retain interruption status.
     */
    public static void assertSucceeds(String description, CheckedRunnable action) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(action, "action");
        try {
            action.run();
        } catch (AssertionError assertion) {
            throw assertion;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError(description + " failed: " + failure, failure);
        }
    }
}
