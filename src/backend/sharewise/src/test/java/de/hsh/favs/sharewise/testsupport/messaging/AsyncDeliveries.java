package de.hsh.favs.sharewise.testsupport.messaging;

import de.hsh.favs.sharewise.testsupport.execution.Eventually;
import de.hsh.favs.sharewise.testsupport.integration.IntegrationTestContext;
import java.util.Objects;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The application task executor that runs asynchronous event listeners. An asynchronous delivery
 * is one executor task that spans the listener and the registry's completion, so an idle
 * executor means every delivery handed off so far has finished, including a completion that
 * failed and left no other trace.
 */
public final class AsyncDeliveries {

    private AsyncDeliveries() {
    }

    /** Waits until the executor runs no task and has none queued. */
    public static void awaitIdle(IntegrationTestContext fixture) {
        Objects.requireNonNull(fixture, "fixture");
        ThreadPoolTaskExecutor executor = fixture.context().getBean(
            TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
        Eventually.await("idle listener executor",
            () -> executor.getActiveCount() == 0 && executor.getQueueSize() == 0);
    }
}
