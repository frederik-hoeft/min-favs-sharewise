package de.hsh.favs.sharewise.shared.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks a rollback-isolated PostgreSQL integration test. Real-commit workflows are E2E tests.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("integration")
public @interface DatabaseIntegrationTest {
}
