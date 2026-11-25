package de.hsh.favs.sharewise.shared.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResultTests {

    private enum SampleError {
        NOT_FOUND,
        CONFLICT
    }

    @Test
    void okHoldsSuccessValueAndMapsCleanly() {
        Result<String, SampleError> result = Result.ok("created");
        assertTrue(result.isSuccess());
        assertFalse(result.isFailure());
        assertEquals(new Result.Ok<>("created"), result);

        Result<Integer, SampleError> mapped = result.map(String::length);
        assertEquals(Result.ok(7), mapped);
        assertEquals(Result.ok("created"), result.mapError(Enum::name));
        assertEquals("ok:created", result.fold(v -> "ok:" + v, err -> "err:" + err));
    }

    @Test
    void errHoldsErrorValueAndSkipsSuccessMapping() {
        Result<String, SampleError> result = Result.err(SampleError.CONFLICT);
        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
        assertEquals(new Result.Err<>(SampleError.CONFLICT), result);

        Result<Integer, SampleError> mapped = result.map(String::length);
        assertEquals(Result.err(SampleError.CONFLICT), mapped);
        assertEquals(Result.err("CONFLICT"), result.mapError(Enum::name));
        assertEquals("err:CONFLICT", result.fold(v -> "ok:" + v, e -> "err:" + e));
    }

    @Test
    void flatMapChainsSuccessfulStepsAndShortCircuitsErrors() {
        Result<String, SampleError> ok = Result.ok("created");
        assertEquals(Result.ok(7), ok.flatMap(value -> Result.ok(value.length())));
        assertEquals(Result.err(SampleError.NOT_FOUND), ok.flatMap(value -> Result.err(SampleError.NOT_FOUND)));

        Result<String, SampleError> err = Result.err(SampleError.CONFLICT);
        assertEquals(Result.err(SampleError.CONFLICT), err.flatMap(value -> {
            throw new AssertionError("step must not run after an error");
        }));
        assertThrows(NullPointerException.class, () -> ok.flatMap(value -> null));
    }

    @Test
    void nullValuesAndMappersAreRejected() {
        assertThrows(NullPointerException.class, () -> Result.ok(null));
        assertThrows(NullPointerException.class, () -> Result.err(null));

        Result<String, SampleError> ok = Result.ok("v");
        assertThrows(NullPointerException.class, () -> ok.map(null));
        assertThrows(NullPointerException.class, () -> ok.mapError(null));
        assertThrows(NullPointerException.class, () -> ok.flatMap(null));
        assertThrows(NullPointerException.class, () -> ok.fold(null, Enum::name));
        assertThrows(NullPointerException.class, () -> ok.fold(v -> v, null));
    }
}
