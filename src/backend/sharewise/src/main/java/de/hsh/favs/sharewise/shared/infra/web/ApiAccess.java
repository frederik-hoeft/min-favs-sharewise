package de.hsh.favs.sharewise.shared.infra.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Explicit HTTP admission requirement. Business authorization remains operation-scoped. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiAccess {
    Mode value() default Mode.AUTHENTICATED_IAM;

    /** Required with CUSTOM; references an application-registered policy by name. */
    String policy() default "";

    enum Mode { AUTHENTICATED_IAM, ANONYMOUS, CUSTOM }
}
