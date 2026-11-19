package de.hsh.favs.sharewise.shared.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks an offline Spring component or MockMvc web-slice test within the Unit suite.
 * The component tag describes the test technique, not a fourth executable test tier.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("component")
public @interface ComponentTest {
}
