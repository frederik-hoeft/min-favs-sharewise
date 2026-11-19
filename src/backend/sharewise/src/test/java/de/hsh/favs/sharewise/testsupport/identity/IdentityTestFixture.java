package de.hsh.favs.sharewise.testsupport.identity;

import java.lang.ScopedValue;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lexically scoped, test-only caller input for {@link IdentityTestConfiguration}.
 *
 * <p>Bind an identity around {@code OperationDispatcher.dispatch(...)}; the
 * operation-scoped Spring bean captures a snapshot when first resolved. There
 * is no shared mutable caller, servlet request, or production fallback.
 */
public final class IdentityTestFixture {

    private static final ScopedValue<Supplier<Optional<UUID>>> CURRENT = ScopedValue.newInstance();

    private IdentityTestFixture() {
    }

    /** Executes the action with a deterministic authenticated IAM user. */
    public static <T> T withUser(UUID userId, Supplier<T> action) {
        Objects.requireNonNull(userId, "userId");
        return bind(() -> Optional.of(userId), action);
    }

    /** Executes the action without an authenticated user. */
    public static <T> T anonymously(Supplier<T> action) {
        return bind(Optional::empty, action);
    }

    /** Simulates a failure while obtaining the caller; never treats it as anonymous. */
    public static <T> T withFailure(RuntimeException failure, Supplier<T> action) {
        Objects.requireNonNull(failure, "failure");
        return bind(() -> {
            throw failure;
        }, action);
    }

    static Optional<UUID> currentUserId() {
        // Unbound test operations are anonymous, not signed-in by default.
        return CURRENT.isBound()
            ? Objects.requireNonNull(CURRENT.get().get(), "test identity provider returned null")
            : Optional.empty();
    }

    private static <T> T bind(Supplier<Optional<UUID>> identity, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return ScopedValue.where(CURRENT, identity).call(action::get);
    }
}
