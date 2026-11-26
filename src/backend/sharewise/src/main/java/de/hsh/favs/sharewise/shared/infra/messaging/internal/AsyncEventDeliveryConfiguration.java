package de.hsh.favs.sharewise.shared.infra.messaging.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables asynchronous delivery for transactional event listeners.
 *
 * <p>Listeners run on Spring Boot's application task executor, which is bounded through
 * {@code spring.task.execution.*}. Durability does not depend on this executor: the Spring
 * Modulith publication registry keeps every publication until its listener succeeds, so a
 * rejected or interrupted hand-off is recovered rather than lost.
 *
 * <p>Spring Modulith also enables async processing conditionally; declaring it here keeps the
 * requirement explicit and independent of that framework detail.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncEventDeliveryConfiguration {
}
