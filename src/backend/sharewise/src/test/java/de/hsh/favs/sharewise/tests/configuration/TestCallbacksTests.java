package de.hsh.favs.sharewise.tests.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hsh.favs.sharewise.testsupport.execution.TestCallbacks;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class TestCallbacksTests {

    @Test
    void checkedExceptionsBecomeAssertionFailuresWithTheirOriginalDetails() {
        IOException original = new IOException("read failed");
        original.addSuppressed(new IllegalStateException("cleanup failed"));
        AssertionError failure = assertThrows(AssertionError.class, () ->
            TestCallbacks.assertSucceeds("database setup", () -> { throw original; }));
        assertSame(original, failure.getCause());
        assertSame(original.getSuppressed()[0], failure.getCause().getSuppressed()[0]);
        assertTrue(failure.getMessage().contains("database setup"));
    }

    @Test
    void originalAssertionFailuresAreNotWrapped() {
        AssertionError original = new AssertionError("expected one row");
        assertSame(original, assertThrows(AssertionError.class, () ->
            TestCallbacks.assertSucceeds("query", () -> { throw original; })));
    }

    @Test
    void normalCompletionReturnsWithoutInterference() {
        int[] executed = { 0 };
        TestCallbacks.assertSucceeds("operation", () -> executed[0]++);
        assertEquals(1, executed[0]);
    }

    @Test
    void interruptionStatusIsPreserved() {
        try {
            AssertionError failure = assertThrows(AssertionError.class, () ->
                TestCallbacks.assertSucceeds("interrupted", () -> { throw new InterruptedException("stop"); }));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
