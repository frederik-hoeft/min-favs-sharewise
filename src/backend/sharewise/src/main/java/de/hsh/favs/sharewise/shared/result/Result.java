package de.hsh.favs.sharewise.shared.result;

import java.util.Objects;
import java.util.function.Function;

/**
 * Represents the outcome of an operation that either succeeds with a value of type {@code T}
 * or fails with an expected domain/application error of type {@code E}.
 *
 * <p>Unlike exceptions, {@code Result} models expected business outcomes (such as not-found,
 * conflict, or policy rejection) without coupling application services to HTTP statuses or
 * forcing transaction rollback.
 *
 * @param <T> the success value type
 * @param <E> the expected error value type
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    /**
     * Creates a successful result holding the given non-null value.
     */
    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    /**
     * Creates a failed result holding the given non-null error.
     */
    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    /**
     * Returns {@code true} if this result is {@link Ok}.
     */
    boolean isSuccess();

    /**
     * Returns {@code true} if this result is {@link Err}.
     */
    default boolean isFailure() {
        return !isSuccess();
    }

    /**
     * Transforms the success value if present, leaving an error unchanged.
     */
    default <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        return switch (this) {
            case Ok<T, E>(T value) -> new Ok<>(mapper.apply(value));
            case Err<T, E>(E error) -> new Err<>(error);
        };
    }

    /**
     * Transforms the error value if present, leaving a success value unchanged.
     */
    default <F> Result<T, F> mapError(Function<? super E, ? extends F> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        return switch (this) {
            case Ok<T, E>(T value) -> new Ok<>(value);
            case Err<T, E>(E error) -> new Err<>(mapper.apply(error));
        };
    }

    /**
     * Folds either branch into a single value.
     */
    default <R> R fold(
            Function<? super T, ? extends R> onSuccess,
            Function<? super E, ? extends R> onError) {
        Objects.requireNonNull(onSuccess, "onSuccess must not be null");
        Objects.requireNonNull(onError, "onError must not be null");
        return switch (this) {
            case Ok<T, E>(T value) -> onSuccess.apply(value);
            case Err<T, E>(E error) -> onError.apply(error);
        };
    }

    /**
     * Successful result variant.
     */
    record Ok<T, E>(T value) implements Result<T, E> {
        public Ok {
            Objects.requireNonNull(value, "value must not be null");
        }

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    /**
     * Failed result variant holding a typed expected error.
     */
    record Err<T, E>(E error) implements Result<T, E> {
        public Err {
            Objects.requireNonNull(error, "error must not be null");
        }

        @Override
        public boolean isSuccess() {
            return false;
        }
    }
}
