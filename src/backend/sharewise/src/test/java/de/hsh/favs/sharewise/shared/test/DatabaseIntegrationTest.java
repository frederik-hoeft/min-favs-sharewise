package de.hsh.favs.sharewise.shared.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks a database-backed integration test (P1.3c rollback-isolated operation test or real-commit
 * workflow test) requiring a provisioned PostgreSQL instance.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("integration")
public @interface DatabaseIntegrationTest {
}
